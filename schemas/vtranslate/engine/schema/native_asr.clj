(ns vtranslate.engine.schema.native-asr
  "Non-runtime contracts for the *-native ASR/VAD namespaces as the native suite
   (test-native/) observes them: whisper-jni-native, silero-vad-native and
   onnx-bytedeco-native. Never required by src.

   Native handles (sessions, ORT Values, contexts) never reach a schema; the
   tests project them to shapes, lengths and plain numbers first."
  (:require [vtranslate.engine.schema :as s]
            [vtranslate.engine.schema.port-values :as pv]))

;; --- whisper-jni-native ------------------------------------------------------

(def WhisperLang
  "What lang-code hands whisper.cpp: a lower-case primary subtag or \"auto\",
   never nil."
  [:re #"^(auto|[a-z0-9]+)$"])

(def WhisperRawSegment
  "One raw hypothesis transcribe-samples returns: whisper centiseconds already
   scaled to ms, text unnormalized (leading space kept)."
  [:and [:map [:start-ms s/Nat] [:end-ms s/Nat] [:text :string]]
   [:fn #(<= (:start-ms %) (:end-ms %))]])

(def WhisperRawResult
  [:or [:map [:ok [:and [:vector WhisperRawSegment] [:fn pv/ordered?]]]]
   [:map [:error [:= :error/asr-failed]] [:message :string]]])

(def WhisperWindowObservation
  "A decode of one window of real speech, projected to what is stable across
   runs (whisper.cpp decoding is not bit-exact here)."
  [:map
   [:ok? :boolean]
   [:starts-inside? :boolean]
   [:ordered? :boolean]
   [:mostly-speech? :boolean]])

;; --- silero-vad-native -------------------------------------------------------

(def SpeechProb [:double {:min 0.0 :max 1.0}])

(def SpeechProbs [:vector SpeechProb])

(def SpeechProbsObservation
  "speech-probs over a signal, summarised: window count, peak, how many windows
   cross the default 0.5 threshold, and run-to-run equality."
  [:map
   [:windows s/Nat]
   [:max-prob SpeechProb]
   [:speech-windows s/Nat]
   [:deterministic? :boolean]])

;; --- onnx-bytedeco-native ----------------------------------------------------

(def TensorShape [:vector pos-int?])

(def TensorData
  "tensor-data projected: the float payload as a vector plus its shape."
  [:map [:data [:vector number?]] [:shape TensorShape]])

(def IoNames [:map [:inputs [:vector :string]] [:outputs [:vector :string]]])

(def Tokenizer
  [:map
   [:token->id [:map-of :string :int]]
   [:id->token [:map-of :int :string]]
   [:special [:map-of :string :int]]])

(def TimestampSegment
  [:and [:map [:start number?] [:end number?] [:text [:string {:min 1}]]]
   [:fn #(<= (:start %) (:end %))]])

(def MelSpec
  [:map [:sample-rate [:= 16000]] [:n-fft pos-int?] [:hop pos-int?]
   [:n-mels pos-int?] [:chunk-frames pos-int?] [:window [:= :hann]]])

(def MelObservation
  "log-mel-spectrogram summarised: length, finiteness and value range."
  [:map [:length [:= 240000]] [:finite? :boolean] [:min number?] [:max number?]])
