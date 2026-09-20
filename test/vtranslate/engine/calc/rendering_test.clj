(ns vtranslate.engine.calc.rendering-test
  "Trifecta — golden + property + mutation — for the TranslatedCues -> SubtitleTrack promoter."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.domain.translation :as t]
            [vtranslate.engine.calc.rendering :as sut]))

;; --- input builders (real smart-ctors) -------------------------------------

(defn- ->unit [{:keys [start-ms end-ms target-text]}]
  (:ok (t/make-translation-unit {:start-ms start-ms :end-ms end-ms
                                 :source-language "en" :source-text "src"
                                 :target-text target-text})))

(defn- ->tcues
  "Build a completed TranslatedCues aggregate (id \"tc-1\") for `target-lang`."
  [target-lang units]
  (let [tc  (:ok (t/make-translated-cues {:id "tc-1" :transcript-id "tr-1"
                                          :source-language "en"
                                          :target-language target-lang}))
        tc  (reduce t/add-unit (t/begin tc) (map ->unit units))]
    (:ok (t/complete tc))))

(defn- ->empty-tcues [target-lang]
  (:ok (t/make-translated-cues {:id "tc-1" :transcript-id "tr-1"
                                :source-language "en"
                                :target-language target-lang})))

;; --- projection (record-free EDN for snapshotting) -------------------------

(defn- project [res]
  {:ok?       (r/ok? res)
   :format    (when (r/ok? res) (rd/extension (:ok res)))
   :indices   (mapv :index (get-in res [:ok :cues]))
   :lines     (mapv :lines (get-in res [:ok :cues]))
   :language  (get-in res [:ok :language])
   :source-id (get-in res [:ok :source-id])
   :status    (get-in res [:ok :status :adt/variant])})

;; =============================================================================
;; GOLDEN — three units promote to a 1..n rendered track; lang + source-id flow
;; =============================================================================

(deftest-golden rendering-golden
  "test/golden/rendering-shape.edn"
  (project
   (sut/build-subtitle-track
    (->tcues "es" [{:start-ms 0    :end-ms 1000 :target-text "Hola"}
                   {:start-ms 1000 :end-ms 2000 :target-text "mundo"}
                   {:start-ms 2000 :end-ms 3000 :target-text "adios"}])
    {:id "sub-1" :format :format/srt})))

;; =============================================================================
;; PROPERTY — one cue per unit, indices 1..n in order, lines/lang/source-id flow
;; =============================================================================

(def ^:private gen-unit
  (gen/fmap (fn [[s d w]] {:start-ms s :end-ms (+ s d) :target-text (str "t" w)})
            (gen/tuple (gen/choose 0 100000) (gen/choose 0 5000) gen/string-alphanumeric)))

(defspec build-is-contiguous-and-preserves 200
  (prop/for-all [units (gen/vector gen-unit 1 8)]
    (let [res (sut/build-subtitle-track (->tcues "es" units) {:id "s" :format :format/vtt})]
      (and (r/ok? res)
           (= (range 1 (inc (count units)))
              (map :index (get-in res [:ok :cues])))
           (= (mapv (comp vector :target-text) units)
              (mapv :lines (get-in res [:ok :cues])))
           (= "es"   (get-in res [:ok :language]))
           (= "tc-1" (get-in res [:ok :source-id]))
           (= "vtt"  (rd/extension (:ok res)))))))

(defspec build-cue-count-equals-unit-count 100
  (prop/for-all [units (gen/vector gen-unit 1 12)]
    (let [res (sut/build-subtitle-track (->tcues "fr" units) {:id "s" :format :format/srt})]
      (and (r/ok? res)
           (= (count units) (count (get-in res [:ok :cues])))
           (= :track/rendered (get-in res [:ok :status :adt/variant]))))))

;; =============================================================================
;; UNIT — failure modes fail loud
;; =============================================================================

(deftest empty-units-fails-render
  (let [res (sut/build-subtitle-track (->empty-tcues "es") {:id "s" :format :format/srt})]
    (is (r/err? res))
    (is (= :error/render-failed (:error res)))))

(deftest unsupported-format-fails-loud
  (let [res (sut/build-subtitle-track
             (->tcues "es" [{:start-ms 0 :end-ms 1 :target-text "x"}])
             {:id "s" :format :format/foo})]
    (is (= :error/unsupported-format (:error res)))))

(deftest blank-target-text-fails-render
  ;; make-translation-unit now forbids a blank target-text upstream, so a bad
  ;; unit is unconstructable via the smart ctor. build-subtitle-track's make-cue
  ;; defense must still fail loud if a blank-target unit is injected directly.
  (let [rng (:ok (shared/make-time-range 0 1000))
        tc  (-> (:ok (t/make-translated-cues {:id "tc-1" :transcript-id "tr-1"
                                              :source-language "en" :target-language "es"}))
                t/begin
                (t/add-unit (t/->TranslationUnit rng "en" "src" "")))
        res (sut/build-subtitle-track (:ok (t/complete tc)) {:id "s" :format :format/srt})]
    (is (r/err? res))
    (is (= :error/render-failed (:error res)))))

;; =============================================================================
;; OMIT — a unit marked :segment/omit? stays in the aggregate, not in the track
;; =============================================================================

(defn- omitting
  "A completed TranslatedCues whose units at the `omitted` positions carry
   :segment/omit?, the way an addon's mark arrives from the source segment."
  [units omitted]
  (update (->tcues "es" units) :units
          (fn [us] (vec (map-indexed (fn [i u] (cond-> u (omitted i) (assoc :segment/omit? true)))
                                     us)))))

(deftest an-omitted-unit-is-left-out-and-the-rest-renumber
  (let [tc  (omitting [{:start-ms 0    :end-ms 1000 :target-text "a"}
                       {:start-ms 1000 :end-ms 2000 :target-text "Subtitles by DimaTorzok"}
                       {:start-ms 2000 :end-ms 3000 :target-text "c"}]
                      #{1})
        res (sut/build-subtitle-track tc {:id "s" :format :format/srt})]
    (is (= [1 2] (mapv :index (get-in res [:ok :cues]))))
    (is (= [["a"] ["c"]] (mapv :lines (get-in res [:ok :cues]))))
    (is (= 3 (t/unit-count tc)) "nothing is deleted from the aggregate")))

(deftest a-track-of-only-omitted-units-fails-loud
  (let [res (sut/build-subtitle-track (omitting [{:start-ms 0 :end-ms 1 :target-text "x"}] #{0})
                                      {:id "s" :format :format/srt})]
    (is (= :error/render-failed (:error res)))))

(defspec omitted-units-never-reach-the-track 100
  (prop/for-all [units (gen/vector gen-unit 2 8)
                 seed  gen/nat]
    (let [omitted (set (filter #(odd? (+ seed %)) (range (count units))))
          kept    (keep-indexed (fn [i u] (when-not (omitted i) u)) units)
          res     (sut/build-subtitle-track (omitting units omitted) {:id "s" :format :format/srt})]
      (and (= (mapv (comp vector :target-text) kept) (mapv :lines (get-in res [:ok :cues])))
           (= (range 1 (inc (count kept))) (map :index (get-in res [:ok :cues])))))))

;; =============================================================================
;; MUTATION — break the promotion rules, prove the assertions catch each
;; =============================================================================

(defn- mut-check []
  (let [res (sut/build-subtitle-track
             (->tcues "es" [{:start-ms 0    :end-ms 1000 :target-text "a"}
                            {:start-ms 1000 :end-ms 2000 :target-text "b"}
                            {:start-ms 2000 :end-ms 3000 :target-text "c"}])
             {:id "s" :format :format/srt})]
    (is (r/ok? res))
    (is (= [1 2 3] (mapv :index (get-in res [:ok :cues]))))
    (is (= [["a"] ["b"] ["c"]] (mapv :lines (get-in res [:ok :cues]))))
    (is (= "es"   (get-in res [:ok :language])))
    (is (= "tc-1" (get-in res [:ok :source-id])))))

(deftest-mutations unit->cue-mutations-caught
  vtranslate.engine.calc.rendering/unit->cue
  [["index-plus-one" (fn [index unit]
                       (rd/make-cue {:index (inc index)
                                     :start-ms (get-in unit [:range :start :ms])
                                     :end-ms   (get-in unit [:range :end :ms])
                                     :lines [(:target-text unit)]}))]
   ["const-index"    (fn [_index unit]
                       (rd/make-cue {:index 1
                                     :start-ms (get-in unit [:range :start :ms])
                                     :end-ms   (get-in unit [:range :end :ms])
                                     :lines [(:target-text unit)]}))]
   ["wrong-lines"    (fn [index unit]
                       (rd/make-cue {:index index
                                     :start-ms (get-in unit [:range :start :ms])
                                     :end-ms   (get-in unit [:range :end :ms])
                                     :lines ["X"]}))]]
  mut-check)

;; NOTE: the add-unit / fill-cues folds moved to calc.promote — their mutation
;; coverage lives in calc.promote-test now.
