(ns vtranslate.engine.api-subtitle-test
  "End-to-end over the REAL adapters for the no-ASR subtitle ingress
   (api/run-subtitle-job): filesystem source reader + registry codec (parse &
   render) + a translator. Exercises the happy paths (identity round-trip,
   VTT emission, a substituting translator) and the two fail-loud edges
   (unreadable source, empty source)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.adapters.source.file :as file]
            [vtranslate.engine.adapters.codec.dispatch :as dispatch]
            [vtranslate.engine.adapters.translator.identity :as identity]
            [vtranslate.engine.port.subtitle :as p.sub]
            [vtranslate.engine.port.translator :as p.tr])
  (:import [java.io File]))

(defn- ports
  "Real-adapter port map; :translator overridable for the substitution test."
  ([] (ports (identity/make-translator)))
  ([translator]
   {:source     (file/make-source-reader)
    :parser     (dispatch/make-codec)
    :translator translator
    :renderer   (dispatch/make-codec)}))

(def ^:private srt-2cues
  (str "1\n00:00:01,000 --> 00:00:04,000\nHello world\n\n"
       "2\n00:00:05,000 --> 00:00:08,000\nSecond cue\n"))

(defn- write-temp
  "Spit `content` to a temp file with `suffix`; return its absolute path."
  [content suffix]
  (let [f (doto (File/createTempFile "vt-e2e" suffix) (.deleteOnExit))]
    (spit f content)
    (.getPath f)))

;; --- (a) en->en identity round-trip: completed job, 2 cues, timing intact ----

(deftest identity-srt-roundtrip
  (let [path (write-temp srt-2cues ".srt")
        res  (api/run-subtitle-job (ports)
                                   {:job-id "j-a" :source path
                                    :source-language "en" :target-language "en"
                                    :format :format/srt})]
    (is (r/ok? res))
    (is (= :job/completed (get-in res [:ok :job :state :adt/variant])))
    (is (= 2 (count (get-in res [:ok :subtitle-track :cues]))))
    (let [rendered (get-in res [:ok :rendered])
          reparsed (:cues (:ok (p.sub/parse (dispatch/make-codec) rendered :format/srt)))]
      (is (= 2 (count reparsed)))
      (is (= [[1000 4000] [5000 8000]]
             (mapv (juxt :start-ms :end-ms) reparsed))
          "re-parsed rendered SRT preserves every cue's timing"))))

;; --- (a2) unregistered EXPLICIT source-language fails loud (registry symmetry) --

(deftest unregistered-source-language-fails-loud
  (let [path (write-temp srt-2cues ".srt")
        res  (api/run-subtitle-job (ports)
                                   {:job-id "j-a2" :source path
                                    :source-language "klingon" :target-language "en"
                                    :format :format/srt})]
    (is (r/err? res))
    (is (= :error/unsupported-language (:error res)))
    (is (= "klingon" (:language res)))))

;; --- (b) pt-BR + :format/vtt: rendered document is WebVTT --------------------

(deftest vtt-emission
  (let [path (write-temp srt-2cues ".srt")
        res  (api/run-subtitle-job (ports)
                                   {:job-id "j-b" :source path
                                    :source-language "en" :target-language "pt-BR"
                                    :format :format/vtt})]
    (is (r/ok? res))
    (is (str/starts-with? (get-in res [:ok :rendered]) "WEBVTT"))))

;; --- (c) substituting translator: rendered lines transformed, count intact ---

(deftest upper-casing-translator
  (let [path        (write-temp srt-2cues ".srt")
        upper-tr    (reify p.tr/ITranslator
                      (translate-batch [_ ts _ _ _]
                        (r/ok (mapv str/upper-case ts))))
        res         (api/run-subtitle-job (ports upper-tr)
                                          {:job-id "j-c" :source path
                                           :source-language "en" :target-language "en"
                                           :format :format/srt})
        rendered    (get-in res [:ok :rendered])
        reparsed    (:cues (:ok (p.sub/parse (dispatch/make-codec) rendered :format/srt)))]
    (is (r/ok? res))
    (is (str/includes? rendered "HELLO WORLD"))
    (is (str/includes? rendered "SECOND CUE"))
    (is (= 2 (count reparsed)) "cue count preserved through translation")
    (is (= [["HELLO WORLD"] ["SECOND CUE"]] (mapv :lines reparsed)))))

;; --- (d) unreadable source: fail loud onto :error/source-unreadable ----------

(deftest missing-source-fails-loud
  (let [res (api/run-subtitle-job (ports)
                                  {:job-id "j-d" :source "/no/such/file.srt"
                                   :source-language "en" :target-language "en"
                                   :format :format/srt})]
    (is (r/err? res))
    (is (= :error/source-unreadable (:error res)))))

;; --- (e) empty source (no cues): fail loud, never crash ----------------------

(deftest empty-source-fails-loud
  (let [path (write-temp "" ".srt")
        res  (api/run-subtitle-job (ports)
                                   {:job-id "j-e" :source path
                                    :source-language "en" :target-language "en"
                                    :format :format/srt})]
    (is (r/err? res))))
