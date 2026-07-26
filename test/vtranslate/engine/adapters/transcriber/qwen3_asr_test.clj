(ns vtranslate.engine.adapters.transcriber.qwen3-asr-test
  "Outer-ns coverage with a STUBBED native — runs WITHOUT the :qwen3-asr dep or
   weights: per-span language routing, whole-clip fallback, error propagation,
   and the resolve-time capability gate. The model-backed smoke SKIPs unless the
   dep is on the classpath AND weights are cached locally."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.providers.transcriber-registry :as reg]
            [vtranslate.engine.adapters.transcriber.qwen3-asr :as sut])
  (:import [java.io ByteArrayInputStream File]
           [javax.sound.sampled AudioFileFormat$Type AudioFormat AudioInputStream
                                AudioSystem]))

(deftest language-name-mapping
  (is (= "English" (sut/->language-name "en")))
  (is (= "English" (sut/->language-name "en-US")) "primary subtag of a full BCP-47 tag")
  (is (= "German" (sut/->language-name "DE")) "case-insensitive")
  (is (nil? (sut/->language-name "xx")) "unmapped => nil (model auto-detect)")
  (is (nil? (sut/->language-name nil)))
  (is (nil? (sut/->language-name "  "))))

(deftest transcribe-with-spans-routes-per-span-language
  (let [seen (atom [])
        fake (fn [_model-key _model-dir samples language-name]
               (swap! seen conj [(alength ^floats samples) language-name])
               (r/ok "x"))
        samples (float-array 48000)
        res (#'sut/transcribe-with-spans fake :qwen3-asr-0.6b nil samples 16000 "en"
                                         [{:start-ms 1000 :end-ms 1500 :language "de"}
                                          {:start-ms 2500 :end-ms 3000}] 0)]
    (is (r/ok? res))
    (is (= [[8000 "German"] [8000 "English"]]
           @seen)
        "the span's :language wins; an untagged span falls back to the port language")
    (is (= [{:start-ms 1000 :end-ms 1500 :text "x" :language "de"}
            {:start-ms 2500 :end-ms 3000 :text "x"}]
           (:ok res))
        "one segment per span at the span's bounds; :language preserved when tagged")))

(deftest transcribe-with-spans-skips-empty-ranges
  (let [fake (fn [_ _ samples _] (r/ok (str (alength ^floats samples))))
        samples (float-array 16000)
        res (#'sut/transcribe-with-spans fake :qwen3-asr-0.6b nil samples 16000 nil
                                         [{:start-ms 90000 :end-ms 91000}] 0)]
    (is (r/ok? res))
    (is (= [] (:ok res)) "a span clamped to an empty sample range yields no segment")))

(deftest transcribe-with-spans-falls-back-to-whole-clip
  (let [seen (atom nil)
        fake (fn [_ _ samples language-name]
               (reset! seen [(alength ^floats samples) language-name])
               (r/ok "whole"))
        samples (float-array 32000)
        res (#'sut/transcribe-with-spans fake :qwen3-asr-0.6b nil samples 16000 "fr" nil 0)]
    (is (r/ok? res))
    (is (= [32000 "French"] @seen))
    (is (= [{:start-ms 0 :end-ms 2000 :text "whole"}] (:ok res))
        "one segment spanning the clip duration")))

(deftest transcribe-with-spans-propagates-native-error
  (let [fake (fn [_ _ _ _] (r/err :error/asr-failed {:reason "boom"}))
        res (#'sut/transcribe-with-spans fake :qwen3-asr-0.6b nil (float-array 16000)
                                         16000 "en" [{:start-ms 0 :end-ms 500}] 0)]
    (is (r/err? res))
    (is (= :error/asr-failed (:error res)) "a native decode failure aborts the span loop loud")))

(deftest resolve-gates-on-backend-model-key-and-model-dir
  (if-not (sut/backend-present?)
    (let [res (reg/resolve-transcriber :qwen3-asr {})]
      (is (r/err? res))
      (is (= :error/transcriber-unavailable (:error res))
          "backend jar absent (test classpath) => clean SKIP via the fallback chain"))
    (is true "backend present — absent-probe branch exercised in the default test env"))
  (with-redefs [sut/backend-present? (constantly true)]
    (let [res (reg/resolve-transcriber :qwen3-asr {:transcriber-opts {:model-key :nope}})]
      (is (r/err? res))
      (is (= :nope (:model-key res)) "unknown model key fails loud at resolve"))
    (let [res (reg/resolve-transcriber :qwen3-asr {:transcriber-opts {:model-dir "/no/such/dir"}})]
      (is (r/err? res))
      (is (= :error/transcriber-unavailable (:error res))
          "a configured :model-dir must name an existing directory"))
    (let [res (reg/resolve-transcriber :qwen3-asr {:transcriber-opts {:model-dir "/tmp"}})]
      (is (r/ok? res) "backend + valid model-dir resolves")
      (is (= :qwen3-asr-0.6b (:model-key (:ok res))) "default model key"))))

(defn- sine-wav
  "Write `secs` of a 440 Hz sine as a 16 kHz mono PCM WAV; returns the path."
  ^String [secs]
  (let [sr 16000, n (* sr (long secs))
        bytes (byte-array (* n 2))]
    (dotimes [i n]
      (let [s (short (Math/round (* 12000.0 (Math/sin (/ (* 2.0 Math/PI 440.0 i) sr)))))]
        (aset bytes (* 2 i) (byte (bit-and s 0xff)))
        (aset bytes (inc (* 2 i)) (byte (bit-and (bit-shift-right s 8) 0xff)))))
    (let [fmt (AudioFormat. (float sr) 16 1 true false)
          ais (AudioInputStream. (ByteArrayInputStream. bytes) fmt n)
          f   (File/createTempFile "qwen3-asr-smoke" ".wav")]
      (AudioSystem/write ais AudioFileFormat$Type/WAVE f)
      (.getPath f))))

(deftest model-backed-smoke
  (let [cache (File. (System/getProperty "user.home")
                     ".cache/raster/models/Qwen--Qwen3-ASR-0.6B-hf")]
    (if-not (and (sut/backend-present?) (.isDirectory cache))
      (is true "SKIP: pretrained-rstr dep or cached Qwen3-ASR-0.6B weights absent")
      (let [res (reg/resolve-transcriber :qwen3-asr {})]
        (is (r/ok? res))
        (let [out (r/let-ok [t res]
                    (p.asr/transcribe t (sine-wav 1) "en" {}))]
          (is (r/ok? out))
          (is (vector? (get-in out [:ok :segments]))))))))
