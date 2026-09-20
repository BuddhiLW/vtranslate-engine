(ns vtranslate.engine.port.transcriber-hooks-test
  "The gate for call-opt hooks. Its universe is the adapter FILES on disk, not
   the adapters that already declare something: an adapter nobody has looked at
   is exactly the case this exists to catch."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vtranslate.engine.adapters.transcriber.openai-compatible :as openai]
            [vtranslate.engine.adapters.transcriber.whisper-jni :as whisper]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.adapters.transcriber.stub :as stub]))

(def declared
  "Every transcriber adapter, and the hooks it honours. An adapter that honours
   none is listed with an empty set ON PURPOSE, so \"nobody decided yet\" cannot
   be spelled the same way as \"it deliberately reads no hook\"."
  {"whisper_jni"       #{:asr/route-window :asr/clean}
   "openai_compatible" #{:asr/clean}
   "stub"              #{:asr/clean}
   "whisper_ffm"       #{}
   "sherpa_onnx"       #{}
   "onnx_bytedeco"     #{}
   "qwen3_asr"         #{}
   "nemo_python"       #{}})

(defn- adapter-names
  "The adapter namespaces on disk, by file name. `*_native.clj` are the JNI/FFM
   shims behind an adapter, and `support.clj` is shared helpers; neither is an
   adapter."
  []
  (->> (file-seq (io/file "src/vtranslate/engine/adapters/transcriber"))
       (filter #(.isFile ^java.io.File %))
       (map #(.getName ^java.io.File %))
       (filter #(str/ends-with? % ".clj"))
       (remove #(str/ends-with? % "_native.clj"))
       (remove #{"support.clj"})
       (map #(str/replace % #"\.clj$" ""))
       set))

(deftest every-transcriber-adapter-has-a-decided-hook-set
  (let [found (adapter-names)]
    (is (seq found)
        "the file walk found adapters at all: an empty universe would pass vacuously")
    (is (= found (set (keys declared)))
        "a new adapter is given its hook set HERE, deliberately, before it ships")))

(deftest the-adapters-say-through-the-port-what-this-inventory-claims
  (is (= (declared "whisper_jni")
         (p.asr/honoured (whisper/->WhisperLocalTranscriber "m" false 0 nil))))
  (is (= (declared "openai_compatible")
         (p.asr/honoured (openai/->OpenAiTranscriber "u" "m" nil {}))))
  (is (= (declared "stub") (p.asr/honoured (stub/make-transcriber))))
  (testing "and the stub is a FAITHFUL double: it really runs the hook it claims"
    (let [seen (atom nil)
          out  (p.asr/transcribe* (stub/make-transcriber 1000)
                                  {:duration-ms 2000} "en"
                                  {:asr/clean (fn [raw _] (reset! seen (count raw)) [])})]
      (is (= 2 @seen) "the hook saw the raw segments")
      (is (= [] (:segments (:ok out))) "and what it returned is what came back"))))

(deftest an-adapter-that-declares-nothing-honours-nothing
  (let [silent (p.asr/transcriber (fn [_ _ _] nil))]
    (is (= #{} (p.asr/honoured silent)))
    (is (= (sorted-set :asr/clean :asr/route-window)
           (p.asr/inert-hooks silent {:asr/clean identity :asr/route-window identity}))
        "a decorator can see that everything it contributed will do nothing")
    (testing "a hook nobody contributed is not reported inert"
      (is (= (sorted-set :asr/clean) (p.asr/inert-hooks silent {:asr/clean identity}))))
    (testing "and a declared hook is not reported inert"
      (let [partial-adapter (reify p.asr/IDeclaresHooks (hooks-honoured [_] #{:asr/clean}))]
        (is (= (sorted-set :asr/route-window)
               (p.asr/inert-hooks partial-adapter
                                  {:asr/clean identity :asr/route-window identity})))))))

(deftest the-port-defines-the-hook-order-once
  (is (= [:asr/route-window :asr/clean] p.asr/hook-keys))
  (testing "cleaned is a no-op without the hook"
    (is (= [{:text "x"}] (p.asr/cleaned {} [{:text "x"}]))))
  (testing "and passes the whole opts map to the hook, not just the segments"
    (is (= [:saw {:asr/clean :fn :extra 1}]
           (p.asr/cleaned {:asr/clean (fn [_ o] [:saw (assoc o :asr/clean :fn)]) :extra 1}
                          [{:text "x"}])))))
