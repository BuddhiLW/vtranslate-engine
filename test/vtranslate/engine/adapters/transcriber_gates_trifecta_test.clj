(ns vtranslate.engine.adapters.transcriber-gates-trifecta-test
  "Capability gates, constructors and pure-after-stub helpers of the ASR
   adapters. Every native probe is either observed false (the unit classpath
   carries none of the optional aliases) or redefined open; model bundles are
   empty files under a per-observation temp dir. No native library is loaded,
   no interpreter embedded, no socket opened, no model decoded."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.transcriber.nemo-python :as nemo]
            [vtranslate.engine.adapters.transcriber.onnx-bytedeco :as onnx]
            [vtranslate.engine.adapters.transcriber.openai-compatible :as oa]
            [vtranslate.engine.adapters.transcriber.qwen3-asr :as qwen]
            [vtranslate.engine.adapters.transcriber.sherpa-onnx :as sherpa]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.adapters.transcriber.whisper-jni :as jni]
            [vtranslate.engine.providers.transcriber-registry :as reg]
            [vtranslate.engine.schema.adapters-gates :as g])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; --- fixtures ----------------------------------------------------------------

(defn- with-bundle
  "Call (f dir) with `names` created as empty files under a fresh temp dir,
   deleting everything afterwards."
  [names f]
  (let [dir (.toFile (Files/createTempDirectory "vt-bundle" (make-array FileAttribute 0)))]
    (try
      (doseq [n names] (.createNewFile (io/file dir n)))
      (f (.getPath dir))
      (finally
        (doseq [x (reverse (file-seq dir))] (.delete ^java.io.File x))))))

(defn- relative [dir m]
  (when m (update-vals m #(subs % (inc (count dir))))))

;; --- sherpa: model-file discovery --------------------------------------------

(def ^:private sherpa-name-gen
  (gen/elements ["tiny-encoder.onnx" "tiny-encoder.int8.onnx" "tiny-decoder.onnx"
                 "base-decoder.int8.onnx" "tokens.txt" "tiny-tokens.txt" "README.md"
                 "model.bin" "Encoder-x.ONNX"]))

(defn sherpa-files-observation
  "Discovery over a bundle of `names`, answered relative to the bundle dir."
  [[names opts :as in]]
  {:in in :out (with-bundle names #(relative % (sherpa/resolve-model-files % opts)))})

(defn- sherpa-pick [names explicit re]
  (if (and explicit (contains? (set names) explicit))
    explicit
    (first (filter #(re-find re %) (sort names)))))

(deftrifecta resolve-model-files sherpa-files-observation
  {:golden-path "test/golden/adapters-sherpa-model-files.edn"
   :cases {:complete [["tiny-encoder.onnx" "tiny-decoder.onnx" "tokens.txt"] {}]
           :sorted [["b-encoder.onnx" "a-encoder.onnx" "x-decoder.onnx" "tokens.txt"] {}]
           :pinned [["a-encoder.onnx" "z-encoder.onnx" "x-decoder.onnx" "tokens.txt"]
                    {:encoder "z-encoder.onnx"}]
           :missing-tokens [["tiny-encoder.onnx" "tiny-decoder.onnx"] {}]
           :empty [[] {}]}
   :gen (gen/tuple (gen/fmap (comp vec distinct) (gen/vector sherpa-name-gen 0 6))
                   (gen/one-of [(gen/return {}) (gen/fmap #(hash-map :encoder %) sherpa-name-gen)]))
   :pred (fn [{[names opts] :in out :out}]
           (let [expect {:encoder (sherpa-pick names (:encoder opts) #"(?i)encoder.*\.onnx$")
                         :decoder (sherpa-pick names (:decoder opts) #"(?i)decoder.*\.onnx$")
                         :tokens (sherpa-pick names (:tokens opts) #"(?i)tokens.*\.txt$")}]
             (and (m/validate [:maybe g/SherpaModelFiles] out)
                  (if (every? some? (vals expect)) (= expect out) (nil? out)))))})

(deftest sherpa-resolve-provider-gates
  (testing "unit classpath has no sherpa jar"
    (is (false? (sherpa/backend-present?)))
    (let [res (sherpa/resolve-provider {:transcriber-opts {:model-dir "/nonexistent"}})]
      (is (m/validate g/UnavailableError res))
      (is (re-find #":sherpa deps alias" (:hint res)))))
  (with-redefs-fn {#'sherpa/backend-present? (constantly true)}
    (fn []
      (testing "backend present, bundle absent: the configured dir is echoed"
        (let [res (sherpa/resolve-provider {:transcriber-opts {:model-dir "/nonexistent"}})]
          (is (m/validate g/UnavailableError res))
          (is (= "/nonexistent" (:model-dir res)))))
      (testing "backend present and bundle complete: cfg carries absolute files + defaults"
        (with-bundle ["m-encoder.onnx" "m-decoder.onnx" "tokens.txt"]
          (fn [dir]
            (let [res (sherpa/resolve-provider {:transcriber-opts {:model-dir dir :language " en "}})
                  cfg (get-in res [:ok :cfg])]
              (is (m/validate g/SherpaCfg cfg))
              (is (= {:num-threads 1 :language "en" :task "transcribe" :provider "cpu"
                      :decoding-method "greedy_search"}
                     (dissoc cfg :encoder :decoder :tokens)))
              (is (every? (fn [p] (.startsWith ^String p dir)) ((juxt :encoder :decoder :tokens) cfg))))))))))

;; --- onnx-bytedeco: layout gate ----------------------------------------------

(deftest onnx-gate-over-default-layout
  (is (false? (onnx/backend-available?)))
  (is (m/validate g/OnnxModelFiles onnx/default-model-files))
  (is (m/validate g/UnavailableError (reg/resolve-transcriber :onnx-bytedeco {})))
  (with-redefs-fn {#'onnx/backend-available? (constantly true)}
    (fn []
      (testing "no model dir configured: the hint names every default file"
        (let [res (reg/resolve-transcriber :onnx-bytedeco {})]
          (is (m/validate g/UnavailableError res))
          (is (every? (fn [f] (.contains ^String (:hint res) ^String f)) (vals onnx/default-model-files)))))
      (testing "a dir missing one default file reports exactly that one"
        (with-bundle ["encoder.onnx" "decoder.onnx"]
          (fn [dir]
            (let [res (reg/resolve-transcriber :onnx-bytedeco {:transcriber-opts {:model-dir dir}})]
              (is (m/validate g/UnavailableError res))
              (is (= #{:tokenizer} (set (keys (:missing res)))))))))
      (testing "the full default layout builds the record"
        (with-bundle (vals onnx/default-model-files)
          (fn [dir]
            (let [res (reg/resolve-transcriber :onnx-bytedeco {:transcriber-opts {:model-dir dir}})]
              (is (= dir (get-in res [:ok :model-dir]))))))))))

;; --- NeMo: resolve-nemo ------------------------------------------------------

(def ^:private executable "/bin/sh")

(defn nemo-observation
  "Gate and build with the libpython probe open and an executable stand-in
   interpreter; :library-path is pinned so no host lib dir is listed."
  [[provider opts :as in]]
  (with-redefs-fn {#'nemo/backend-present? (constantly true)}
    #(let [res (nemo/resolve-nemo provider {:transcriber-opts (assoc opts :python-executable executable
                                                                     :library-path "/fixture/libpython3.12.so")})]
       {:in in :out (cond-> res (:ok res) (update :ok (fn [v] (into {} v))))})))

(deftrifecta resolve-nemo nemo-observation
  {:golden-path "test/golden/adapters-nemo-resolve.edn"
   :cases {:canary [:canary {}]
           :canary-ast [:canary {:target-language "de" :device "cuda" :span-pad-ms 100}]
           :parakeet [:parakeet {}]
           :parakeet-ast [:parakeet {:target-language "de"}]
           :unknown [:whisper {}]}
   :gen (gen/tuple (gen/elements [:canary :parakeet :other])
                   (gen/let [t (gen/elements [nil "de" "fr"]) pad (gen/choose 0 2000)
                             dev (gen/elements [nil "cpu" "cuda"])]
                     (cond-> {:span-pad-ms pad} t (assoc :target-language t) dev (assoc :device dev))))
   :pred (fn [{[provider opts] :in out :out}]
           (let [known (get nemo/model-names provider)]
             (cond
               (nil? known) (m/validate g/UnavailableError out)
               (and (:target-language opts) (not (:translates? known))) (m/validate g/UnavailableError out)
               :else (let [v (:ok out)]
                       (and (m/validate g/NemoTranscriberValue v)
                            (= (:model known) (:model v))
                            (= (:multilingual? known) (:multilingual? v))
                            (= (:target-language opts) (:target-language v))
                            (= (or (:device opts) "cpu") (:device v))
                            (= (:span-pad-ms opts) (:span-pad-ms v)))))))})

(deftest nemo-gates-offline
  (is (= #{:canary :parakeet} (set (keys nemo/model-names))))
  (is (.endsWith ^String nemo/default-python "/bin/python"))
  (testing "no libpython-clj on the unit classpath"
    (is (m/validate g/UnavailableError (nemo/resolve-nemo :canary {}))))
  (testing "a non-executable interpreter is refused with its path"
    (with-redefs-fn {#'nemo/backend-present? (constantly true)}
      #(let [res (nemo/resolve-nemo :canary {:transcriber-opts {:python-executable "/nonexistent/python"}})]
         (is (m/validate g/UnavailableError res))
         (is (= "/nonexistent/python" (:python res)))))))

;; --- Qwen3-ASR: registry gate ------------------------------------------------

(defn qwen-observation [opts]
  (with-redefs-fn {#'qwen/backend-present? (constantly true)}
    #(let [res (reg/resolve-transcriber :qwen3-asr {:transcriber-opts opts})]
       {:in opts :out (cond-> res (:ok res) (update :ok (fn [v] (into {} v))))})))

(deftrifecta resolve-qwen3-asr qwen-observation
  {:golden-path "test/golden/adapters-qwen-resolve.edn"
   :cases {:default {} :large {:model-key :qwen3-asr-1.7b :span-pad-ms 100}
           :string-key {:model-key "qwen3-asr-0.6b"} :unknown {:model-key :qwen3-asr-9b}
           :missing-dir {:model-dir "/nonexistent/qwen"}}
   :gen (gen/let [k (gen/elements [nil :qwen3-asr-0.6b :qwen3-asr-1.7b "qwen3-asr-1.7b" :other])
                  pad (gen/choose 0 3000)]
          (cond-> {:span-pad-ms pad} k (assoc :model-key k)))
   :pred (fn [{opts :in out :out}]
           (let [k (keyword (or (:model-key opts) qwen/default-model-key))]
             (if (contains? qwen/known-model-keys k)
               (and (m/validate g/Qwen3TranscriberValue (:ok out))
                    (= k (get-in out [:ok :model-key]))
                    (= (long (get opts :span-pad-ms 500)) (get-in out [:ok :span-pad-ms])))
               (and (m/validate g/UnavailableError out) (= k (:model-key out))))))})

(deftest qwen-gate-offline
  (is (contains? qwen/known-model-keys qwen/default-model-key))
  (is (false? (qwen/backend-present?)))
  (is (m/validate g/UnavailableError (reg/resolve-transcriber :qwen3-asr {}))))

;; --- whisper-jni: default model path -----------------------------------------

(deftest whisper-jni-default-model-path
  (is (= "models/ggml-large-v3.bin" jni/default-model-path))
  (is (m/validate g/UnavailableError (reg/resolve-transcriber :whisper-local {})))
  (with-redefs-fn {#'jni/whisper-jni-present? (constantly true)}
    #(let [res (reg/resolve-transcriber :whisper-local {})]
       (is (m/validate g/UnavailableError res))
       (is (= jni/default-model-path (:model-path res))))))

;; --- OpenAI-compatible: provider-defaults + make-transcriber ----------------

(defn openai-observation
  "Construct through the real constructor; key resolution is stubbed to echo
   the env var name it was asked for, so no environment or pass store is read."
  [[provider opts :as in]]
  (with-redefs-fn {#'oa/resolve-key (fn [env pass] (str "key:" env ":" pass))}
    #(let [t (oa/make-transcriber provider {:transcriber-opts opts})]
       {:in in :out (into {} t)})))

(deftrifecta make-transcriber openai-observation
  {:golden-path "test/golden/adapters-openai-make.edn"
   :cases {:groq [:groq {}]
           :server [:whisper-server {:api-url "http://speaches:8000/v1/audio/transcriptions"
                                     :model "large-v3" :span-pad-ms 50 :slice-spans? true}]
           :build-knobs [:openai-whisper {:secret-env "X_KEY" :secret-pass "pass/x" :temperature 0.0
                                          :not-a-knob 1}]}
   :gen (gen/tuple (gen/elements (keys oa/provider-defaults))
                   (gen/let [ks (gen/vector (gen/elements [:temperature :prompt :span-pad-ms
                                                           :slice-spans? :api-url :model :junk]) 0 5)]
                     (select-keys {:temperature 0.3 :prompt "p" :span-pad-ms 10 :slice-spans? false
                                   :api-url "http://h/x" :model "m" :junk 1} ks)))
   :pred (fn [{[provider opts] :in out :out}]
           (let [d (get oa/provider-defaults provider)]
             (and (m/validate g/OpenAiTranscriberValue out)
                  (= (or (:api-url opts) (:api-url d)) (:api-url out))
                  (= (or (:model opts) (:model d)) (:model out))
                  (= (str "key:" (or (:secret-env opts) (:secret-env d)) ":" (:secret-pass opts)) (:api-key out))
                  (= (:opts out) (select-keys opts [:temperature :prompt :extra-form :span-pad-ms
                                                    :slice-spans? :cues])))))})

(deftest openai-provider-defaults
  (is (m/validate g/OpenAiProviderDefaults oa/provider-defaults))
  (is (= #{:groq :openai-whisper :whisper-server} (set (keys oa/provider-defaults))))
  (testing "only the local server is keyless, so only it resolves without a key"
    (with-redefs-fn {#'oa/resolve-key (constantly nil)}
      (fn []
        (is (contains? (reg/resolve-transcriber :whisper-server {}) :ok))
        (is (m/validate g/UnavailableError (reg/resolve-transcriber :groq {})))))))

;; --- support: wav-bytes-slice ------------------------------------------------

(defn slice-observation
  "Slice a synthetic clip; the WAV read is stubbed, so this is the pure
   pad/clip/encode arithmetic. Bytes are summarised by their count."
  [[total rate span pad :as in]]
  (with-redefs-fn {#'sup/read-wav-mono-floats
                   (fn [_] {:ok {:samples (float-array total) :sample-rate rate}})}
    #(let [res (sup/wav-bytes-slice "fixture.wav" span pad)]
       {:in in :out (update res :ok (fn [v] (-> v (assoc :byte-count (count (:bytes v))) (dissoc :bytes))))})))

(deftrifecta wav-bytes-slice slice-observation
  {:golden-path "test/golden/adapters-support-wav-slice.edn"
   :cases {:inside [16000 16000 {:start-ms 100 :end-ms 300} 50]
           :head-pad [16000 16000 {:start-ms 10 :end-ms 20} 100]
           :past-tail [8000 8000 {:start-ms 900 :end-ms 5000} 0]
           :empty [0 16000 {:start-ms 0 :end-ms 100} 0]}
   :gen (gen/let [rate (gen/elements [8000 16000]) total (gen/choose 0 32000)
                  a (gen/choose 0 4000) len (gen/choose 0 4000) pad (gen/choose 0 500)]
          [total rate {:start-ms a :end-ms (+ a len)} pad])
   :pred (fn [{[total rate {:keys [start-ms end-ms]} pad] :in out :out}]
           (let [v (:ok out)
                 s (min total (long (Math/floor (* rate (/ (double (max 0 (- start-ms pad))) 1000.0)))))
                 e (max s (min total (long (Math/ceil (* rate (/ (double (+ end-ms pad)) 1000.0))))))]
             (and (m/validate g/WavSlice v)
                  (= (- e s) (:samples v))
                  (= (long (Math/round (* 1000.0 (/ (double s) rate)))) (:offset-ms v))
                  (= (+ 44 (* 2 (:samples v))) (:byte-count v))
                  (= rate (:sample-rate v)))))})

(deftest gate-schemas-reject-ambiguity
  (is (not (m/validate g/UnavailableError {:error :error/asr-failed :hint "x"})))
  (is (not (m/validate g/WavSlice {:offset-ms -1 :samples 0 :sample-rate 16000 :byte-count 44})))
  (is (empty? (set/difference #{:encoder :decoder :tokenizer} (set (keys onnx/default-model-files))))))
