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
            [vtranslate.engine.port.media :as pm]
            [vtranslate.engine.adapters.transcriber.support :as sup]))

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

;; REGRESSION (prod 2026-09-13) — a STEREO source at a non-target rate must not
;; come out STRETCHED. FFmpegFrameRecorder reads an incoming sample buffer using
;; ITS OWN channel count, so handing it the grabber's native 48 kHz stereo frames
;; made every stereo PAIR land as two consecutive MONO samples: audio at half
;; speed, pitch halved, covering only the first half of the source. Nothing
;; upstream could see it — levels, WAV header, sample rate and channel count were
;; all still correct — until whisper's VAD scored the whole track as non-speech
;; and the job died as :job/asr-failed "no segments produced" on a video that is
;; perfectly audible. DURATION is therefore the assertion: it is the one property
;; the defect actually changed.
(deftest stereo-source-extracts-without-stretching
  (testing "48 kHz stereo -> 16 kHz mono preserves PITCH, not merely length"
    (let [hz   440
          src  (dev/stereo-tone-wav 2 hz)
          res  (pm/extract-audio (dev/ffmpeg-port) src {})]
      (is (r/ok? res))
      (let [{:keys [samples sample-rate]} (:ok (sup/read-wav-mono-floats (:ok res)))
            n         (alength ^floats samples)
            crossings (count (for [i (range 1 n)
                                   :when (not= (neg? (aget ^floats samples (dec i)))
                                               (neg? (aget ^floats samples i)))]
                               i))
            ;; a sine crosses zero twice per cycle
            measured  (/ (* crossings sample-rate) (* 2.0 n))]
        (is (pos? n) "extracted WAV should carry samples")
        (is (< (Math/abs (- measured (double hz))) 40.0)
            (str "expected ~" hz " Hz, measured " (long measured)
                 " Hz; ~" (quot hz 2) " Hz means every stereo PAIR was consumed as "
                 "two consecutive MONO samples, halving the pitch. Note that the "
                 "clip's DURATION cannot witness this: the defect emits samples "
                 "spanning the full source length while consuming only half of it, "
                 "so length looks right and only the content is wrong."))))))

;; Corpus probe — real media when present; skips (not fails) without a corpus.
;; Corpus probe — defined ONLY when the corpus is actually present. The bare
;; (is true) it replaces made an absent corpus indistinguishable from a passing
;; probe. Set $VT_CORPUS to enable.
(when-let [pt (dev/corpus-file "speech/pt.mp3")]
  (deftest javacv-probes-corpus-speech
    (let [res (pm/probe (dev/ffmpeg-port) pt)]
      (is (r/ok? res))
      (is (pos? (:duration-ms (:ok res))))
      (is (:has-audio? (:ok res))))))
