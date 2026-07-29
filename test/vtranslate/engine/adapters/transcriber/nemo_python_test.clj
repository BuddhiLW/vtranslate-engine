(ns vtranslate.engine.adapters.transcriber.nemo-python-test
  "Outer-ns coverage with a STUBBED native — runs WITHOUT the :nemo dep, a Python
   env, or NeMo weights: per-span language routing, the AST (target-language)
   path that makes Canary different from every other transcriber in the family,
   whole-clip fallback, error propagation, and the resolve-time capability gate.
   The model-backed smoke SKIPs unless libpython-clj AND a usable Python are
   present."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.providers.transcriber-registry :as reg]
            [vtranslate.engine.adapters.transcriber.nemo-python :as sut])
  (:import [java.io File]))

(deftest language-code-mapping
  (is (= "en" (sut/->lang-code "en")))
  (is (= "en" (sut/->lang-code "en-US")) "primary subtag of a full BCP-47 tag")
  (is (= "de" (sut/->lang-code "DE")) "case-insensitive")
  (is (nil? (sut/->lang-code nil)))
  (is (nil? (sut/->lang-code "  "))))

;; The stub stands in for the native decode: it records what each call was asked
;; to do, so routing can be asserted without an interpreter.
(defn- recording-stub [seen]
  (fn [model wav-path source-lang target-lang]
    (swap! seen conj {:model model
                      :source source-lang
                      :target target-lang
                      ;; decoded, not the raw byte count — a WAV carries a header
                      :frames (alength ^floats (:samples (:ok (sup/read-wav-mono-floats wav-path))))})
    (r/ok (str "text:" source-lang "->" target-lang))))

(deftest routes-each-span-with-its-own-source-language
  (let [seen (atom [])
        res  (#'sut/transcribe-with-spans (recording-stub seen) "nvidia/canary-1b-flash"
                                          (float-array 48000) 16000 "en" nil
                                          [{:start-ms 1000 :end-ms 1500 :language "de"}
                                           {:start-ms 2500 :end-ms 3000}] 0)]
    (is (r/ok? res))
    (is (= ["de" "en"] (mapv :source @seen))
        "the span's :language wins; an untagged span falls back to the port language")
    (is (= [nil nil] (mapv :target @seen))
        "no target language => transcription, not translation")
    (is (= [{:start-ms 1000 :end-ms 1500 :text "text:de->" :language "de"}
            {:start-ms 2500 :end-ms 3000 :text "text:en->"}]
           (:ok res))
        "one segment per span at the span's bounds; :language preserved when tagged")))

(deftest a-target-language-turns-every-span-into-a-translation
  (let [seen (atom [])
        res  (#'sut/transcribe-with-spans (recording-stub seen) "nvidia/canary-1b-flash"
                                          (float-array 48000) 16000 "en" "de-DE"
                                          [{:start-ms 0 :end-ms 500}
                                           {:start-ms 1000 :end-ms 1500 :language "fr"}] 0)]
    (is (r/ok? res))
    (is (= ["en" "fr"] (mapv :source @seen)) "per-span source routing still applies")
    (is (= ["de" "de"] (mapv :target @seen))
        "the target language is constant across spans and reduced to ISO-639-1")))

(deftest each-span-is-decoded-from-its-own-audio-slice
  (let [seen (atom [])
        res  (#'sut/transcribe-with-spans (recording-stub seen) "m"
                                          (float-array 48000) 16000 "en" nil
                                          [{:start-ms 0 :end-ms 1000}
                                           {:start-ms 1000 :end-ms 3000}] 0)]
    (is (r/ok? res))
    (is (= [16000 32000] (mapv :frames @seen))
        "the scratch WAV handed to the backend holds exactly the span's samples")))

(deftest skips-spans-that-clamp-to-an-empty-range
  (let [res (#'sut/transcribe-with-spans (recording-stub (atom [])) "m"
                                         (float-array 16000) 16000 nil nil
                                         [{:start-ms 90000 :end-ms 91000}] 0)]
    (is (r/ok? res))
    (is (= [] (:ok res)) "a span past the end of the audio yields no segment")))

(deftest falls-back-to-one-whole-clip-call
  (let [seen (atom [])
        res  (#'sut/transcribe-with-spans (recording-stub seen) "m"
                                          (float-array 32000) 16000 "fr" nil nil 0)]
    (is (r/ok? res))
    (is (= [{:start-ms 0 :end-ms 2000 :text "text:fr->"}] (:ok res))
        "one segment spanning the clip duration")
    (is (= [32000] (mapv :frames @seen)))))

(deftest propagates-a-native-decode-failure
  (let [res (#'sut/transcribe-with-spans (fn [_ _ _ _] (r/err :error/asr-failed {:reason "boom"}))
                                         "m" (float-array 16000) 16000 "en" nil
                                         [{:start-ms 0 :end-ms 500}] 0)]
    (is (r/err? res))
    (is (= :error/asr-failed (:error res)) "a decode failure aborts the span loop loud")))

(deftest scratch-wav-files-do-not-accumulate
  (let [captured (atom nil)]
    (#'sut/transcribe-with-spans (fn [_ path _ _] (reset! captured path) (r/ok "x"))
                                 "m" (float-array 16000) 16000 "en" nil
                                 [{:start-ms 0 :end-ms 500}] 0)
    (is (some? @captured))
    (is (not (.exists (File. ^String @captured)))
        "the per-span scratch WAV is deleted once the backend has read it")))

(deftest resolve-gates-on-backend-python-and-model
  (if-not (sut/backend-present?)
    (let [res (reg/resolve-transcriber :canary {})]
      (is (r/err? res))
      (is (= :error/transcriber-unavailable (:error res))
          "libpython-clj absent (test classpath) => clean SKIP via the fallback chain"))
    (is true "backend present — absent-probe branch exercised in the default test env"))

  (with-redefs [sut/backend-present? (constantly true)]
    (testing "the interpreter must actually exist"
      (let [res (reg/resolve-transcriber
                 :canary {:transcriber-opts {:python-executable "/no/such/python"}})]
        (is (r/err? res))
        (is (= :error/transcriber-unavailable (:error res)))))

    (testing "an ASR-only model refuses a translation request instead of silently transcribing"
      (let [res (reg/resolve-transcriber
                 :parakeet {:transcriber-opts {:python-executable "/bin/sh"
                                               :target-language "de"}})]
        (is (r/err? res))
        (is (= :error/transcriber-unavailable (:error res)))))

    (testing "a usable interpreter resolves each provider to its own model"
      (let [canary (reg/resolve-transcriber
                    :canary {:transcriber-opts {:python-executable "/bin/sh"}})
            parakeet (reg/resolve-transcriber
                      :parakeet {:transcriber-opts {:python-executable "/bin/sh"}})]
        (is (r/ok? canary))
        (is (= "nvidia/canary-1b-flash" (:model (:ok canary))))
        (is (r/ok? parakeet))
        (is (= "nvidia/parakeet-tdt-0.6b-v2" (:model (:ok parakeet))))))

    (testing "an explicit model name overrides the provider default"
      (let [res (reg/resolve-transcriber
                 :canary {:transcriber-opts {:python-executable "/bin/sh"
                                             :model-name "nvidia/canary-180m-flash"}})]
        (is (r/ok? res))
        (is (= "nvidia/canary-180m-flash" (:model (:ok res))))))))

(deftest derives-the-libpython-beside-the-interpreter
  (testing "an env layout <env>/bin/python resolves to <env>/lib/libpython3.X.so"
    (let [env (doto (File. (System/getProperty "java.io.tmpdir")
                           (str "nemo-env-" (System/nanoTime)))
                (.mkdirs))
          bin (doto (File. env "bin") .mkdirs)
          lib (doto (File. env "lib") .mkdirs)]
      (try
        (.createNewFile (File. bin "python"))
        (.createNewFile (File. lib "libpython3.12.so"))
        (.createNewFile (File. lib "libpython3.9.so"))
        (is (= (.getPath (File. lib "libpython3.12.so"))
               (sut/derive-library-path (.getPath (File. bin "python"))))
            "the newest minor version wins")
        (finally
          (doseq [f (reverse (file-seq env))] (.delete ^File f))))))
  (testing "nil rather than a guess when there is nothing to derive from"
    (is (nil? (sut/derive-library-path nil)))
    (is (nil? (sut/derive-library-path "/no/such/bin/python")))))

;; --- model-backed smoke -----------------------------------------------------

(deftest model-backed-smoke
  (let [python (System/getenv "VT_NEMO_PYTHON")]
    (if-not (and (sut/backend-present?) python (.canExecute (File. ^String python)))
      (is true "SKIP: set VT_NEMO_PYTHON to a Python with nemo_toolkit[asr] installed")
      (let [res (reg/resolve-transcriber
                 :parakeet {:transcriber-opts {:python-executable python}})]
        (is (r/ok? res))
        (is (= "cpu" (:device (:ok res)))
            "CPU by default — a shared GPU is routinely too full to load onto")))))

;; --- the WAV round-trip this adapter leans on -------------------------------

(deftest scratch-wav-round-trips-through-the-support-helpers
  (let [samples (float-array (map #(Math/sin (/ (* 2.0 Math/PI 440.0 %) 16000.0))
                                  (range 16000)))
        file    (doto (File/createTempFile "nemo-roundtrip" ".wav") .deleteOnExit)]
    (try
      (is (r/ok? (sup/write-wav-mono! (.getPath file) samples 16000)))
      (let [read-back (sup/read-wav-mono-floats (.getPath file))]
        (is (r/ok? read-back))
        (is (= 16000 (alength ^floats (:samples (:ok read-back)))))
        (is (= 16000 (:sample-rate (:ok read-back))))
        (testing "16-bit quantization is the only loss"
          (is (every? #(< (Math/abs (- (aget ^floats samples %)
                                       (aget ^floats (:samples (:ok read-back)) %)))
                          1e-3)
                      (range 0 16000 97)))))
      (finally (.delete file)))))
