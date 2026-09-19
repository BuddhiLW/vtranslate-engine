(ns vtranslate.engine.port.transcriber
  "Port (ISP barrier) for speech-to-text. An adapter (M3, e.g. whisper) implements
   ITranscriber; the engine depends only on this protocol (DIP). The method returns
   a hive-dsl Result of plain boundary DATA — a vector of segment maps — which the
   pure calc layer (calc.transcription) promotes into a Transcript aggregate.
   Effects-as-data: the adapter does the IO; the domain never sees the transport.")

(defprotocol ITranscriber
  "Speech-to-text over an extracted audio source."
  (transcribe [this audio-source language opts]
    "=> (r/ok {:segments [{:start-ms n :end-ms n :text s :confidence f} ...]})
        | (r/err :error/asr-failed {:reason s}).
     `audio-source` is the opaque value returned by IAudioExtractor;
     `language` is a BCP-47 source-language tag; `opts` is an adapter map."))

(defn transcribe*
  "Call `transcriber`'s transcribe through a var. An AOT-compiled addon calls
   this rather than the protocol fn: the engine ships as source, so a direct
   protocol call compiled into an addon jar names this protocol's interface and
   throws NoClassDefFoundError in the worker."
  [transcriber audio-source language opts]
  (transcribe transcriber audio-source language opts))

(defn transcriber
  "An ITranscriber whose transcribe is `f` ([audio-source language opts] =>
   Result). Built in engine source, so an AOT-compiled addon can make a
   transcriber without naming this protocol's interface."
  [f]
  (reify ITranscriber
    (transcribe [_ audio-source language opts]
      (f audio-source language opts))))

;; --- call-opt hooks ----------------------------------------------------------
;;
;; Two optional hooks an adapter honours when present in `opts`:
;;   :asr/route-window  (fn [decode language] => Result<raw>), where `decode` is
;;                      (fn [language] => Result<raw>): how one decode window is
;;                      decoded (e.g. decoded again, or its segments tagged).
;;   :asr/clean         (fn [raw-segments opts] => raw-segments): applied to one
;;                      reply's raw segments while server metrics are attached.
;; A transcriber decorator adds its hook with `with-route` / `with-clean`, which
;; compose with the hooks already in `opts`: the decorator nearest the adapter
;; runs first.

(defn- plain-route [decode language] (decode language))

(defn with-route
  "`opts` with `route` composed under the :asr/route-window already there, so
   `route` sees the raw decode and the existing route sees `route`'s result."
  [opts route]
  (let [outer (or (:asr/route-window opts) plain-route)]
    (assoc opts :asr/route-window
           (fn [decode language]
             (outer (fn [lang] (route decode lang)) language)))))

(defn with-clean
  "`opts` with `clean` composed before the :asr/clean already there."
  [opts clean]
  (let [outer (:asr/clean opts)]
    (assoc opts :asr/clean
           (if outer
             (fn [segs o] (outer (clean segs o) o))
             clean))))
