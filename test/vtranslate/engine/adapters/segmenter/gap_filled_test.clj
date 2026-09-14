(ns vtranslate.engine.adapters.segmenter.gap-filled-test
  "The decorator's contract is substitution: it IS an ISegmenter, it returns the
   port's span shape, and under a pass-through policy the inner segmenter's
   behaviour survives verbatim. The interesting half is the policy seam — an
   open set resolved through the registry, so a deployment can add its own."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.coverage.policies :as policies]
            [vtranslate.engine.adapters.segmenter.gap-filled :as gap]
            [vtranslate.engine.adapters.segmenter.stub :as stub]
            [vtranslate.engine.port.coverage :as p.cov]
            [vtranslate.engine.port.segmenter :as p.seg]
            [vtranslate.engine.providers.coverage-registry :as reg]))

(defrecord FixedSegmenter [spans]
  p.seg/ISegmenter
  (segment [_ _audio-source _opts] (r/ok {:spans spans})))

(defrecord FailingSegmenter []
  p.seg/ISegmenter
  (segment [_ _audio-source _opts]
    (r/err :error/segmentation-failed {:reason "inner refused"})))

(def ^:private vad-like
  "The silero shape measured on Sintel's closing minutes: one accepted span."
  (->FixedSegmenter [{:start-ms 440 :end-ms 2152}]))

(deftest satisfies-the-segmenter-port
  (is (satisfies? p.seg/ISegmenter (gap/make-gap-filled vad-like (policies/make-grid-fill {})))))

(deftest fills-what-the-inner-segmenter-left-out
  (let [seg  (gap/make-gap-filled vad-like (policies/make-grid-fill {:window-ms 15000}))
        out  (p.seg/segment seg {:duration-ms 180000} {})]
    (is (r/ok? out))
    (testing "the inner span survives and the rest of the clip is tiled"
      (let [spans (:spans (:ok out))]
        (is (some #{{:start-ms 440 :end-ms 2152}} spans))
        (is (= 180000 (:end-ms (last spans))))
        (is (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b))) (partition 2 1 spans)))))))

(deftest pass-through-leaves-the-inner-segmenter-alone
  (testing "LSP: with :none the decorated segmenter answers exactly what it wraps"
    (let [inner (->FixedSegmenter [{:start-ms 10 :end-ms 20}])
          seg   (gap/make-gap-filled inner (policies/->PassThroughPolicy))]
      (is (= (:ok (p.seg/segment inner {:duration-ms 5000} {}))
             (:ok (p.seg/segment seg {:duration-ms 5000} {})))))))

(deftest an-unknown-duration-cannot-invent-coverage
  (testing "nothing carries :duration-ms, so the policy has nothing to fill against"
    (let [seg (gap/make-gap-filled vad-like (policies/make-grid-fill {}))]
      (is (= [{:start-ms 440 :end-ms 2152}]
             (:spans (:ok (p.seg/segment seg {} {}))))))))

(deftest an-inner-failure-is-not-masked-by-coverage
  (let [seg (gap/make-gap-filled (->FailingSegmenter) (policies/make-grid-fill {}))
        out (p.seg/segment seg {:duration-ms 180000} {})]
    (is (r/err? out))
    (is (= :error/segmentation-failed (:error out)))))

(deftest wrap-resolves-the-policy-through-the-registry
  (testing "the default is to fill"
    (let [wrapped (gap/wrap vad-like {})]
      (is (r/ok? wrapped))
      (is (instance? vtranslate.engine.adapters.coverage.policies.GridFillPolicy
                     (:policy (:ok wrapped))))))
  (testing ":none selects the pass-through policy"
    (let [wrapped (gap/wrap vad-like {:segmenter-opts {:coverage :none}})]
      (is (r/ok? wrapped))
      (is (instance? vtranslate.engine.adapters.coverage.policies.PassThroughPolicy
                     (:policy (:ok wrapped))))))
  (testing "an unknown policy fails LOUD rather than silently skipping coverage"
    (let [wrapped (gap/wrap vad-like {:segmenter-opts {:coverage :telepathy}})]
      (is (r/err? wrapped))
      (is (= :error/unknown-coverage-policy (:error wrapped)))
      (is (contains? (set (:known wrapped)) :grid-fill)))))

(deftest policy-opts-come-from-segmenter-opts
  (let [policy (:ok (reg/resolve-coverage-policy
                     :grid-fill {:segmenter-opts {:grid-fill-ms 4000 :grid-fill-min-gap-ms 250}}))]
    (is (= 4000 (:window-ms policy)))
    (is (= 250 (:min-gap-ms policy)))
    (testing "and a nonsense value falls back to the default rather than dividing by it"
      (let [d (:ok (reg/resolve-coverage-policy :grid-fill {:segmenter-opts {:grid-fill-ms 0}}))]
        (is (= policies/default-window-ms (:window-ms d)))))))

(deftest the-grid-segmenter-is-unchanged-by-filling
  (testing "a segmenter that already tiles the clip has nothing left to fill"
    (let [grid (stub/make-segmenter 5000)
          seg  (gap/make-gap-filled grid (policies/make-grid-fill {}))]
      (is (= (:spans (:ok (p.seg/segment grid {:duration-ms 16500} {})))
             (:spans (:ok (p.seg/segment seg {:duration-ms 16500} {}))))))))

(deftest policies-answer-the-port
  (doseq [p [(policies/make-grid-fill {}) (policies/->PassThroughPolicy)]]
    (is (satisfies? p.cov/ICoveragePolicy p))
    (is (r/ok? (p.cov/cover p [{:start-ms 0 :end-ms 100}] 1000)))))
