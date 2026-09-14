(ns vtranslate.engine.domain.transcription-silent-test
  "The silent terminal state: media that carries no speech is a transcript that
   SEALED with nothing in it, which is neither :transcript/complete (sealed WITH
   segments) nor :transcript/failed (ASR broke). Measured case: Big Buck Bunny,
   ten minutes of music and effects, which used to end the job with
   :error/asr-failed \"no segments produced\" after paying for the whole decode."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.transcription :as tx]))

(defn- empty-transcript []
  (:ok (tx/make-transcript {:id "t" :asset-id "a" :language "en"})))

(defn- one-segment []
  (:ok (tx/make-segment {:index 1 :start-ms 0 :end-ms 100 :text "hi" :confidence 1.0})))

(deftest an-empty-transcript-seals-silent
  (let [res (tx/seal-silent (empty-transcript))]
    (is (r/ok? res))
    (is (tx/silent? (:ok res)))
    (is (empty? (:segments (:ok res))))))

(deftest silence-is-not-completion-and-completion-is-not-silence
  (testing "a transcript with speech completes and is not silent"
    (let [t (tx/add-segment (empty-transcript) (one-segment))
          res (tx/complete t)]
      (is (r/ok? res))
      (is (not (tx/silent? (:ok res))))
      (is (= :transcript/complete (:adt/variant (:status (:ok res)))))))
  (testing "a transcript with speech refuses to be sealed silent"
    (let [t (tx/add-segment (empty-transcript) (one-segment))]
      (is (r/err? (tx/seal-silent t)))))
  (testing "an empty transcript still refuses to complete"
    (is (r/err? (tx/complete (empty-transcript))))))

(deftest a-sealed-transcript-cannot-be-resealed
  (let [silent (:ok (tx/seal-silent (empty-transcript)))]
    (is (r/err? (tx/seal-silent silent)) "silence is terminal")
    (is (r/err? (tx/complete silent)) "and cannot be upgraded to complete")))
