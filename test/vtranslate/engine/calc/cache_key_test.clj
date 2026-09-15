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
  ;; The named knobs only cover what an operator can write in a config file.
  ;; They cannot see a better segmenter or a repaired transcriber adapter, so
  ;; without this a shipped improvement leaves every already-processed video on
  ;; its old transcript. A release must invalidate by construction.
  (is (not= (ck/transcript-key base)
            (ck/transcript-key (assoc base :engine-version "0.1.15")))
      "a new engine release is a different transcription identity")
  (is (not= (ck/transcript-key (assoc base :engine-version "dev"))
            (ck/transcript-key base))
      "a source checkout does not share a bucket with a released engine"))

(deftest the-running-engine-reports-a-version
  ;; A nil or blank version would silently collapse every release into one
  ;; cache bucket, which is the failure this field exists to prevent.
  (let [v @(requiring-resolve 'vtranslate.engine.version/engine-version)]
    (is (string? v))
    (is (seq v))
    (is (= v (str v)) "already a string, never a Properties lookup returning nil")))
