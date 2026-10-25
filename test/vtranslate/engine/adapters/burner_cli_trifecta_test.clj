(ns vtranslate.engine.adapters.burner-cli-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.burner.ffmpeg-cli :as b]
            [vtranslate.engine.collect.ffmpeg-cli :as cli]
            [vtranslate.engine.port.burner :as port]
            [vtranslate.engine.schema.port-values :as p]))
(defn executable-observation [bin] {:in bin :out (:bin (b/executables {:ffmpeg-bin bin}))})
(deftrifecta executables executable-observation
  {:golden-path "test/golden/adapters-burner-cli-executables.edn"
   :cases {:default nil :custom "/opt/ffmpeg"}
   :gen (gen/one-of [(gen/return nil) (gen/fmap #(str "/bin/" %) gen/string-alphanumeric)])
   :pred (fn [{:keys [in out]}] (and (m/validate :string out) (= (or in "ffmpeg") out)))})
(defn make-observation [path]
  (with-redefs [cli/burn-hardsub (fn [_ _ out _ _] out)]
    {:in path :out (port/burn! (b/make-burner {}) "fixture.mp4" path {:cues []} {})}))
(deftrifecta make-burner make-observation
  {:golden-path "test/golden/adapters-burner-cli-make.edn"
   :cases {:output "/tmp/out.mp4" :spaced "/tmp/out file.mp4"}
   :gen (gen/fmap #(str "/tmp/" % ".mp4") gen/string-alphanumeric)
   :pred (fn [{:keys [in out]}] (and (m/validate p/BurnValue out) (= in out)))})
