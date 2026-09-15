(ns vtranslate.engine.calc.cache-key-test
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.calc.cache-key :as ck]))

(def base {:content-sha "abc" :provider :openai-compatible :model "m" :language "en"
           :segmenter :silero :span-pad-ms 200 :asr-hygiene "loop-collapse-1" :transcriber-knobs "{}"
           :engine-version "0.1.14"})

(deftest equal-inputs-give-equal-keys
  (is (= (ck/transcript-key base) (ck/transcript-key (into {} base)))))

(deftest the-hygiene-version-is-part-of-the-identity
  (is (not= (ck/transcript-key base) (ck/transcript-key (assoc base :asr-hygiene "loop-collapse-2")))))

(deftest the-transcriber-knobs-are-part-of-the-identity
  (is (not= (ck/transcript-key base)
            (ck/transcript-key (assoc base :transcriber-knobs "{:temperature 0.2}")))))

(deftest absent-and-nil-contribute-the-same-empty-slot
  (is (= (ck/transcript-key {:content-sha "abc"}) (ck/transcript-key {:content-sha "abc" :model nil}))))

(deftest the-engine-version-is-part-of-the-identity
  (is (not= (ck/transcript-key base)
            (ck/transcript-key (assoc base :engine-version "0.1.15")))
      "a new engine release is a different transcription identity")
  (is (not= (ck/transcript-key (assoc base :engine-version "dev"))
            (ck/transcript-key base))
      "a source checkout does not share a bucket with a released engine"))

(deftest the-running-engine-reports-a-version
  (let [v @(requiring-resolve 'vtranslate.engine.version/engine-version)]
    (is (string? v))
    (is (seq v) "a blank version would collapse every release into one bucket")))
