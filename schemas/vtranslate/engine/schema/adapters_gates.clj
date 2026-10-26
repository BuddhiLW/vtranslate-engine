(ns vtranslate.engine.schema.adapters-gates
  "Non-runtime contracts for the adapter capability gates, constructors and
   configuration readers the earlier adapter passes left unspecified. Never
   required by src; the adapters stay malli-free."
  (:require [vtranslate.engine.schema :as s]))

(def ErrorValue
  "Any Result error: a category keyword plus free-form data."
  [:map [:error :keyword]])

(def UnavailableError
  "The router-skippable refusal every capability gate answers with."
  [:map [:error [:= :error/transcriber-unavailable]] [:hint :string]])

(defn gate-result
  "Result of a capability gate: ok `value` or an unavailable refusal."
  [value]
  [:or [:map [:ok value]] UnavailableError])

(def SileroSegmenterValue
  "A built Silero segmenter as plain data: knobs coerced to their numeric types."
  [:map [:model-path :string]
   [:threshold float?] [:neg-threshold float?]
   [:min-speech-ms :int] [:min-silence-ms :int] [:speech-pad-ms :int]
   [:max-span-ms :int] [:merge-to-ms :int]])

(def SileroMakeResult
  [:or [:map [:ok SileroSegmenterValue]]
   [:map [:error [:= :error/segmentation-failed]] [:reason :string]]])

(def NemoTranscriberValue
  [:map [:model :string] [:target-language [:maybe :string]]
   [:span-pad-ms :int] [:python-executable :string]
   [:library-path [:maybe :string]] [:device :string] [:multilingual? :boolean]])

(def Qwen3TranscriberValue
  [:map [:model-key :keyword] [:model-dir [:maybe :string]] [:span-pad-ms :int]])

(def SherpaModelFiles
  [:map [:encoder :string] [:decoder :string] [:tokens :string]])

(def SherpaCfg
  [:map [:encoder :string] [:decoder :string] [:tokens :string]
   [:num-threads :int] [:language :string] [:task :string]
   [:provider :string] [:decoding-method :string]])

(def OnnxModelFiles
  [:map [:encoder :string] [:decoder :string] [:tokenizer :string]])

(def OpenAiTranscriberValue
  [:map [:api-url :string] [:model :string] [:api-key [:maybe :string]]
   [:opts [:map-of :keyword :any]]])

(def OpenAiProviderDefaults
  [:map-of :keyword
   [:map [:api-url :string] [:secret-env :string] [:model :string]
    [:keyless? {:optional true} :boolean]]])

(def WavSlice
  "Observed wav-bytes-slice value with the byte array summarised by length."
  [:map [:offset-ms s/Nat] [:samples s/Nat] [:sample-rate pos-int?] [:byte-count pos-int?]])

(def ResolvedTranslator
  [:map [:provider :keyword] [:api-url [:maybe :string]] [:model [:maybe :string]]
   [:secret-env [:maybe :string]] [:secret-pass [:maybe :string]] [:api-key :string]])

(def TranslatorUnavailable
  [:map [:error [:= :error/translator-unavailable]] [:hint :string]
   [:secret-env [:maybe :string]] [:secret-pass [:maybe :string]]])

(def RequestTimeoutS pos-int?)

(def CacheDir [:re #".+/vtranslate/transcripts$"])

(def CatalogueRoles [:set :keyword])
