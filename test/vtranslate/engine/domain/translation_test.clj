(ns vtranslate.engine.domain.translation-test
  "Trifecta — golden + property + mutation — for the translation aggregate.
   Projects records/Results to plain EDN (fields + :adt/variant) before snapshot."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.domain.translation :as sut]))

;; --- projection (record/Result -> plain EDN) -------------------------------

(defn- variant [cues] (get-in cues [:status :adt/variant]))

(defn- project-unit [u]
  {:start-ms        (get-in u [:range :start :ms])
   :end-ms          (get-in u [:range :end :ms])
   :source-language (:source-language u)
   :source-text     (:source-text u)
   :target-text     (:target-text u)})

(defn- project-cues [cues]
  {:id              (:id cues)
   :transcript-id   (:transcript-id cues)
   :source-language (:source-language cues)
   :target-language (:target-language cues)
   :unit-count      (count (:units cues))
   :units           (mapv project-unit (:units cues))
   :status          (variant cues)})

;; --- builders --------------------------------------------------------------

(defn- mk-unit [start end st tt]
  (:ok (sut/make-translation-unit
        {:start-ms start :end-ms end :source-language "en"
         :source-text st :target-text tt})))

(defn- mk-cues []
  (:ok (sut/make-translated-cues
        {:id "tc-1" :transcript-id "trs-1"
         :source-language "en" :target-language "pt"})))

;; =============================================================================
;; GOLDEN — a built, sealed TranslatedCues (pending -> translating -> complete)
;; =============================================================================

(deftest-golden translated-cues-golden
  "test/golden/translation-cues-shape.edn"
  (let [c (-> (mk-cues)
              sut/begin
              (sut/add-unit (mk-unit 0 1000 "Hello" "Ola"))
              (sut/add-unit (mk-unit 1000 2500 "World" "Mundo")))]
    (project-cues (:ok (sut/complete c)))))

(deftest-golden translation-unit-golden
  "test/golden/translation-unit-shape.edn"
  (project-unit (mk-unit 250 3750 "the cat" "o gato")))

;; =============================================================================
;; PROPERTY
;; =============================================================================

(def ^:private some-invalid ["xx" "zz" "klingon" "" "EN" "en-US" "pt-br"])

(def ^:private gen-lang
  (gen/elements (into (vec shared/supported-languages) some-invalid)))

;; make-translated-cues errs IFF either language is invalid OR source = target.
(defspec make-translated-cues-err-iff-invalid-or-identical 300
  (prop/for-all [s  gen-lang
                 t  gen-lang
                 id gen/string-alphanumeric]
    (let [res       (sut/make-translated-cues
                     {:id id :transcript-id "trs" :source-language s :target-language t})
          valid?    (fn [x] (contains? shared/supported-languages x))
          both-ok   (and (valid? s) (valid? t))
          should-ok (and both-ok (not= s t))]
      (and (= should-ok (r/ok? res))
           (= should-ok (not (r/err? res)))
           (if should-ok
             (= :translation/pending (variant (:ok res)))
             ;; identical-but-valid => translation-failed; else lang-registry err
             (= (if both-ok :error/translation-failed :error/unsupported-language)
                (:error res)))))))

;; unit-count = number of add-unit calls.
(defspec unit-count-equals-adds 200
  (prop/for-all [n (gen/choose 0 25)]
    (let [filled (reduce (fn [c i] (sut/add-unit c {:i i})) (mk-cues) (range n))]
      (and (= n (sut/unit-count filled))
           (= n (count (:units filled)))))))

;; make-translation-unit is total on ordered ranges and preserves text verbatim.
(defspec translation-unit-preserves-text 200
  (prop/for-all [a  (gen/choose 0 100000)
                 d  (gen/choose 0 100000)
                 st gen/string-alphanumeric
                 tt gen/string-alphanumeric]
    (let [res (sut/make-translation-unit
               {:start-ms a :end-ms (+ a d) :source-language "en"
                :source-text st :target-text tt})]
      (and (r/ok? res)
           (= st (:source-text (:ok res)))
           (= tt (:target-text (:ok res)))
           (= a (get-in res [:ok :range :start :ms]))
           (= (+ a d) (get-in res [:ok :range :end :ms]))))))

;; =============================================================================
;; UNIT — lifecycle transitions + failure modes
;; =============================================================================

(deftest status-transitions-pending-translating-complete
  (let [c0 (mk-cues)]
    (is (= :translation/pending (variant c0)))
    (is (= :translation/translating (variant (sut/begin c0))))
    (let [done (:ok (-> c0 sut/begin (sut/add-unit (mk-unit 0 1 "a" "b")) sut/complete))]
      (is (= :translation/complete (variant done))))))

(deftest make-translated-cues-forbids-identical-language
  (let [res (sut/make-translated-cues
             {:id "x" :transcript-id "t" :source-language "en" :target-language "en"})]
    (is (r/err? res))
    (is (= :error/translation-failed (:error res)))))

(deftest make-translated-cues-rejects-invalid-language
  (is (r/err? (sut/make-translated-cues
               {:id "x" :transcript-id "t" :source-language "zz" :target-language "en"})))
  (is (r/err? (sut/make-translated-cues
               {:id "x" :transcript-id "t" :source-language "en" :target-language "zz"}))))

(deftest complete-fails-on-zero-units
  (let [res (sut/complete (mk-cues))]
    (is (r/err? res))
    (is (= :error/translation-failed (:error res)))))

(deftest make-translation-unit-rejects-inverted-range
  (is (r/err? (sut/make-translation-unit
               {:start-ms 1000 :end-ms 0 :source-text "a" :target-text "b"}))))

;; =============================================================================
;; MUTATION — each mutant must break >=1 assertion above's contract
;; =============================================================================

(deftest-mutations make-translated-cues-mutations-caught
  vtranslate.engine.domain.translation/make-translated-cues
  [["always-ok"     (fn [m] (r/ok m))]
   ["allow-src=tgt" (fn [{:keys [source-language target-language] :as m}]
                      (r/let-ok [src (shared/make-language source-language)
                                 tgt (shared/make-language target-language)]
                        (r/ok (assoc m :source-language src :target-language tgt
                                     :units [] :status {:adt/variant :translation/pending}))))]
   ["always-err"    (fn [_] (r/err :error/translation-failed {}))]]
  (fn []
    (is (r/ok?  (sut/make-translated-cues
                 {:id "a" :transcript-id "t" :source-language "en" :target-language "pt"})))
    (is (r/err? (sut/make-translated-cues
                 {:id "a" :transcript-id "t" :source-language "en" :target-language "en"})))
    (is (r/err? (sut/make-translated-cues
                 {:id "a" :transcript-id "t" :source-language "en" :target-language "zz"})))))

(deftest-mutations complete-mutations-caught
  vtranslate.engine.domain.translation/complete
  [["accept-empty" (fn [cues] (r/ok (assoc cues :status {:adt/variant :translation/complete})))]
   ["always-err"   (fn [cues] (r/err :error/translation-failed {:id (:id cues)}))]]
  (fn []
    (let [with-unit (sut/add-unit (mk-cues) (mk-unit 0 1 "a" "b"))]
      (is (r/ok?  (sut/complete with-unit)))
      (is (= :translation/complete (variant (:ok (sut/complete with-unit)))))
      (is (r/err? (sut/complete (mk-cues)))))))

(deftest-mutations add-unit-mutations-caught
  vtranslate.engine.domain.translation/add-unit
  [["no-append"    (fn [cues _unit] cues)]
   ["replace-last" (fn [cues unit] (assoc cues :units [unit]))]]
  (fn []
    (let [c (-> (mk-cues)
                (sut/add-unit (mk-unit 0 1 "a" "x"))
                (sut/add-unit (mk-unit 1 2 "b" "y")))]
      (is (= 2 (sut/unit-count c)))
      (is (= ["a" "b"] (mapv :source-text (:units c)))))))

(deftest-mutations begin-mutations-caught
  vtranslate.engine.domain.translation/begin
  [["no-op"       (fn [cues] cues)]
   ["to-complete" (fn [cues] (assoc cues :status {:adt/variant :translation/complete}))]]
  (fn []
    (is (= :translation/translating (variant (sut/begin (mk-cues)))))))

(deftest-mutations unit-count-mutations-caught
  vtranslate.engine.domain.translation/unit-count
  [["always-zero" (fn [_] 0)]
   ["off-by-one"  (fn [{:keys [units]}] (inc (count units)))]]
  (fn []
    (let [c (-> (mk-cues)
                (sut/add-unit (mk-unit 0 1 "a" "x"))
                (sut/add-unit (mk-unit 1 2 "b" "y"))
                (sut/add-unit (mk-unit 2 3 "c" "z")))]
      (is (= 3 (sut/unit-count c)))
      (is (= 0 (sut/unit-count (mk-cues)))))))

(deftest-mutations make-translation-unit-mutations-caught
  vtranslate.engine.domain.translation/make-translation-unit
  [["ignore-validation" (fn [m] (r/ok m))]
   ["swap-src-tgt"      (fn [{:keys [source-text target-text] :as m}]
                          (r/ok (assoc m :source-text target-text :target-text source-text)))]]
  (fn []
    (let [u (:ok (sut/make-translation-unit
                  {:start-ms 0 :end-ms 1000 :source-language "en"
                   :source-text "hi" :target-text "oi"}))]
      (is (= "hi" (:source-text u)))
      (is (= "oi" (:target-text u))))
    (is (r/err? (sut/make-translation-unit
                 {:start-ms 1000 :end-ms 0 :source-text "a" :target-text "b"})))))
