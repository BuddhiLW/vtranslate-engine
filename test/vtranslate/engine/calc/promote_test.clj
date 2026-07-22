(ns vtranslate.engine.calc.promote-test
  "Property + mutation coverage for the shared cue-fill fold (calc.promote)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.calc.promote :as sut]))

;; --- a minimal item->cue (item = {:text s}); 1s slots keyed by index ---------

(defn- item->cue [index {:keys [text]}]
  (rd/make-cue {:index index
                :start-ms (* 1000 (dec index))
                :end-ms   (* 1000 index)
                :lines [text]}))

(defn- fresh-track []
  (:ok (rd/make-subtitle-track {:id "s" :source-id "src"
                                :language "es" :format :format/srt})))

;; --- shared assertion body (reused by the mutation harness) ------------------

(defn- mut-check []
  (let [res (sut/fill-cues (fresh-track) item->cue
                           [{:text "a"} {:text "b"} {:text "c"}])]
    (is (r/ok? res))
    (is (= [1 2 3] (mapv :index (get-in res [:ok :cues]))))
    (is (= [["a"] ["b"] ["c"]] (mapv :lines (get-in res [:ok :cues]))))))

;; =============================================================================
;; UNIT
;; =============================================================================

(deftest renumbers-1-based-in-input-order
  (mut-check))

(deftest short-circuits-on-first-error
  ;; a blank line makes make-cue fail -> the whole fold errs
  (let [res (sut/fill-cues (fresh-track) item->cue [{:text "a"} {:text ""}])]
    (is (r/err? res))
    (is (= :error/render-failed (:error res)))))

;; =============================================================================
;; PROPERTY — one cue per item, indices 1..n contiguous in input order
;; =============================================================================

(defspec fill-cues-count-and-order 200
  (prop/for-all [texts (gen/vector gen/string-alphanumeric 1 10)]
    (let [items (map (fn [t] {:text (if (str/blank? t) "x" t)}) texts)
          res   (sut/fill-cues (fresh-track) item->cue items)]
      (and (r/ok? res)
           (= (range 1 (inc (count items)))
              (map :index (get-in res [:ok :cues])))
           (= (count items) (count (get-in res [:ok :cues])))))))

;; =============================================================================
;; MUTATION — break the fold rules, prove the assertions catch each
;; =============================================================================

(deftest-mutations fill-cues-mutations-caught
  vtranslate.engine.calc.promote/fill-cues
  [["empty-fill"  (fn [track _i2c _items] (r/ok track))]
   ["ignore-item" (fn [track _i2c items]
                    (reduce (fn [tr _pair] tr) (r/ok track)
                            (map-indexed (fn [i x] [(inc i) x]) items)))]
   ["zero-index"  (fn [track item->cue items]
                    (->> items
                         (map-indexed (fn [i item] [i item]))
                         (reduce (fn [tr [idx item]]
                                   (r/let-ok [t tr
                                              cue (item->cue idx item)]
                                     (r/ok (rd/add-cue t cue))))
                                 (r/ok track))))]]
  mut-check)
