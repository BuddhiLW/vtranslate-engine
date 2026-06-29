(ns vtranslate.engine.collect.audio
  "Collect-layer audio API — the sugar the pipeline / boundary actually calls.

   Depends on the collect PROTOCOLS (DIP), not on JavaCV. Responsibilities:
   - validate the source path via hive-system.fs (IPathQuery-backed, Result-returning);
   - drive an INJECTED IMediaProbe / IAudioExtractor backend;
   - project raw backend maps onto the domain ingestion/ProbeInfo value object;
   - wrap every effect's Exception in a hive-dsl Result (railway, via try-effect*);
     native Errors propagate to main/run's process-level Throwable guard;
   - fan out multi-source work under BOUNDED concurrency
     (hive-weave.parallel/bounded-pmap). Each ffmpeg grabber is stateful and
     holds native memory, so one grabber runs per task and the concurrency bound
     is the only thing protecting file handles + off-heap allocation.

   No bytedeco type is ever visible in this namespace."
  (:require [vtranslate.engine.collect.protocols :as p]
            [vtranslate.engine.domain.ingestion :as ingestion]
            [hive-system.fs.core :as fs]
            [hive-weave.parallel :as wp]
            [hive-dsl.result :as r]))

(def default-audio-opts
  "16 kHz mono — the de-facto ASR input format (Whisper et al.)."
  {:sample-rate 16000 :channels 1})

(defn to-probe-info
  "Project a backend probe map onto the domain ProbeInfo value object. Drops the
   collect-only fields (sample-rate/channels) the domain does not model. Pure —
   the boundary's narrowing from backend facts to the domain value object."
  [{:keys [container duration-ms has-audio? audio-codec]}]
  (ingestion/->ProbeInfo container duration-ms has-audio? audio-codec))

(defn- ensure-source
  "=> (r/ok source-uri) when the path exists, else a not-found error."
  [source-uri]
  (r/let-ok [exists? (fs/exists? source-uri)]
    (if exists?
      (r/ok source-uri)
      (r/err :collect/source-not-found {:source-uri source-uri}))))

(defn probe
  "Validate the source exists, probe it, project to the domain ProbeInfo.
   => (r/ok ProbeInfo) | (r/err :collect/source-not-found | :collect/probe-failed)."
  [backend source-uri]
  (r/let-ok [src (ensure-source source-uri)]
    (r/try-effect* :collect/probe-failed
      (to-probe-info (p/probe backend src)))))

(defn extract-audio
  "Validate the source, extract its audio to out-path as PCM/WAV.
   => (r/ok out-path) | (r/err :collect/source-not-found | :collect/extract-failed)."
  ([backend source-uri out-path]
   (extract-audio backend source-uri out-path default-audio-opts))
  ([backend source-uri out-path opts]
   (r/let-ok [src (ensure-source source-uri)]
     (r/try-effect* :collect/extract-failed
       (p/extract-audio backend src out-path (merge default-audio-opts opts))))))

(defn probe-all
  "Bounded-parallel probe of many sources — one grabber per task. The result is a
   vector of Results aligned positionally to `sources`; a task that overruns
   :timeout-ms yields the :collect/probe-timeout fallback Result.

   opts: {:concurrency long (default 4), :timeout-ms long (default 30000)}."
  [backend sources & [{:keys [concurrency timeout-ms]
                       :or   {concurrency 4 timeout-ms 30000}}]]
  (wp/bounded-pmap {:concurrency concurrency
                    :timeout-ms  timeout-ms
                    :fallback    (r/err :collect/probe-timeout {})}
                   (fn [src] (probe backend src))
                   sources))
