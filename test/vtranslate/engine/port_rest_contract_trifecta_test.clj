(ns vtranslate.engine.port-rest-contract-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-rest-contract :as c]
            [vtranslate.engine.port.native-call :as n]
            [vtranslate.engine.port.composer :as co]
            [vtranslate.engine.port.model-host :as h]
            [vtranslate.engine.port.coverage :as cv]
            [vtranslate.engine.port.media :as me]
            [vtranslate.engine.port.source :as s]
            [vtranslate.engine.port.transcript-cache :as ca]
            [vtranslate.engine.port.segmenter :as sg]
            [vtranslate.engine.port.burner :as b]
            [vtranslate.engine.port.fetcher :as f]
            [vtranslate.engine.port.subtitle :as su]))

(defn protocol-observation [x]
  (let [spans [{:start-ms 0 :end-ms (count x)}]
        native (reify n/INativeCall (call-native [_ thunk] (thunk))
                 (describe [_] {:strategy :stub}))
        host (reify h/IModelHost (loaded [_] {:ok #{x}})
               (load! [_ id] {:ok id}) (unload! [_ _] {:ok :absent}))
        cache (reify ca/ITranscriptCache (fetch [_ k] {:ok k})
                (store! [_ k _] {:ok k}) (forget! [_ k] {:ok k})
                (evict! [_ age] {:ok age}))
        policy (reify cv/ICoveragePolicy (cover [_ xs _] {:ok xs}))]
    {:in x
     :out {:composer (co/compose (reify co/IVideoComposer
                                  (compose [_ uri _ _] {:ok {:output-uri uri}})) x [] {})
           :probe (me/probe (reify me/IMediaProbe
                             (probe [_ uri] {:ok {:duration-ms (count uri)}})) x)
           :extract (me/extract-audio (reify me/IAudioExtractor
                                       (extract-audio [_ uri _] {:ok uri})) x {})
           :source (s/read-text (reify s/ISourceReader (read-text [_ uri] {:ok uri})) x)
           :segment (sg/segment (reify sg/ISegmenter
                                  (segment [_ audio _] {:ok {:spans [{:start-ms 0 :end-ms (count audio)}]}})) x {})
           :fetch (f/fetch-media (reify f/ISourceFetcher
                                  (fetch-media [_ uri _] {:ok {:local-path uri}})) x {})
           :render (su/render-bytes (reify su/ISubtitleRenderer
                                     (render-bytes [_ track] {:ok track})) x)
           :parse (su/parse (reify su/ISubtitleParser
                             (parse [_ text _] {:ok {:cues [{:index 1 :start-ms 0 :end-ms (count text) :lines [text]}]}})) x :srt)
           :burn (b/burn! (reify b/IHardsubBurner (burn! [_ _ out _ _] out)) :video x [] {})
           :host-loaded (h/loaded host) :host-load (h/load! host x) :host-unload (h/unload! host x)
           :cache-fetch (ca/fetch cache x) :cache-store (ca/store! cache x [])
           :cache-forget (ca/forget! cache x) :cache-evict (ca/evict! cache (count x))
           :native (n/call-native native (constantly {:ok x})) :describe (n/describe native)
           :covered (cv/covered policy spans 0)}}))

(deftrifecta protocol-stubs protocol-observation
  {:golden-path "test/golden/port-rest-contract-protocols.edn"
   :cases {:empty "" :text "fixture"}
   :gen gen/string-alphanumeric
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/ProtocolResults out)
                (= in (get-in out [:composer :ok :output-uri])
                   (get-in out [:fetch :ok :local-path]) (:burn out))
                (every? #(= {:ok in} (out %))
                        [:extract :source :render :host-load :cache-fetch :cache-store :cache-forget :native])
                (= #{in} (get-in out [:host-loaded :ok]))
                (= (count in) (get-in out [:cache-evict :ok]) (get-in out [:probe :ok :duration-ms]))
                (= (get-in out [:covered :ok]) (get-in out [:segment :ok :spans]))
                (= [in] (get-in out [:parse :ok :cues 0 :lines]))))})

(defn cache-observation [valid?]
  {:in valid? :out (ca/cache? (when valid? ca/disabled))})
(deftrifecta cache-predicate cache-observation
  {:golden-path "test/golden/port-rest-contract-cache.edn"
   :cases {:valid true :invalid false} :gen gen/boolean
   :pred (fn [{:keys [in out]}] (and (m/validate c/Bool out) (= in out)))})

(defn fetcher-observation [valid?]
  (let [x (when valid? f/unavailable)]
    {:in valid? :out {:predicate (f/fetcher? x)
                     :checked (if valid? (= {:ok x} (f/checked-fetcher x))
                                  (:error (f/checked-fetcher x)))}}))
(deftrifecta fetcher-predicate-and-check fetcher-observation
  {:golden-path "test/golden/port-rest-contract-fetcher.edn"
   :cases {:valid true :invalid false} :gen gen/boolean
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:predicate :boolean] [:checked [:or :boolean :keyword]]] out)
                (= in (:predicate out))
                (= (if in true :error/invalid-source-fetcher) (:checked out))))})

(defn native-result-observation [fail?]
  {:in fail? :out (n/call-result
                  (reify n/INativeCall (call-native [_ thunk] (thunk))
                    (describe [_] {:strategy :stub}))
                  :error/stub (fn [] (if fail? (throw (ex-info "pinned" {})) {:ok 7})))})
(deftrifecta native-result native-result-observation
  {:golden-path "test/golden/port-rest-contract-native.edn"
   :cases {:success false :throw true} :gen gen/boolean
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:or [:map [:ok :int]] c/Failure] out)
                (= (if in :error/stub 7) (if in (:error out) (:ok out)))))})

(defn coverage-observation [fail?]
  (let [result (if fail? {:error :error/segmentation-failed} {:ok []})]
    {:in result :out (cv/covered (reify cv/ICoveragePolicy (cover [_ _ _] result)) [] 0)}))
(deftrifecta covered-preserves-result coverage-observation
  {:golden-path "test/golden/port-rest-contract-covered.edn"
   :cases {:success false :error true} :gen gen/boolean
   :pred (fn [{:keys [in out]}] (and (m/validate [:or c/SpansResult c/Failure] out) (= in out)))})

(deftest disabled-contract
  (is (= {:ok nil} (ca/fetch ca/disabled "key")))
  (is (= {:ok "key"} (ca/store! ca/disabled "key" [])))
  (is (= {:ok "key"} (ca/forget! ca/disabled "key")))
  (is (= {:ok 0} (ca/evict! ca/disabled 10)))
  (is (= :error/no-source-fetcher (:error (f/fetch-media f/unavailable "url" {})))))
