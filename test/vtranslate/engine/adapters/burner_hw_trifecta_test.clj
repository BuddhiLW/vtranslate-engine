(ns vtranslate.engine.adapters.burner-hw-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.burner.ffmpeg-hw :as b]
            [vtranslate.engine.collect.ffmpeg-cli :as cli]
            [vtranslate.engine.port.burner :as port]
            [vtranslate.engine.schema.port-values :as p]))
(defn make-observation [encoder]
  (let [burner (b/make-burner {:burn-encoder encoder})]
    (with-redefs [cli/burn-hardsub (fn [_ _ out _ _ actual] (assert (= encoder actual)) out)]
      {:in encoder :out {:encoder (:encoder burner) :path (port/burn! burner "fixture" "out.mp4" {:cues []} {})}})))
(deftrifecta make-burner make-observation
  {:golden-path "test/golden/adapters-burner-hw-make.edn"
   :cases {:nvenc :h264-nvenc :vaapi :h264-vaapi}
   :gen (gen/elements [:h264-nvenc :h264-vaapi])
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:encoder :keyword] [:path p/BurnValue]] out)
                (= in (:encoder out)) (= "out.mp4" (:path out))))})
(defn resolve-observation [backend]
  (let [result (b/resolve-hw backend {})]
    {:in backend :out (if (:ok result) {:ok (:encoder (:ok result))} result)}))
(deftrifecta resolve-hw resolve-observation
  {:golden-path "test/golden/adapters-burner-hw-resolve.edn"
   :cases {:nvenc :ffmpeg-nvenc :vaapi :ffmpeg-vaapi}
   :gen (gen/elements [:ffmpeg-nvenc :ffmpeg-vaapi])
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:ok :keyword]] out) (= (get b/backend-encoders in) (:ok out))))})
