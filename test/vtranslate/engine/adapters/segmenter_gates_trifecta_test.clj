(ns vtranslate.engine.adapters.segmenter-gates-trifecta-test
  "Silero segmenter construction (both capability gates stubbed open; the
   ONNX session itself is never created), the gap-filled default policy and the
   grid-fill defaults. Offline: no model file is read and no native code runs."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as walk]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.coverage.policies :as pol]
            [vtranslate.engine.adapters.segmenter.gap-filled :as gap]
            [vtranslate.engine.adapters.segmenter.silero-vad :as vad]
            [vtranslate.engine.port.segmenter :as seg]
            [vtranslate.engine.schema.adapters-gates :as g]
            [vtranslate.engine.schema.port-values :as p]))

(def ^:private knob-defaults
  {:threshold vad/default-threshold :neg-threshold vad/default-neg-threshold
   :min-speech-ms vad/default-min-speech-ms :min-silence-ms vad/default-min-silence-ms
   :speech-pad-ms vad/default-speech-pad-ms :max-span-ms vad/default-max-span-ms
   :merge-to-ms vad/default-merge-to-ms})

(defn make-observation
  "Build through the real constructor with the classpath and model gates open."
  [config]
  (with-redefs [vad/onnxruntime-present? (constantly true)
                vad/model-present? (constantly true)]
    (let [res (vad/make-segmenter config)]
      {:in config :out (cond-> res (:ok res) (update :ok #(into {} %)))})))

(defn- doubles-for-golden
  "Floats print shorter than they read back; snapshot them as doubles."
  [x]
  (walk/postwalk #(if (float? %) (double %) %) x))

(def ^:private opts-gen
  (gen/let [ks (gen/vector (gen/elements (keys knob-defaults)) 0 7)
            ms (gen/vector (gen/choose 0 60000) 7)
            th (gen/vector (gen/elements [0.1 0.25 0.5 0.75 0.9]) 2)
            path (gen/one-of [(gen/return nil) (gen/fmap #(str "models/" % ".onnx") gen/string-alphanumeric)])]
    (let [vals (zipmap [:min-speech-ms :min-silence-ms :speech-pad-ms :max-span-ms :merge-to-ms] ms)
          vals (assoc vals :threshold (first th) :neg-threshold (second th))]
      {:segmenter-opts (cond-> (select-keys vals ks) path (assoc :model-path path))})))

(deftrifecta make-segmenter make-observation
  {:golden-path "test/golden/adapters-silero-make.edn"
   :cases {:defaults {}
           :model {:segmenter-opts {:model-path "models/custom.onnx"}}
           :knobs {:segmenter-opts {:threshold 0.6 :neg-threshold 0.2 :min-speech-ms 100
                                    :min-silence-ms 50 :speech-pad-ms 10 :max-span-ms 5000
                                    :merge-to-ms 0}}}
   :xf doubles-for-golden
   :gen opts-gen
   :pred (fn [{config :in out :out}]
           (let [opts (:segmenter-opts config) v (:ok out)]
             (and (m/validate g/SileroMakeResult out)
                  (= (:model-path v) (or (:model-path opts) vad/default-model-path))
                  (every? (fn [[k d]]
                            (if (#{:threshold :neg-threshold} k)
                              (= (float (get opts k d)) (get v k))
                              (= (long (get opts k d)) (get v k))))
                          knob-defaults))))})

(deftest silero-gates-refuse-offline
  (testing "no ai.onnxruntime on the unit classpath: refused before any model probe"
    (let [res (vad/make-segmenter {})]
      (is (m/validate g/SileroMakeResult res))
      (is (re-find #":silero-vad deps alias" (:reason res)))))
  (testing "runtime present but model absent: the hint names the path that was looked at"
    (with-redefs [vad/onnxruntime-present? (constantly true)]
      (let [res (vad/make-segmenter {:segmenter-opts {:model-path "/nonexistent/vad.onnx"}})]
        (is (m/validate g/SileroMakeResult res))
        (is (re-find #"/nonexistent/vad.onnx" (:reason res))))))
  (testing "the default model path is relative to the engine's working dir"
    (is (= "models/silero_vad.onnx" vad/default-model-path))))

(deftest gap-filled-default-policy-is-grid-fill
  (let [inner (reify seg/ISegmenter (segment [_ _ _] {:ok {:spans []}}))
        wrapped (:ok (gap/wrap inner {}))]
    (is (= :grid-fill gap/default-policy-key))
    (is (instance? vtranslate.engine.adapters.coverage.policies.GridFillPolicy (:policy wrapped)))
    (testing "the default policy tiles a clip the inner segmenter left empty"
      (let [res (seg/segment wrapped {:duration-ms 30000} {})]
        (is (m/validate p/SegmentResult res))
        (is (= 30000 (reduce + (map #(- (:end-ms %) (:start-ms %)) (get-in res [:ok :spans])))))))))

(deftest grid-fill-defaults
  (is (= {:window-ms pol/default-window-ms :min-gap-ms pol/default-min-gap-ms}
         (into {} (pol/make-grid-fill {}))))
  (is (< 0 pol/default-min-gap-ms pol/default-window-ms))
  (testing "non-positive overrides fall back to the defaults"
    (is (= pol/default-min-gap-ms (:min-gap-ms (pol/make-grid-fill {:min-gap-ms 0}))))))
