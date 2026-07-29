(ns vtranslate.engine.adapters.transcriber.nemo-canary-int-test
  "Scores Canary's AST (speech TRANSLATION) against real NeMo weights.

   Canary is the only ITranscriber in the family that also translates, so it is
   the only one a :target-language reaches — and the multilingual branch that
   passes source_lang/target_lang is code no stub can exercise: NeMo raises at
   call time on a model that does not take those kwargs, and the AED multitask
   models reject a JVM collection where a str path is expected.

   Drives the ASR-only ingress over corpus/sintel/clips/sintel_105-140s.mp4
   twice — once transcribing, once translating to German — and scores both
   against the HUMAN sintel.de.srt cues covering that window. The load-bearing
   assertion needs no magic constant: the German output must be closer to the
   German reference than Canary's own English transcription of the same audio.

   :silero-vad, not :grid. Canary is an utterance-level AED model and returns an
   EMPTY hypothesis for a fixed window that cuts mid-utterance; VAD spans cut on
   silence, which is what it expects.

   OPT-IN. Needs a Python with nemo_toolkit[asr], the weights (~4GB, fetched on
   first run) and the VAD model:

     VT_NEMO_PYTHON=<python> clojure -M:dev:test:ffmpeg:nemo:silero-vad \\
       --config-file tests-int.edn"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vtranslate.engine.adapters.codec.srt :as srt]
            [vtranslate.engine.adapters.transcriber.nemo-python :as nemo]
            [vtranslate.engine.main :as main]
            [vtranslate.engine.mt-reference-int-test :refer [chrf]]
            [vtranslate.engine.port.subtitle :as p.sub])
  (:import [java.io File]))

;; --- opt-in gate ------------------------------------------------------------

(def python (System/getenv "VT_NEMO_PYTHON"))

(def model (or (System/getenv "VT_NEMO_MODEL") "nvidia/canary-1b-flash"))

(defn- corpus [relative]
  (str (or (System/getenv "VTRANSLATE_CORPUS_DIR")
           (str (System/getProperty "user.dir") "/../corpus"))
       "/" relative))

(def vad-model "models/silero_vad.onnx")

(def enabled?
  (boolean (and (nemo/backend-present?)
                python
                (.canExecute (File. ^String python))
                (.exists (File. vad-model)))))

;; --- the clip's slice of the reference subtitles -----------------------------

(def clip (corpus "sintel/clips/sintel_105-140s.mp4"))

;; The clip is cut from the film at these marks, so a reference cue belongs to
;; it when its own start falls inside them.
(def clip-start-ms 105000)
(def clip-end-ms 140000)

(defn- r-ok? [result] (contains? result :ok))

(defn- reference-text
  "The human subtitle text covering the clip, joined, for `lang`."
  [lang]
  (let [result (p.sub/parse (srt/make-codec)
                            (slurp (corpus (str "sintel/subs/sintel." lang ".srt")))
                            :format/srt)]
    (is (r-ok? result) (str "could not parse the " lang " reference"))
    (->> (:cues (:ok result))
         (filter #(<= clip-start-ms (:start-ms %) clip-end-ms))
         (mapcat #(or (:lines %) [(:text %)]))
         (str/join " "))))

;; --- the run ----------------------------------------------------------------

(defn- canary-run
  "Transcribe the clip with Canary. A non-nil `target` selects AST.
   => joined segment text."
  [target]
  (let [spec {:job-id          (str "canary-int-" (or target "asr"))
              :operation       :transcribe
              :source          clip
              :source-language "en"
              :config {:transcriber :canary
                       :segmenter   :silero-vad
                       :transcriber-opts (cond-> {:python-executable python
                                                  :model-name        model
                                                  ;; a shared GPU is routinely
                                                  ;; too full to load 1b onto
                                                  :device            "cpu"}
                                           target (assoc :target-language target))}}
        result (main/run [(pr-str spec)])]
    (is (r-ok? result) (str "canary run failed: " (pr-str (dissoc result :ok))))
    (->> (get-in result [:ok :transcript :segments])
         (map :text)
         (str/join " "))))

(deftest ^:nemo canary-translates-speech-rather-than-transcribing-it
  (if-not enabled?
    (do (println "[canary] skipped — set VT_NEMO_PYTHON and add :nemo:silero-vad")
        (is (not enabled?) "opt-in: no NeMo weights were loaded"))
    (let [english-reference (reference-text "en")
          german-reference  (reference-text "de")
          transcribed       (canary-run nil)
          translated        (canary-run "de")
          scores {:transcribed-vs-en (chrf transcribed english-reference)
                  :transcribed-vs-de (chrf transcribed german-reference)
                  :translated-vs-de  (chrf translated german-reference)
                  :translated-vs-en  (chrf translated english-reference)}]

      (println (format "[canary] model=%s" model))
      (doseq [[k v] (sort-by key scores)]
        (println (format "[canary] %-20s chrF=%.3f" (name k) v)))

      (testing "both passes produced speech"
        (is (seq transcribed))
        (is (seq translated))
        (is (not= transcribed translated)
            "a target language changed nothing — the AST kwargs never reached NeMo"))

      (testing "the translation is closer to the German reference than Canary's
                own English transcription of the same audio"
        ;; The load-bearing assertion: no magic constant, and it fails loudly if
        ;; the model silently transcribes instead of translating.
        (is (> (:translated-vs-de scores) (:transcribed-vs-de scores))))

      (testing "and the translation is German, not English"
        (is (> (:translated-vs-de scores) (:translated-vs-en scores))))

      (testing "the transcription pass is still English"
        (is (> (:transcribed-vs-en scores) (:transcribed-vs-de scores)))))))
