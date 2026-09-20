(ns vtranslate.engine.calc.translation-test
  "Promote (CPPB) zip — golden + property + mutation for build-translated-cues.
   Projects the TranslatedCues aggregate to plain EDN before snapshotting. No IO."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.transcription :as tx]
            [vtranslate.engine.domain.translation :as tr]
            [vtranslate.engine.calc.translation :as sut]))

;; --- builders + projection -------------------------------------------------

(defn- transcript-with
  "Fold seg-input maps into a completed Transcript (language en)."
  [seg-inputs]
  (let [t0     (:ok (tx/make-transcript {:id "tc" :asset-id "asset" :language "en"}))
        filled (reduce (fn [t s] (tx/add-segment t (:ok (tx/make-segment s)))) t0 seg-inputs)]
    (:ok (tx/complete filled))))

(defn- project
  "Record-free projection of a build-translated-cues result."
  [res]
  {:ok?              (r/ok? res)
   :unit-count       (when (r/ok? res) (count (get-in res [:ok :units])))
   :target-texts     (mapv :target-text (get-in res [:ok :units]))
   :source-languages (mapv :source-language (get-in res [:ok :units]))
   :source-language  (get-in res [:ok :source-language])
   :target-language  (get-in res [:ok :target-language])
   :status           (get-in res [:ok :status :adt/variant])})

(def ^:private sample-segs
  [{:index 1 :start-ms 0    :end-ms 1000 :text "Hello" :confidence 0.9 :language "en"}
   {:index 2 :start-ms 1000 :end-ms 2000 :text "World" :confidence 0.8 :language "pt"}
   {:index 3 :start-ms 2000 :end-ms 3000 :text "Bye"   :confidence 0.7 :language "fr"}])

(def ^:private sample-tls ["Hola" "Mundo" "Adios"])

;; =============================================================================
;; GOLDEN — aligned 1:1 zip vs. count-mismatch failure
;; =============================================================================

(deftest-golden build-translated-cues-golden
  "test/golden/calc-translation-build.edn"
  {:aligned  (project (sut/build-translated-cues
                       (transcript-with sample-segs) sample-tls
                       {:id "tcues" :target-language "es"}))
   :mismatch (project (sut/build-translated-cues
                       (transcript-with sample-segs) ["only-one"]
                       {:id "tcues" :target-language "es"}))})

;; =============================================================================
;; PROPERTY — equal counts align 1:1 in order (target-text = translation,
;; source-language = segment language), status seals to :translation/complete
;; =============================================================================

(def ^:private gen-lang (gen/elements ["en" "pt" "fr" "de" "ja" "ru"]))

(def ^:private gen-seg-input
  (gen/fmap (fn [[s d text lang]]
              {:index 1 :start-ms s :end-ms (+ s d)
               :text text :confidence 0.9 :language lang})
            (gen/tuple (gen/choose 0 100000) (gen/choose 1 5000)
                       (gen/not-empty gen/string-alphanumeric) gen-lang)))

(defspec aligned-units-match-translations 200
  (prop/for-all [[segs tls]
                 (gen/bind (gen/vector gen-seg-input 1 6)
                           (fn [segs]
                             (gen/fmap (fn [tls] [segs tls])
                                       (gen/vector (gen/not-empty gen/string-alphanumeric) (count segs)))))]
    (let [res (sut/build-translated-cues (transcript-with segs) tls
                                         {:id "c" :target-language "es"})]
      (and (r/ok? res)
           (= (count segs) (count (get-in res [:ok :units])))
           (= (vec tls) (mapv :target-text (get-in res [:ok :units])))
           (= (mapv :language segs) (mapv :source-language (get-in res [:ok :units])))
           (= "en" (get-in res [:ok :source-language]))
           (= "es" (get-in res [:ok :target-language]))
           (= :translation/complete (get-in res [:ok :status :adt/variant]))))))

(defspec count-mismatch-fails-else-ok 200
  (prop/for-all [segs (gen/vector gen-seg-input 1 6)
                 tls  (gen/vector (gen/not-empty gen/string-alphanumeric) 0 6)]
    (let [res (sut/build-translated-cues (transcript-with segs) tls
                                         {:id "c" :target-language "es"})]
      (if (= (count segs) (count tls))
        (r/ok? res)
        (and (r/err? res) (= :error/translation-failed (:error res)))))))

;; =============================================================================
;; target-outcomes — every target lands in exactly one bucket, in order
;; =============================================================================

(def ^:private gen-target-result
  (gen/one-of [(gen/fmap (fn [t] (r/ok {:units t})) gen/string-alphanumeric)
               (gen/fmap (fn [why] (r/err :error/translation-failed {:reason why}))
                         gen/string-alphanumeric)
               (gen/return ::unfinished)]))

(def ^:private gen-outcomes
  "Distinct target languages, each paired with one result."
  (gen/bind (gen/vector-distinct gen-lang {:max-elements 6})
            (fn [langs]
              (gen/fmap #(mapv vector langs %)
                        (gen/vector gen-target-result (count langs))))))

(defspec every-target-is-delivered-or-failed-once 200
  (prop/for-all [outcomes gen-outcomes]
    (let [targets (mapv first outcomes)
          results (mapv second outcomes)
          {:keys [delivered failed]} (sut/target-outcomes targets results ::unfinished)
          langs-of (fn [xs] (mapv :target-language xs))]
      (and (= (count targets) (+ (count delivered) (count failed)))
           (= (filterv (set (langs-of delivered)) targets) (langs-of delivered))
           (= (filterv (set (langs-of failed)) targets) (langs-of failed))
           (every? (comp some? :translated) delivered)
           (every? (comp keyword? :error) failed)
           (= (mapv (fn [[_ res]] (r/ok? res))
                    (filter (fn [[_ res]] (not= ::unfinished res)) outcomes))
              (mapv (fn [[lang _]] (contains? (set (langs-of delivered)) lang))
                    (filter (fn [[_ res]] (not= ::unfinished res)) outcomes)))))))

(deftest a-target-the-pool-gave-up-on-is-a-failure-that-names-it
  (let [{:keys [delivered failed]}
        (sut/target-outcomes ["pt" "fr" "de"]
                             [(r/ok :pt-cues) ::gone (r/err :error/translation-failed
                                                            {:reason "count"})]
                             ::gone)]
    (is (= [{:target-language "pt" :translated :pt-cues}] delivered))
    (is (= ["fr" "de"] (mapv :target-language failed)))
    (is (= :error/translation-failed (:error (first failed))))
    (is (= "count" (:reason (second failed)))
        "a target's own error travels with it")))

;; =============================================================================
;; UNIT — failure modes fail loud
;; =============================================================================

(deftest a-segment's-annotations-ride-along-on-its-unit
  (let [res (sut/build-translated-cues
             (transcript-with [{:index 1 :start-ms 0 :end-ms 1000 :text "Hello" :confidence 0.9}
                               {:index 2 :start-ms 1000 :end-ms 2000 :text "Subtitles by X" :confidence 0.9
                                :segment/omit? true :asr/hallucination :credit}])
             ["Hola" "Subtitles by X"]
             {:id "tc" :target-language "es"})
        [spoken credit] (get-in res [:ok :units])]
    (is (nil? (:segment/omit? spoken)))
    (is (true? (:segment/omit? credit)))
    (is (= :credit (:asr/hallucination credit)))
    (is (= "Subtitles by X" (:target-text credit)) "the text is kept")))

(deftest count-mismatch-is-translation-failed
  (let [res (sut/build-translated-cues (transcript-with sample-segs) ["a" "b"]
                                       {:id "c" :target-language "es"})]
    (is (r/err? res))
    (is (= :error/translation-failed (:error res)))))

(deftest identical-src-tgt-language-fails
  (let [res (sut/build-translated-cues (transcript-with sample-segs) sample-tls
                                       {:id "c" :target-language "en"})]
    (is (r/err? res))
    (is (= :error/translation-failed (:error res)))))

;; =============================================================================
;; MUTATION — mis-zip, swap source/target, ignore count mismatch
;; =============================================================================

(defn- mk
  "Reimplementation seam used to inject the mutant bugs."
  [transcript translations {:keys [id target-language]} xform-tls swap? force?]
  (let [segments (:segments transcript)]
    (if (and (not force?) (not= (count segments) (count translations)))
      (r/err :error/translation-failed {:segment-id (str id)})
      (r/let-ok [c0     (tr/make-translated-cues
                         {:id id :transcript-id (:id transcript)
                          :source-language (:language transcript)
                          :target-language target-language})
                 filled (reduce
                         (fn [acc [seg tt]]
                           (r/let-ok [c acc
                                      u (tr/make-translation-unit
                                         {:start-ms (get-in seg [:range :start :ms])
                                          :end-ms   (get-in seg [:range :end :ms])
                                          :source-language (:language seg)
                                          :source-text (if swap? tt (:text seg))
                                          :target-text (if swap? (:text seg) tt)})]
                             (r/ok (tr/add-unit c u))))
                         (r/ok (tr/begin c0))
                         (map vector segments (xform-tls translations)))]
        (tr/complete filled)))))

(deftest-mutations build-translated-cues-mutations-caught
  vtranslate.engine.calc.translation/build-translated-cues
  [["mis-zip-reverse"      (fn [t tl o] (mk t tl o reverse   false false))]
   ["swap-source-target"   (fn [t tl o] (mk t tl o identity  true  false))]
   ["ignore-count-mismatch" (fn [t tl o] (mk t tl o identity false true))]]
  (fn []
    (let [ok  (sut/build-translated-cues (transcript-with sample-segs) sample-tls
                                         {:id "c" :target-language "es"})
          bad (sut/build-translated-cues (transcript-with sample-segs) ["a" "b"]
                                         {:id "c" :target-language "es"})]
      (is (r/ok? ok))
      (is (= 3 (count (get-in ok [:ok :units]))))
      (is (= sample-tls (mapv :target-text (get-in ok [:ok :units]))))
      (is (= ["Hello" "World" "Bye"] (mapv :source-text (get-in ok [:ok :units]))))
      (is (= ["en" "pt" "fr"] (mapv :source-language (get-in ok [:ok :units]))))
      (is (r/err? bad))
      (is (= :error/translation-failed (:error bad))))))
