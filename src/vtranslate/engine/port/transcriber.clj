(ns vtranslate.engine.port.transcriber
  "Port (ISP barrier) for speech-to-text. An adapter (M3, e.g. whisper) implements
   ITranscriber; the engine depends only on this protocol (DIP). The method returns
   a hive-dsl Result of plain boundary DATA — a vector of segment maps — which the
   pure calc layer (calc.transcription) promotes into a Transcript aggregate.
   Effects-as-data: the adapter does the IO; the domain never sees the transport."
  (:require [hive-dsl.result :as r]))

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
;;                      An adapter that can also offers (decode language
;;                      {:widen-ms n :transform f}): the window grown by n ms on
;;                      each side and/or its samples through f (float[] =>
;;                      float[]), times still relative to the window's start.
;;   :asr/clean         (fn [raw-segments opts] => raw-segments): applied to one
;;                      reply's raw segments while server metrics are attached.
;; A transcriber decorator adds its hook with `with-route` / `with-clean`, which
;; compose with the hooks already in `opts`: the decorator nearest the adapter
;; runs first.

(defn- plain-route [decode language] (decode language))

(defn with-route
  "`opts` with `route` composed under the :asr/route-window already there, so
   `route` sees the raw decode and the existing route sees `route`'s result. A
   decode the outer route asks for with decode opts goes to the adapter as asked:
   it is a different decode, not the one `route` answers for. The adapter's
   metadata on `decode` (:asr/window-ms) reaches every route."
  [opts route]
  (let [outer (or (:asr/route-window opts) plain-route)]
    (assoc opts :asr/route-window
           (fn [decode language]
             (outer (with-meta
                      (fn
                        ([lang] (route decode lang))
                        ([lang decode-opts] (decode lang decode-opts)))
                      (meta decode))
                    language)))))

(defn with-clean
  "`opts` with `clean` composed before the :asr/clean already there."
  [opts clean]
  (let [outer (:asr/clean opts)]
    (assoc opts :asr/clean
           (if outer
             (fn [segs o] (outer (clean segs o) o))
             clean))))

;; --- running the hooks (the ONE definition of the order) ---------------------

(def hook-keys
  "Every call-opt hook this port defines, in the order they run over one
   decode's raw segments."
  [:asr/route-window :asr/clean])

(defn cleaned
  "`raw` through the :asr/clean hook of `opts`. No hook, no change."
  [opts raw]
  (if-let [clean (:asr/clean opts)] (clean raw opts) raw))

(defn decoded
  "One decode's raw segments through every hook `opts` carries, in this port's
   order: routed, then cleaned. `decode` is (fn [language] => Result<raw>),
   plus the (fn [language decode-opts]) arity an adapter may also offer.
   An adapter that decodes by window calls THIS rather than reading the hook
   keys itself. => Result<raw>"
  [opts decode language]
  (r/let-ok [routed ((or (:asr/route-window opts) plain-route) decode language)]
    (r/ok (cleaned opts routed))))

(defprotocol IDeclaresHooks
  "Optional companion to ITranscriber: which of `hook-keys` this adapter reads.
   An adapter that reads none does not implement it."
  (hooks-honoured [this] "=> a set of hook keys"))

(defn honoured
  "The hooks `transcriber` declares it reads. Declaring nothing honours
   nothing."
  [transcriber]
  (if (satisfies? IDeclaresHooks transcriber) (set (hooks-honoured transcriber)) #{}))

(defn inert-hooks
  "The hooks present in `opts` that `transcriber` does not read, so a decorator
   can say that its contribution will do nothing instead of looking installed.
   => a sorted set of hook keys"
  [transcriber opts]
  (let [live (honoured transcriber)]
    (into (sorted-set) (remove live (filter opts hook-keys)))))

(defn notice!
  "One line on stderr, the channel the CLI streams to its panel. An adapter or
   a transcriber decorator reports through this, so every such line carries one
   prefix and a decorator does not have to invent its own channel."
  [msg]
  (binding [*out* *err*]
    (println (str "[vtranslate/asr] " msg))
    (flush)))
