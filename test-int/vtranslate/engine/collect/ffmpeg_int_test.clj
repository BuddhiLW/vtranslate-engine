(ns vtranslate.engine.collect.ffmpeg-int-test
  "OPT-IN native integration suite (run: clojure -M:test:itest:ffmpeg).
   Loads bytedeco; excluded from the default unit run (test-int/ is off the
   default classpath — path isolation IS the gate). Reuses dev/ helpers +
   hive-test golden. Corpus checks resolve via $VT_CORPUS and skip when absent;
   the hermetic round-trip always runs."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-dsl.result :as r]
            [vtranslate.engine.dev :as dev]
            [vtranslate.engine.port.media :as pm]))

;; GOLDEN — hermetic native round-trip: the real JavaCv backend probes a generated
;; WAV onto a stable ProbeInfo and extracts a non-empty PCM artifact.
(deftest-golden javacv-silent-wav-round-trip-golden
  "test/golden/ffmpeg-silent-wav-roundtrip.edn"
  (let [{:keys [probe extract wav-bytes]} (dev/smoke!)]
    {:probe         (select-keys (:ok probe) [:container :duration-ms :has-audio? :audio-codec])
     :extract-ok?   (r/ok? extract)
     :wav-nonempty? (pos? (long (or wav-bytes 0)))}))

;; GOLDEN — anti-corruption remap: each Collect/fs failure collapses onto its
;; closed domain TranslationError category at the port boundary.
(deftest-golden collect-port-error-remap-golden
  "test/golden/ffmpeg-port-error-remap.edn"
  (let [port (dev/throwing-port)]
    {:missing-source (:error (pm/probe port "/no/such.mp4"))
     :probe-throws   (:error (pm/probe port "/tmp"))
     :extract-throws (:error (pm/extract-audio port "/tmp" {}))}))

;; Corpus probe — real media when present; skips (not fails) without a corpus.
(deftest javacv-probes-corpus-speech
  (if-let [pt (dev/corpus-file "speech/pt.mp3")]
    (let [res (pm/probe (dev/ffmpeg-port) pt)]
      (is (r/ok? res))
      (is (pos? (:duration-ms (:ok res))))
      (is (:has-audio? (:ok res))))
    (testing "corpus absent — skipped (set $VT_CORPUS to enable)"
      (is true))))
