(ns vtranslate.engine.adapters.transcriber.sherpa-onnx-test
  "PURE projection coverage: result->raw-segments maps a sherpa native result onto
   raw segment maps — no native handle needed (the outer ns loads without ONNX)."
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.adapters.transcriber.sherpa-onnx :as sut]))

(deftest whisper-empty-timestamps-is-one-full-clip-segment
  ;; whisper models return no per-token timestamps => ONE segment [0, dur] w/ text
  (is (= [{:start 0.0 :end 2.5 :text "hello world"}]
         (sut/result->raw-segments {:text "hello world" :tokens ["hello" " world"] :timestamps []}
                                   2500))))

(deftest mismatched-timestamps-falls-back-to-full-clip
  (is (= [{:start 0.0 :end 1.0 :text "hi"}]
         (sut/result->raw-segments {:text "hi" :tokens ["a" "b"] :timestamps [0.0]} 1000))))

(deftest token-timestamps-regroup-into-ordered-words
  (let [segs (sut/result->raw-segments
              {:text "hi there" :tokens ["▁hi" "▁there"] :timestamps [0.0 1.0]} 2000)]
    (is (pos? (count segs)))
    (is (= 2.0 (:end (last segs))) "the last word ends at the clip duration")
    (is (apply <= (mapcat (juxt :start :end) segs)) "ordered + non-overlapping")))
