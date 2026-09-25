(ns vtranslate.engine.adapters.transcriber.openai-compatible-test
  "The OpenAI-compatible ASR adapter is the real, no-native-dep backend. Each
   test talks to a local aleph server that decodes the multipart request, so
   these run offline over real HTTP: they pin the request's fields and audio
   part, the verbose_json -> contract mapping, the text-only spanning fallback,
   fail-loud on an HTTP error, and the resolve-time capability gate
   (key-requiring hosts vs a keyless local server)."
  (:require [clojure.test :refer [deftest is testing]]
            [aleph.http :as http]
            [aleph.http.multipart :as mp]
            [aleph.netty :as netty]
            [cheshire.core :as json]
            [hive-dsl.result :as r]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [vtranslate.engine.contract.ports-contract :as ct]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.providers.transcriber-registry :as reg]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.adapters.transcriber.openai-compatible :as oai]
            [vtranslate.engine.port.model-host :as p.host]
            [vtranslate.engine.residency :as residency]))

(defn- with-tmp-wav [f]
  (let [tmp (java.io.File/createTempFile "vt-asr-" ".wav")]
    (try (spit tmp "RIFFdummy") (f (.getPath tmp))
         (finally (.delete tmp)))))

(defn- with-real-wav
  "Run `f` with the path of a real 16 kHz mono WAV lasting `seconds`."
  [seconds f]
  (let [tmp (java.io.File/createTempFile "vt-asr-real-" ".wav")]
    (try (sup/write-wav-mono! (.getPath tmp) (float-array (* seconds 16000) 0.01) 16000)
         (f (.getPath tmp))
         (finally (.delete tmp)))))

(defn- part-fields
  "Decoded multipart `parts` as {part-name value}: a text field's string, and
   for the file part {:file-name :mime-type :bytes}."
  [parts]
  (into {}
        (map (fn [{:keys [part-name content file? name mime-type]}]
               [part-name
                (if file?
                  {:file-name name :mime-type mime-type
                   :bytes (.readAllBytes ^java.io.InputStream content)}
                  content)]))
        parts))

(defn- with-asr-server
  "Run `f` with the transcription URL of a local aleph server that answers
   every POST with what `reply-fn` returns for the request's form fields (a
   ring response when it carries :status, else a JSON body), and an atom of
   every field map it received."
  [reply-fn f]
  (let [seen    (atom [])
        handler (fn [req]
                  (d/chain (s/reduce conj [] (mp/decode-request req {:memory-limit (* 16 1024 1024)}))
                           (fn [parts]
                             (let [fields (part-fields parts)
                                   reply  (reply-fn fields)]
                               (swap! seen conj fields)
                               (if (:status reply)
                                 reply
                                 {:status  200
                                  :headers {"content-type" "application/json"}
                                  :body    (json/generate-string reply)})))))
        server  (http/start-server handler {:port 0})]
    (try
      (f (str "http://127.0.0.1:" (netty/port server) "/v1/audio/transcriptions") seen)
      (finally (.close ^java.io.Closeable server)))))

(deftest verbose-json-maps-to-contract
  (with-tmp-wav
    (fn [path]
      (with-asr-server (constantly {:segments [{:start 0.0 :end 1.4 :text "Hello"}
                                               {:start 1.4 :end 3.2 :text "world"}]})
        (fn [url _seen]
          (let [impl (oai/->OpenAiTranscriber url "whisper-large-v3" "sk-fake" {})]
            (ct/check-transcriber impl path "en")   ; LSP contract
            (let [segs (:segments (:ok (p.asr/transcribe impl path "en" {})))]
              (is (= [[0 1400] [1400 3200]] (mapv (juxt :start-ms :end-ms) segs))
                  "second-precision timestamps convert to ms"))))))))

(deftest text-only-spans-whole-clip
  (with-real-wav 5
    (fn [path]
      (with-asr-server (constantly {:text "just text"})
        (fn [url _seen]
          (let [segs (:segments (:ok (p.asr/transcribe
                                      (oai/->OpenAiTranscriber url "m" "k" {}) path "en" {})))]
            (is (= [{:start-ms 0 :end-ms 5000}] (mapv #(select-keys % [:start-ms :end-ms]) segs))
                "a segment-less reply becomes one clip-spanning segment")))))))

(deftest transport-error-fails-loud
  (with-tmp-wav
    (fn [path]
      (with-asr-server (constantly {:status 401 :body "unauthorized"})
        (fn [url _seen]
          (is (= :error/asr-failed
                 (:error (p.asr/transcribe (oai/->OpenAiTranscriber url "m" "k" {}) path "en" {})))
              "never a fake transcript on failure"))))))

(deftest capability-gate
  (testing "a key-requiring provider with no key is unavailable at resolve time"
    (is (= :error/transcriber-unavailable (:error (reg/resolve-transcriber :openai-whisper {}))))
    (is (= :error/transcriber-unavailable (:error (reg/resolve-transcriber :groq {})))))
  (testing "a key provided via config makes it resolvable"
    (is (r/ok? (reg/resolve-transcriber :groq {:transcriber-opts {:secret-env "PATH"}}))
        "PATH is always set -> resolve-key finds a value -> resolvable"))
  (testing "the keyless local server is always resolvable"
    (is (r/ok? (reg/resolve-transcriber :whisper-server {})))))

(deftest the-request-carries-every-field-and-the-audio
  (with-tmp-wav
    (fn [path]
      (with-asr-server (constantly {:text "ok"})
        (fn [url seen]
          (p.asr/transcribe (oai/->OpenAiTranscriber url "whisper-1" nil {}) path "en" {})
          (let [fields (first @seen)]
            (is (= {"model" "whisper-1" "response_format" "verbose_json" "language" "en"
                    ;; the field repeats (segment, word); the map keeps the last
                    "timestamp_granularities[]" "word"}
                   (dissoc fields "file")))
            (is (= {:file-name "audio.wav" :mime-type "audio/wav"}
                   (dissoc (get fields "file") :bytes)))
            (is (= "RIFFdummy" (String. ^bytes (:bytes (get fields "file")) "UTF-8")))))))))

(deftest a-window-long-reply-is-recut-by-its-words
  (with-real-wav 25
    (fn [path]
      (with-asr-server (constantly {:segments [{:start 0.0 :end 24.0 :text "one two. three four"}]
                                    :words    [{:start 0.0 :end 1.0 :word " one"}
                                               {:start 1.0 :end 2.0 :word " two."}
                                               {:start 12.0 :end 13.0 :word " three"}
                                               {:start 13.0 :end 14.0 :word " four"}]})
        (fn [url _seen]
          (let [cues-for #(:segments (:ok (p.asr/transcribe (oai/->OpenAiTranscriber url "m" "k" %)
                                                            path "ar" {})))]
            (is (= [[0 2000] [12000 14000]] (mapv (juxt :start-ms :end-ms) (cues-for {})))
                "the decode window's 24 s never reaches a cue")
            (is (= [[0 24000]] (mapv (juxt :start-ms :end-ms) (cues-for {:cues false})))
                ":cues false keeps the server's own timing")))))))

(deftest cues-false-asks-for-no-word-timestamps
  (with-tmp-wav
    (fn [path]
      (with-asr-server (constantly {:text "ok"})
        (fn [url seen]
          (p.asr/transcribe (oai/->OpenAiTranscriber url "m" nil {:cues false}) path "en" {})
          (is (not (contains? (first @seen) "timestamp_granularities[]"))))))))

;; --- wire-level test for multipart body quoting ---------------------------

;; --- default: grid spans still post the whole clip ------------------------

(deftest grid-spans-still-post-the-whole-clip-by-default
  (with-tmp-wav
    (fn [path]
      (with-asr-server (constantly {:segments [{:start 0.0 :end 1.0 :text "hello"}]})
        (fn [url seen]
          (p.asr/transcribe (oai/->OpenAiTranscriber url "m" "k" {})
                            path "en"
                            {:spans [{:start-ms 0 :end-ms 5000}
                                     {:start-ms 5000 :end-ms 10000}]})
          (is (= 1 (count @seen))
              "grid spans alone (no :slice-spans?) -> exactly one POST for the whole clip"))))))

;; --- slicing happens only when asked ---------------------------------------

(deftest slicing-happens-only-when-asked
  (with-real-wav 2
    (fn [path]
      (with-asr-server (constantly {:segments [{:start 0.0 :end 0.5 :text "test"}]})
        (fn [url seen]
          (let [result (p.asr/transcribe
                        (oai/->OpenAiTranscriber url "m" "k"
                                                 {:slice-spans? true :span-pad-ms 0})
                        path "en"
                        {:spans [{:start-ms 0 :end-ms 1000}
                                 {:start-ms 1000 :end-ms 2000}]})]
            (is (= 2 (count @seen)) "two spans with :slice-spans? -> TWO posts")
            (is (some #(<= 1000 (:start-ms %)) (:segments (:ok result)))
                "the second span's segments are shifted into absolute clip time (>= 1000 ms)")))))))

;; --- only configured knobs are sent ----------------------------------------

(deftest unconfigured-knobs-are-not-sent
  (let [fields-for (fn [call-opts]
                     (with-tmp-wav
                       (fn [path]
                         (with-asr-server (constantly {:text "ok"})
                           (fn [url seen]
                             (p.asr/transcribe (oai/->OpenAiTranscriber url "m" "k" {})
                                               path "en" call-opts)
                             (first @seen))))))]
    (let [fields (fields-for {})]
      (is (not (contains? fields "temperature")) "empty opts -> no temperature sent")
      (is (not (contains? fields "prompt")) "empty opts -> no prompt sent"))
    (let [fields (fields-for {:temperature 0.2 :prompt "names: Ada"})]
      (is (= "0.20" (get fields "temperature")) "configured temperature -> sent as \"0.20\"")
      (is (= "names: Ada" (get fields "prompt")) "configured prompt -> sent"))
    (let [prev (java.util.Locale/getDefault)]
      (try
        (java.util.Locale/setDefault (java.util.Locale. "pt" "BR"))
        (is (= "0.20" (get (fields-for {:temperature 0.2}) "temperature"))
            "pt-BR locale still sends \"0.20\" not \"0,20\"")
        (finally (java.util.Locale/setDefault prev))))
    (let [fields (fields-for {:temperature nil :prompt nil})]
      (is (not (contains? fields "temperature")) "nil temperature -> no temperature sent")
      (is (not (contains? fields "prompt")) "nil prompt -> no prompt sent"))))

;; --- the :asr/clean hook sees each reply's raw segments ----------------------

(deftest the-clean-hook-sees-raw-segments-with-their-metrics
  (with-tmp-wav
    (fn [path]
      (with-asr-server (constantly {:segments [{:start 0.0 :end 1.0 :text "thank you" :compression_ratio 3.1}
                                               {:start 1.0 :end 2.0 :text "Thank you."}
                                               {:start 2.0 :end 3.0 :text "thank you!"}]})
        (fn [url _seen]
          (let [seen   (atom nil)
                clean  (fn [segs _opts]
                         (reset! seen segs)
                         [(assoc (first segs) :end (:end (last segs)))])
                result (p.asr/transcribe
                        (oai/->OpenAiTranscriber url "m" "k" {}) path "en"
                        {:asr/clean clean})
                segs   (:segments (:ok result))]
            (is (= 3.1 (:compression_ratio (first @seen))) "server metrics are still attached")
            (is (= 1 (count segs)) "what the hook returns is what is normalised")
            (is (= 3000 (:end-ms (first segs))))))))))

(deftest no-clean-hook-keeps-every-segment
  (with-tmp-wav
    (fn [path]
      (with-asr-server (constantly {:segments [{:start 0.0 :end 1.0 :text "thank you"}
                                               {:start 1.0 :end 2.0 :text "Thank you."}
                                               {:start 2.0 :end 3.0 :text "thank you!"}]})
        (fn [url _seen]
          (let [result (p.asr/transcribe
                        (oai/->OpenAiTranscriber url "m" "k" {}) path "en" {})]
            (is (= 3 (count (:segments (:ok result)))))))))))

;; --- the :asr/route-window hook, against a real HTTP stub ---------------------

(defn- echo-reply
  "A verbose_json reply whose one segment names the model and language it was
   asked for, detected as `heard`."
  ([fields] (echo-reply "he" fields))
  ([heard fields]
   {:language heard
    :segments [{:start 0.0 :end 1.0
                :text (str (get fields "model") "/" (get fields "language" "auto"))}]}))

(deftest the-route-window-hook-is-honoured
  (is (contains? (p.asr/honoured (oai/->OpenAiTranscriber "http://x" "m" nil {}))
                 :asr/route-window)))

(deftest a-route-may-name-another-server-model-per-window
  (with-tmp-wav
    (fn [path]
      (with-asr-server echo-reply
        (fn [url seen]
          (let [impl  (oai/->OpenAiTranscriber url "large-v3" nil {})
                route (fn [decode _language] (decode "he" {:model "ivrit-turbo"}))
                segs  (:segments (:ok (p.asr/transcribe impl path "he" {:asr/route-window route})))]
            (is (= ["ivrit-turbo/he"] (mapv :text segs)) "the routed model and language are sent")
            (is (= "ivrit-turbo" (get (first @seen) "model")))))))))

(deftest a-route-may-send-a-window-to-another-server
  (with-tmp-wav
    (fn [path]
      (with-asr-server echo-reply
        (fn [own-url own-seen]
          (with-asr-server echo-reply
            (fn [other-url other-seen]
              (let [impl  (oai/->OpenAiTranscriber own-url "large-v3" "k" {})
                    route (fn [decode _language]
                            (decode "ar" {:model "cohere-ar" :api-url other-url}))
                    segs  (:segments (:ok (p.asr/transcribe impl path "ar" {:asr/route-window route})))]
                (is (= ["cohere-ar/ar"] (mapv :text segs)))
                (is (= 1 (count @other-seen)) "the routed server decodes the window")
                (is (empty? @own-seen) "the adapter's own server is not asked")))))))))

(defn- recording-host
  "An IModelHost holding `loaded`, recording [op id] for every load and unload."
  [loaded calls]
  (let [held (atom (set loaded))]
    (reify p.host/IModelHost
      (loaded [_] (r/ok @held))
      (load! [_ id] (swap! calls conj [:load id]) (swap! held conj id) (r/ok id))
      (unload! [_ id] (swap! calls conj [:unload id]) (swap! held disj id) (r/ok :unloaded)))))

(deftest a-routed-window-first-makes-its-model-resident
  (with-tmp-wav
    (fn [path]
      (with-asr-server echo-reply
        (fn [url seen]
          (let [calls (atom [])
                owner (residency/owner (recording-host #{"large-v3" "ivrit"} calls)
                                       {:budget-mib 2500 :default-model-mib 1000})
                impl  (oai/->OpenAiTranscriber url "large-v3" nil {:residency-owner owner})
                route (fn [decode _language] (decode "ar" {:model "arabic"}))]
            (is (= ["arabic/ar"]
                   (mapv :text (:segments (:ok (p.asr/transcribe impl path "ar" {:asr/route-window route}))))))
            (is (= [[:unload "ivrit"] [:load "arabic"]] @calls)
                "the model nothing has used makes room before the POST")
            (is (= 1 (count @seen)))))))))

(deftest a-window-for-another-server-leaves-this-servers-models-alone
  (with-tmp-wav
    (fn [path]
      (with-asr-server echo-reply
        (fn [url _seen]
          (let [calls (atom [])
                owner (residency/owner (recording-host #{"large-v3"} calls)
                                       {:budget-mib 1500 :default-model-mib 1000})
                impl  (oai/->OpenAiTranscriber "http://127.0.0.1:9/nowhere" "large-v3" nil
                                               {:residency-owner owner})
                route (fn [decode _language] (decode "ar" {:model "cohere-ar" :api-url url}))]
            (is (r/ok? (p.asr/transcribe impl path "ar" {:asr/route-window route})))
            (is (= [] @calls))))))))

(deftest without-a-route-the-adapter-model-is-sent
  (with-tmp-wav
    (fn [path]
      (with-asr-server echo-reply
        (fn [url _seen]
          (let [impl (oai/->OpenAiTranscriber url "large-v3" nil {})]
            (is (= ["large-v3/en"]
                   (mapv :text (:segments (:ok (p.asr/transcribe impl path "en" {}))))))))))))

(deftest an-http-decode-offers-no-window-metadata
  (with-tmp-wav
    (fn [path]
      (with-asr-server echo-reply
        (fn [url _seen]
          (let [window-meta (atom ::unset)
                route       (fn [decode language]
                              (reset! window-meta (:asr/window-ms (meta decode)))
                              (decode language))]
            (p.asr/transcribe (oai/->OpenAiTranscriber url "m" nil {}) path "en"
                              {:asr/route-window route})
            (is (nil? @window-meta)
                "no :asr/window-ms, so a route that widens or treats a window leaves it alone")))))))

(deftest a-sliced-window-hands-the-route-its-span
  (let [tmp  (java.io.File/createTempFile "vt-asr-span-" ".wav")
        path (.getPath tmp)]
    (try
      (sup/write-wav-mono! path (float-array 16000 0.01) 16000)
      (with-asr-server echo-reply
        (fn [url _seen]
          (let [spans [{:start-ms 0 :end-ms 400 :regime :speech}
                       {:start-ms 400 :end-ms 800 :regime :music-bed}]
                heard (atom [])
                route (fn [decode language]
                        (swap! heard conj (:asr/span (meta decode)))
                        (decode language))]
            (p.asr/transcribe (oai/->OpenAiTranscriber url "m" nil {:slice-spans? true :span-pad-ms 0})
                              path "en" {:spans spans :asr/route-window route})
            (is (= spans @heard)
                "each window's route sees the span it decodes, with the keys the segmenter put on it"))))
      (finally (.delete tmp)))))

(deftest the-detected-language-rides-on-segments-only-when-none-was-named
  (with-tmp-wav
    (fn [path]
      (with-asr-server (partial echo-reply "he")
        (fn [url _seen]
          (let [impl (oai/->OpenAiTranscriber url "m" nil {})]
            (is (= ["he"] (mapv :language (:segments (:ok (p.asr/transcribe impl path "" {})))))
                "a detecting request keeps what the server heard")
            (is (= [nil] (mapv :language (:segments (:ok (p.asr/transcribe impl path "en" {})))))
                "a named language is the caller's, not overwritten per segment"))))))
  (with-tmp-wav
    (fn [path]
      (with-asr-server (partial echo-reply "hebrew")
        (fn [url _seen]
          (is (= [nil] (mapv :language (:segments (:ok (p.asr/transcribe
                                                        (oai/->OpenAiTranscriber url "m" nil {})
                                                        path "" {})))))
              "a language NAME is not a code, so it is not attached"))))))
