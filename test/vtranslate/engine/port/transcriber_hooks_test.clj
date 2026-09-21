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
            [vtranslate.engine.adapters.transcriber.stub :as stub]
            [vtranslate.engine.adapters.transcriber.whisper-ffm :as ffm]
            [vtranslate.engine.adapters.transcriber.sherpa-onnx :as sherpa]
            [vtranslate.engine.adapters.transcriber.onnx-bytedeco :as onnx]
            [vtranslate.engine.adapters.transcriber.qwen3-asr :as qwen3]
            [vtranslate.engine.adapters.transcriber.nemo-python :as nemo]))

(def declared
  "Every transcriber adapter: the hooks it honours, and a way to BUILD one. An
   adapter that honours none would be listed with an empty set ON PURPOSE, so
   \"nobody decided yet\" cannot be spelled the same way as \"it deliberately
   reads no hook\".

   The :build thunk is what stops this map from being a second opinion. Without
   it the inventory asserts only that it agrees with itself; with it, every row
   is checked against what the record actually says through the port. That is
   the whole defect this file exists for: the stub once claimed to run
   :asr/clean and did not, so the addon's clean tests passed against a double
   the production adapter did not behave like."
  {"whisper_jni"       {:hooks #{:asr/route-window :asr/clean}
                        :build #(whisper/->WhisperLocalTranscriber "m" false 0 nil)}
   "openai_compatible" {:hooks #{:asr/clean}
                        :build #(openai/->OpenAiTranscriber "u" "m" nil {})}
   "stub"              {:hooks #{:asr/clean}
                        :build #(stub/make-transcriber)}
   ;; The five that were inert. Each decodes the whole clip (or loops spans)
   ;; with no per-window decode a route could be handed, so each honours
   ;; :asr/clean and declares nothing else — leaving :asr/route-window
   ;; undeclared is what lets p.asr/inert-hooks tell the truth about them.
   "whisper_ffm"       {:hooks #{:asr/clean}
                        :build #(ffm/->WhisperFfmTranscriber "lib" "model" 1024)}
   "sherpa_onnx"       {:hooks #{:asr/clean}
                        :build #(sherpa/->SherpaOnnxTranscriber {})}
   "onnx_bytedeco"     {:hooks #{:asr/clean}
                        :build #(onnx/->OnnxBytedecoTranscriber "dir" {})}
   "qwen3_asr"         {:hooks #{:asr/clean}
                        :build #(qwen3/->Qwen3AsrTranscriber :k nil 500)}
   "nemo_python"       {:hooks #{:asr/clean}
                        :build #(nemo/->NemoTranscriber "m" nil 500 "python3" nil "cpu" false)}})

(defn- adapter-names
  "The adapter namespaces on disk, by file name. `*_native.clj` are the JNI/FFM
   shims behind an adapter, `support.clj` is shared helpers, and
   `whisper_budget.clj` is the pure VRAM admission policy the whisper-jni shim
   decides over; none of the three is an adapter, so none of them has a hook
   set to declare."
  []
  (->> (file-seq (io/file "src/vtranslate/engine/adapters/transcriber"))
       (filter #(.isFile ^java.io.File %))
       (map #(.getName ^java.io.File %))
       (filter #(str/ends-with? % ".clj"))
       (remove #(str/ends-with? % "_native.clj"))
       (remove #{"support.clj" "whisper_budget.clj"})
       (map #(str/replace % #"\.clj$" ""))
       set))

(deftest every-transcriber-adapter-has-a-decided-hook-set
  (let [found (adapter-names)]
    (is (seq found)
        "the file walk found adapters at all: an empty universe would pass vacuously")
    (is (= found (set (keys declared)))
        "a new adapter is given its hook set HERE, deliberately, before it ships")))

(deftest the-adapters-say-through-the-port-what-this-inventory-claims
  (doseq [[adapter {:keys [hooks build]}] declared]
    (is (= hooks (p.asr/honoured (build))) adapter))
  (testing "and the stub is a FAITHFUL double: it really runs the hook it claims"
    (let [seen (atom nil)
          out  (p.asr/transcribe* (stub/make-transcriber 1000)
                                  {:duration-ms 2000} "en"
                                  {:asr/clean (fn [raw _] (reset! seen (count raw)) [])})]
      (is (= 2 @seen) "the hook saw the raw segments")
      (is (= [] (:segments (:ok out))) "and what it returned is what came back"))))

(defn- hook-call-sites
  "The source of `adapter` and of its `_native` sibling, concatenated. An
   adapter that delegates its pipeline to a native ns honours the hook down
   there, because that is the only place its raw segments exist."
  [adapter]
  (let [dir   "src/vtranslate/engine/adapters/transcriber/"
        read1 (fn [f] (let [file (io/file (str dir f))]
                        (when (.isFile file) (slurp file))))]
    (str (read1 (str adapter ".clj")) (read1 (str adapter "_native.clj")))))

(deftest a-declared-hook-has-a-call-site
  ;; STRUCTURAL evidence, and deliberately labelled as such: it proves the
  ;; declaration and a call to the port's hook runner live in the same adapter,
  ;; not that the hook is reached on every path. The behavioural tier is the
  ;; faithful-double test above, and it is affordable only for the stub —
  ;; every other adapter needs a native backend and real audio to exercise.
  ;;
  ;; Weak as it is, it is the gate that was missing. `stub` declared nothing
  ;; and ran nothing, whisper_jni read the hook keys itself, and six adapters
  ;; had neither declaration nor call. A declaration nothing reads is
  ;; decoration, and its drift is what nobody can see.
  (doseq [[adapter {:keys [hooks]}] declared
          :when (contains? hooks :asr/clean)
          :let  [src (hook-call-sites adapter)]]
    (is (seq src) (str adapter ": no source found to check"))
    (is (or (str/includes? src "p.asr/cleaned")
            (str/includes? src "p.asr/decoded"))
        (str adapter " declares :asr/clean but calls neither p.asr/cleaned nor "
             "p.asr/decoded — the declaration would be decoration")))
  (testing "an adapter that declares no hook must not be quietly running one"
    (doseq [[adapter {:keys [hooks]}] declared
            :when (empty? hooks)]
      (is (not (str/includes? (hook-call-sites adapter) "p.asr/cleaned"))
          (str adapter " runs the clean hook without declaring it, so "
               "inert-hooks would report a live hook as inert")))))

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
