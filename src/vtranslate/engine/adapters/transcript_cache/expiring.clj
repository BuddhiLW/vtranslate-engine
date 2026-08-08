(ns vtranslate.engine.adapters.transcript-cache.expiring
  "ITranscriptCache decorator that stops trusting an entry past a TTL.

   Decorates rather than extends the file cache, so where transcripts are kept
   and how long they are believed stay separate choices."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.transcript-cache :as port]))

(def cached-at-key
  "Namespaced so an envelope can never be mistaken for a transcript."
  :transcript-cache/cached-at)

(def transcript-key :transcript-cache/transcript)

(defn envelope
  "Wrap `transcript` with the time it was kept."
  [transcript at-seconds]
  {cached-at-key at-seconds
   transcript-key transcript})

(defn envelope?
  "True for a value this decorator wrote."
  [value]
  (and (map? value) (contains? value cached-at-key)))

(defn unwrap
  "The transcript inside `value`, which may be a bare one written before this
   decorator existed."
  [value]
  (if (envelope? value) (get value transcript-key) value))

(defn expired?
  "Whether `value` is older than `ttl-seconds` at `now-seconds`.

   A bare transcript has no recorded age and is never expired by this test:
   treating an unreadable age as expiry would empty every cache in the field on
   the deploy that introduced the envelope. Reclaiming those is `evict!`'s job,
   which reads the file's own mtime."
  [value ttl-seconds now-seconds]
  (boolean
   (and (pos? (long (or ttl-seconds 0)))
        (envelope? value)
        (> (- (long now-seconds) (long (get value cached-at-key 0)))
           (long ttl-seconds)))))

(defrecord ExpiringCache [delegate ttl-seconds now-seconds]
  port/ITranscriptCache
  (fetch [_ key]
    (r/let-ok [found (port/fetch delegate key)]
      (cond
        (nil? found)
        (r/ok nil)

        (expired? found ttl-seconds (now-seconds))
        (r/let-ok [_ (port/forget! delegate key)]
          (r/ok nil))

        :else
        (r/ok (unwrap found)))))

  (store! [_ key transcript]
    (r/let-ok [stored (port/store! delegate key
                                   (envelope transcript (now-seconds)))]
      ;; Swept here rather than on a schedule: a store follows an ASR run, the
      ;; rarest and most expensive thing the engine does, so the scan is both
      ;; infrequent and paid for.
      (port/evict! delegate ttl-seconds)
      (r/ok stored)))

  (forget! [_ key] (port/forget! delegate key))
  (evict! [_ older-than-seconds] (port/evict! delegate older-than-seconds)))

(defn wrap
  "Give `delegate` a TTL. A non-positive `ttl-seconds` returns it unchanged,
   so \"keep forever\" costs no wrapper."
  ([delegate ttl-seconds]
   (wrap delegate ttl-seconds #(quot (System/currentTimeMillis) 1000)))
  ([delegate ttl-seconds now-seconds]
   (if (pos? (long (or ttl-seconds 0)))
     (->ExpiringCache delegate ttl-seconds now-seconds)
     delegate)))
