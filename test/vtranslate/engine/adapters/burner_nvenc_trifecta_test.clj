(ns vtranslate.engine.adapters.burner-nvenc-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.burner.ffmpeg-nvenc :as b]
            [vtranslate.engine.collect.ffmpeg-cli :as cli]
            [vtranslate.engine.port.burner :as port]
            [vtranslate.engine.schema.port-values :as p]))
(defn observation [bin]
  (let [burner (b/make-burner {:ffmpeg-bin bin})]
    (with-redefs [cli/burn-hardsub (fn [_ _ out _ _ encoder] (assert (= :h264-nvenc encoder)) out)]
      {:in bin :out {:bin (get-in burner [:cli :bin]) :encoder (:encoder burner)
                     :path (port/burn! burner "fixture" "out.mp4" {:cues []} {})}})))
(deftrifecta make-burner observation
  {:golden-path "test/golden/adapters-burner-nvenc-make.edn"
   :cases {:default nil :custom "/opt/ffmpeg"}
   :gen (gen/fmap #(str "/bin/" %) gen/string-alphanumeric)
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:bin :string] [:encoder :keyword] [:path p/BurnValue]] out)
                (= (or in "ffmpeg") (:bin out)) (= :h264-nvenc (:encoder out)) (= "out.mp4" (:path out))))})
