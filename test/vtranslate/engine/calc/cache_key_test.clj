(ns vtranslate.engine.calc.cache-key-test
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.calc.cache-key :as ck]))

(def base {:content-sha "abc" :provider :openai-compatible :model "m" :language "en"
           :segmenter :silero :span-pad-ms 200 :asr-hygiene "loop-collapse-1" :transcriber-knobs "{}"})

(deftest equal-inputs-give-equal-keys
  (is (= (ck/transcript-key base) (ck/transcript-key (into {} base)))))

(deftest the-hygiene-version-is-part-of-the-identity
  (is (not= (ck/transcript-key base) (ck/transcript-key (assoc base :asr-hygiene "loop-collapse-2")))))

(deftest the-transcriber-knobs-are-part-of-the-identity
  (is (not= (ck/transcript-key base)
            (ck/transcript-key (assoc base :transcriber-knobs "{:temperature 0.2}")))))

(deftest absent-and-nil-contribute-the-same-empty-slot
  (is (= (ck/transcript-key {:content-sha "abc"}) (ck/transcript-key {:content-sha "abc" :model nil}))))
