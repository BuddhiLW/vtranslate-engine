(ns vtranslate.engine.adapters.transcriber.stub
  "Reference ITranscriber — a DETERMINISTIC fake that tiles the audio duration
   into fixed placeholder segments. NO ASR; NO native dep. Two jobs:
   (1) the conformant impl the port contract runs against (the Liskov reference);
   (2) an EXPLICIT-only dev provider (:stub) that makes the full ASR ingress
       (demux -> transcribe -> translate -> render) runnable end-to-end with zero
       models — the fastest way to exercise Ingress A on real video.

   It is DELIBERATELY absent from router/transcriber-priority: ASR must never
   SILENTLY fall back to a fake transcript (the fail-loud invariant). You only get
   the stub by asking for it — VT_TRANSCRIBER=stub or [:providers :transcriber]."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.providers.transcriber-registry :as reg]))

(defn stub-segments
  "Pure: tile [0, duration-ms) into window-ms spans, each with deterministic
   placeholder text keyed to the source `language` + 1-based index. Always yields
   at least one span so the downstream Transcript can complete."
  [duration-ms window-ms language]
  (let [dur (max 1 (long duration-ms))
        win (max 1 (long window-ms))]
    (sup/normalize-segments
     (map-indexed (fn [i start]
                    {:start-ms   start
                     :end-ms     (min (+ start win) dur)
                     :text       (format "[%s segment %d]" (or language "und") (inc i))
                     :confidence 1.0})
                  (range 0 dur win)))))

(defrecord StubTranscriber [window-ms]
  p.asr/ITranscriber
  (transcribe [_ audio-source language _opts]
    (let [path        (sup/audio->path audio-source)
          duration-ms (or (:duration-ms audio-source)
                          (sup/wav-duration-ms path)
                          3000)]
      (r/ok {:segments (stub-segments duration-ms window-ms language)}))))

(defn make-transcriber
  "Build a StubTranscriber. Default window 5000ms; override via config
   [:transcriber-opts :window-ms]."
  ([] (make-transcriber 5000))
  ([window-ms] (->StubTranscriber window-ms)))

(defmethod reg/resolve-transcriber :stub
  [_ config]
  (r/ok (make-transcriber (get-in config [:transcriber-opts :window-ms] 5000))))
