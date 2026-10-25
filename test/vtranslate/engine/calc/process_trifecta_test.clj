(ns vtranslate.engine.calc.process-trifecta-test
  "Pure policies under calc/**: no thread, native library or process is started."
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-process :as s]
            [vtranslate.engine.calc.native-stack :as stack]
            [vtranslate.engine.calc.subprocess-env :as env]))

(def env-gen
  (gen/map (gen/elements ["PATH" "HOME" "LANG" "LC_ALL" "XDG_CACHE_HOME"
                         "CUDA_VISIBLE_DEVICES" "API_KEY" "LD_PRELOAD" nil])
           (gen/one-of [gen/string-alphanumeric (gen/return nil)]) {:max-elements 9}))
(def policy-gen
  (gen/elements [nil {} {:allow #{"ONLY"} :allow-prefixes [] :overrides {}}
                 {:overrides {"HOME" "sandbox" "FIXED" "yes"}}]))

(deftrifecta stack-bytes stack/stack-bytes
  {:golden-path "test/golden/calc-process-stack.edn"
   :cases {:default nil :missing {} :too-small {:stack-bytes 1}
           :invalid {:stack-bytes "16M"} :negative {:stack-bytes -1}
           :larger {:stack-bytes 33554432}}
   :gen (gen/one-of [(gen/return nil)
                     (gen/hash-map :stack-bytes (gen/one-of [gen/small-integer gen/string
                                                            (gen/choose 8388608 33554432)]))])
   :pred #(m/validate s/StackBytes %)
   :mutations [["honours-unsafe-size" (fn [opts] (or (:stack-bytes opts) stack/default-stack-bytes))]]})

(deftrifecta allowed env/allowed?
  {:apply? true :golden-path "test/golden/calc-process-allowed.edn"
   :cases {:exact [env/default-policy "PATH"] :prefix [env/default-policy "LC_ALL"]
           :credential [env/default-policy "API_KEY"] :injection [env/default-policy "LD_PRELOAD"]}
   :gen (gen/tuple (gen/return env/default-policy)
                   (gen/elements ["PATH" "HOME" "LC_ALL" "CUDA_HOME" "API_KEY" "LD_PRELOAD" nil]))
   :pred #(m/validate :boolean %)
   :mutations [["allows-everything" (fn [& _] true)]]})

(deftrifecta sanitize env/sanitize
  {:apply? true :golden-path "test/golden/calc-process-sanitize.edn"
   :cases {:default [{"PATH" "/bin" "HOME" "/real" "API_KEY" "secret"}]
           :empty [{}] :nils [{nil "bad" "LANG" nil}]
           :overrides [{"HOME" "/real"} {:overrides {"HOME" "sandbox"}}]}
   :gen (gen/tuple env-gen policy-gen)
   :pred #(m/validate s/Environment %)
   :mutations [["inherits-everything" (fn [env & _] env)]]})

(deftrifecta withheld env/withheld
  {:apply? true :golden-path "test/golden/calc-process-withheld.edn"
   :cases {:default [{"PATH" "/bin" "Z_KEY" "z" "A_KEY" "a"}]
           :empty [{}] :nils [{nil "bad" "LANG" nil}]
           :override-kept [{"API_KEY" "key"} {:overrides {"API_KEY" "fixed"}}]}
   :gen (gen/tuple env-gen policy-gen)
   :pred #(m/validate s/Withheld %)
   :mutations [["withholds-nothing" (fn [& _] [])]]})
