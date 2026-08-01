(ns vtranslate.engine.providers.compatibility-test
  "The PAIRING RULE is exercised through fictional provider keys registered by
   this ns and removed afterwards, so the rule is asserted independently of which
   adapters happen to exist. A separate deftest pins the declarations the real
   adapters register."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-dsl.result :as r]
            [hive-schemas.test :refer [deftrifecta-from-schema]]
            [vtranslate.engine.schema :as s]
            [vtranslate.engine.providers.compatibility :as sut]
            [vtranslate.engine.adapters.segmenter.stub]
            [vtranslate.engine.adapters.segmenter.silero-vad]
            [vtranslate.engine.adapters.transcriber.nemo-python]))

;; --- fictional providers, registered only for this ns ------------------------

(def utterance-only ::utterance-only)
(def windowed       ::windowed)
(def utterance-cut  ::utterance-cut)
(def undeclared     ::undeclared)

(defn- with-fictional-providers [f]
  (defmethod sut/segmentation-required utterance-only [_] :utterance)
  (defmethod sut/segmentation-produced windowed      [_] :fixed-window)
  (defmethod sut/segmentation-produced utterance-cut [_] :utterance)
  (try (f)
       (finally
         (remove-method sut/segmentation-required utterance-only)
         (remove-method sut/segmentation-produced windowed)
         (remove-method sut/segmentation-produced utterance-cut))))

(use-fixtures :once with-fictional-providers)

(defn- routing [transcriber segmenter]
  {:segmenter segmenter :transcriber transcriber :translator nil :composer :none
   :addons [] :segmenter-opts {} :transcriber-opts {} :translator-opts {}
   :composer-opts {}})

;; --- the rule ----------------------------------------------------------------

(deftest defaults-claim-nothing
  (is (= :any (sut/segmentation-required ::never-registered))
      "an unregistered transcriber requires nothing in particular")
  (is (= :unknown (sut/segmentation-produced ::never-registered))
      "an unregistered segmenter declares nothing"))

(deftest refuses-only-a-declared-losing-pairing
  (testing "utterance-only transcriber + fixed-window segmenter"
    (is (sut/incompatible? utterance-only windowed)))
  (testing "every other combination stands"
    (is (not (sut/incompatible? utterance-only utterance-cut)))
    (is (not (sut/incompatible? utterance-only undeclared))
        "an undeclared segmenter is never refused — a third-party segmenter still routes")
    (is (not (sut/incompatible? undeclared windowed))
        "a transcriber that requires nothing pairs with any segmenter")
    (is (not (sut/incompatible? undeclared undeclared)))))

(deftest check-fails-loud-with-a-repair-hint
  (let [res (sut/check (routing utterance-only windowed))]
    (is (r/err? res))
    (is (= :error/incompatible-routing (:error res)))
    (is (= utterance-only (:transcriber res)))
    (is (= windowed (:segmenter res)))
    (is (re-find #"silero-vad" (:hint res))
        "the error names the segmenter that does work"))
  (testing "a compatible pairing passes the routing through unchanged"
    (let [route (routing utterance-only utterance-cut)]
      (is (= route (:ok (sut/check route)))))))

(deftest a-nil-transcriber-is-not-a-pairing
  (is (r/ok? (sut/check (routing nil :grid)))
      "routing with no transcriber selected yet is not refused"))

;; --- free coverage: check is total over every well-formed routing ------------

(defn- ok-or-refusal [_in out]
  (if (contains? out :ok)
    (s/routing-config? (:ok out))
    (= :error/incompatible-routing (:error out))))

(deftrifecta-from-schema check sut/check
  {:in s/RoutingConfig :out (s/result-of s/RoutingConfig)
   :rel ok-or-refusal :mutation false})

;; --- what the real adapters declare ------------------------------------------

(deftest real-adapters-declare-their-segmentation
  (testing "the NeMo AED providers require utterance boundaries"
    (is (= :utterance (sut/segmentation-required :canary)))
    (is (= :utterance (sut/segmentation-required :parakeet))))
  (testing "the segmenters declare what they cut"
    (is (= :fixed-window (sut/segmentation-produced :grid)))
    (is (= :utterance (sut/segmentation-produced :silero-vad))))
  (testing "the engine default pairing for a NeMo provider is refused"
    (is (sut/incompatible? :canary :grid))
    (is (not (sut/incompatible? :canary :silero-vad)))))
