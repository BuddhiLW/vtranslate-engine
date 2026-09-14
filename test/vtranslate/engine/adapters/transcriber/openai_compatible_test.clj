(ns vtranslate.engine.adapters.transcriber.openai-compatible-test
  "The OpenAI-compatible ASR adapter is the real, no-native-dep backend. HTTP is
   mocked (with-redefs on the private post fn) so these run offline: they pin the
   verbose_json -> contract mapping, the text-only spanning fallback, fail-loud on
   transport error, and the resolve-time capability gate (key-requiring hosts vs a
   keyless local server)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.contract.ports-contract :as ct]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.providers.transcriber-registry :as reg]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.calc.asr-hygiene :as ah]
            [vtranslate.engine.adapters.transcriber.openai-compatible :as oai]))

(defn- with-tmp-wav [f]
  (let [tmp (java.io.File/createTempFile "vt-asr-" ".wav")]
    (try (spit tmp "RIFFdummy") (f (.getPath tmp))
         (finally (.delete tmp)))))

(deftest verbose-json-maps-to-contract
  (with-tmp-wav
    (fn [path]
      (with-redefs [oai/post-multipart
                    (fn [_ _ _] (r/ok {:segments [{:start 0.0 :end 1.4 :text "Hello"}
                                                  {:start 1.4 :end 3.2 :text "world"}]}))]
        (let [impl (oai/->OpenAiTranscriber "http://mock" "whisper-large-v3" "sk-fake" {})]
          (ct/check-transcriber impl path "en")   ; LSP contract
          (let [segs (:segments (:ok (p.asr/transcribe impl path "en" {})))]
            (is (= [[0 1400] [1400 3200]] (mapv (juxt :start-ms :end-ms) segs))
                "second-precision timestamps convert to ms")))))))

(deftest text-only-spans-whole-clip
  (with-tmp-wav
    (fn [path]
      (with-redefs [oai/post-multipart (fn [_ _ _] (r/ok {:text "just text"}))
                    sup/wav-duration-ms (constantly 5000)]
        (let [segs (:segments (:ok (p.asr/transcribe
                                    (oai/->OpenAiTranscriber "http://mock" "m" "k" {}) path "en" {})))]
          (is (= [{:start-ms 0 :end-ms 5000}] (mapv #(select-keys % [:start-ms :end-ms]) segs))
              "a segment-less reply becomes one clip-spanning segment"))))))

(deftest transport-error-fails-loud
  (with-tmp-wav
    (fn [path]
      (with-redefs [oai/post-multipart (fn [_ _ _] (r/err :error/asr-failed {:reason "HTTP 401"}))]
        (is (= :error/asr-failed
               (:error (p.asr/transcribe (oai/->OpenAiTranscriber "http://mock" "m" "k" {}) path "en" {})))
            "never a fake transcript on failure")))))

(deftest capability-gate
  (testing "a key-requiring provider with no key is unavailable at resolve time"
    (is (= :error/transcriber-unavailable (:error (reg/resolve-transcriber :openai-whisper {}))))
    (is (= :error/transcriber-unavailable (:error (reg/resolve-transcriber :groq {})))))
  (testing "a key provided via config makes it resolvable"
    (is (r/ok? (reg/resolve-transcriber :groq {:transcriber-opts {:secret-env "PATH"}}))
        "PATH is always set -> resolve-key finds a value -> resolvable"))
  (testing "the keyless local server is always resolvable"
    (is (r/ok? (reg/resolve-transcriber :whisper-server {})))))

;; --- wire-level test for multipart body quoting ---------------------------

(deftest multipart-body-names-every-field
  (let [{:keys [body content-type]}
        (oai/multipart {"model" "whisper-1"
                        "response_format" "verbose_json"
                        "language" "en"}
                       {:name "file" :filename "audio.wav"
                        :bytes (.getBytes "RIFFdata" "UTF-8")})
        body-str (String. ^bytes body "ISO-8859-1")
        boundary (second (re-find #"boundary=(.+)$" content-type))]
    ;; The body contains correctly-quoted Content-Disposition lines
    (is (str/includes? body-str "Content-Disposition: form-data; name=\"model\"\r\n"))
    (is (str/includes? body-str "Content-Disposition: form-data; name=\"response_format\"\r\n"))
    (is (str/includes? body-str "Content-Disposition: form-data; name=\"language\"\r\n"))
    (is (str/includes? body-str "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"))
    ;; Content-Type for the file part
    (is (str/includes? body-str "Content-Type: audio/wav"))
    ;; The raw audio data is present
    (is (str/includes? body-str "RIFFdata"))
    ;; content-type starts with the right prefix
    (is (.startsWith content-type "multipart/form-data; boundary="))
    ;; body ends with the closing boundary
    (is (.endsWith body-str (str "--" boundary "--\r\n")))
    ;; The broken unquoted form is NOT present
    (is (not (str/includes? body-str "name= ")))))

;; --- default: grid spans still post the whole clip ------------------------

(deftest grid-spans-still-post-the-whole-clip-by-default
  (with-tmp-wav
    (fn [path]
      (let [posts (atom [])]
        (with-redefs [oai/post-multipart
                      (fn [_ _ _] (swap! posts conj :post)
                        (r/ok {:segments [{:start 0.0 :end 1.0 :text "hello"}]}))]
          (p.asr/transcribe (oai/->OpenAiTranscriber "http://mock" "m" "k" {})
                            path "en"
                            {:spans [{:start-ms 0 :end-ms 5000}
                                     {:start-ms 5000 :end-ms 10000}]})
          (is (= 1 (count @posts))
              "grid spans alone (no :slice-spans?) -> exactly one POST for the whole clip"))))))

;; --- slicing happens only when asked ---------------------------------------

(deftest slicing-happens-only-when-asked
  (let [tmp (java.io.File/createTempFile "vt-asr-slice-" ".wav")
        path (.getPath tmp)
        n-samples (* 2 16000)             ; 2 seconds at 16 kHz
        samples (float-array n-samples 0.01)]
    (try
      (sup/write-wav-mono! path samples 16000)
      (let [posts (atom [])]
        (with-redefs [oai/post-multipart
                      (fn [_ _ _] (swap! posts conj :post)
                        (r/ok {:segments [{:start 0.0 :end 0.5 :text "test"}]}))]
          (let [result (p.asr/transcribe
                        (oai/->OpenAiTranscriber "http://mock" "m" "k"
                                                 {:slice-spans? true :span-pad-ms 0})
                        path "en"
                        {:spans [{:start-ms 0 :end-ms 1000}
                                 {:start-ms 1000 :end-ms 2000}]})]
            (is (= 2 (count @posts)) "two spans with :slice-spans? -> TWO posts")
            (let [segs (:segments (:ok result))]
              (is (some #(<= 1000 (:start-ms %)) segs)
                  "the second span's segments are shifted into absolute clip time (>= 1000 ms)")))))
      (finally (.delete tmp)))))

;; --- only configured knobs are sent ----------------------------------------

(deftest unconfigured-knobs-are-not-sent
  (let [recorded-calls (atom [])
        orig-multipart oai/multipart]
    ;; Test 1: empty opts — no temperature, no prompt
    (with-redefs [oai/multipart
                  (fn [fields file]
                    (swap! recorded-calls conj fields)
                    (orig-multipart fields file))]
      (let [tmp (java.io.File/createTempFile "vt-asr-knob-" ".wav")]
        (try
          (spit tmp "RIFFdummy")
          (with-redefs [oai/post-multipart (fn [_ _ _] (r/ok {:text "ok"}))
                        sup/wav-duration-ms (constantly 1000)]
            (p.asr/transcribe (oai/->OpenAiTranscriber "http://mock" "m" "k" {})
                              (.getPath tmp) "en" {}))
          (finally (.delete tmp))))
      (let [fields (first @recorded-calls)]
        (is (not (contains? fields "temperature")) "empty opts -> no temperature sent")
        (is (not (contains? fields "prompt")) "empty opts -> no prompt sent"))
      ;; Test 2: configured temperature and prompt
      (reset! recorded-calls [])
      (let [tmp (java.io.File/createTempFile "vt-asr-knob-" ".wav")]
        (try
          (spit tmp "RIFFdummy")
          (with-redefs [oai/post-multipart (fn [_ _ _] (r/ok {:text "ok"}))
                        sup/wav-duration-ms (constantly 1000)]
            (p.asr/transcribe (oai/->OpenAiTranscriber "http://mock" "m" "k" {})
                              (.getPath tmp) "en"
                              {:temperature 0.2 :prompt "names: Ada"}))
          (finally (.delete tmp))))
      (let [fields (first @recorded-calls)]
        (is (= "0.20" (get fields "temperature")) "configured temperature -> sent as \"0.20\"")
        (is (= "names: Ada" (get fields "prompt")) "configured prompt -> sent"))
      ;; Test 3: Locale pt-BR must not produce "0,20"
      (let [prev (java.util.Locale/getDefault)]
        (try
          (java.util.Locale/setDefault (java.util.Locale. "pt" "BR"))
          (reset! recorded-calls [])
          (let [tmp (java.io.File/createTempFile "vt-asr-knob-ptbr-" ".wav")]
            (try
              (spit tmp "RIFFdummy")
              (with-redefs [oai/post-multipart (fn [_ _ _] (r/ok {:text "ok"}))
                            sup/wav-duration-ms (constantly 1000)]
                (p.asr/transcribe (oai/->OpenAiTranscriber "http://mock" "m" "k" {})
                                  (.getPath tmp) "en"
                                  {:temperature 0.2}))
              (finally (.delete tmp))))
          (let [fields (first @recorded-calls)]
            (is (= "0.20" (get fields "temperature"))
                "pt-BR locale still sends \"0.20\" not \"0,20\""))
          (finally (java.util.Locale/setDefault prev)))))

      ;; Test 4: nil temperature and nil prompt -- must not send either, must not throw
      (reset! recorded-calls [])
      (let [tmp (java.io.File/createTempFile "vt-asr-knob-nil-" ".wav")]
        (try
          (spit tmp "RIFFdummy")
          (with-redefs [oai/post-multipart (fn [_ _ _] (r/ok {:text "ok"}))
                        sup/wav-duration-ms (constantly 1000)]
            (p.asr/transcribe (oai/->OpenAiTranscriber "http://mock" "m" "k" {})
                              (.getPath tmp) "en"
                              {:temperature nil :prompt nil}))
          (finally (.delete tmp))))
      (let [fields (first @recorded-calls)]
        (is (not (contains? fields "temperature")) "nil temperature -> no temperature sent")
        (is (not (contains? fields "prompt")) "nil prompt -> no prompt sent"))))

;; --- nil thresholds do not throw --------------------------------------------

(deftest nil-thresholds-do-not-throw
  (is (= [{:start 0.0 :end 1.0 :text "x"}]
         (ah/clean [{:start 0.0 :end 1.0 :text "x"}]
                   {:min-repeats nil :compression-ratio-thr nil}))
      "explicit nil thresholds fall back to defaults, pass-through unchanged"))

;; --- three repeated segments are collapsed into one -------------------------

(deftest three-repeated-segments-come-back-as-one
  (with-tmp-wav
    (fn [path]
      (with-redefs [oai/post-multipart
                    (fn [_ _ _] (r/ok {:segments [{:start 0.0 :end 1.0 :text "thank you"}
                                                  {:start 1.0 :end 2.0 :text "Thank you."}
                                                  {:start 2.0 :end 3.0 :text "thank you!"}]}))]
        (let [result (p.asr/transcribe
                      (oai/->OpenAiTranscriber "http://mock" "m" "k" {}) path "en" {})
              segs (:segments (:ok result))]
          (is (= 1 (count segs)) "three repeated segments collapse into one")
          (is (= 0 (:start-ms (first segs))) "collapsed start is first segment's start")
          (is (= 3000 (:end-ms (first segs))) "collapsed end is last segment's end"))))))
