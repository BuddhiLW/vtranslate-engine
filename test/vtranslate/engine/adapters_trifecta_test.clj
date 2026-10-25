(ns vtranslate.engine.adapters-trifecta-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.schema.adapters-boundaries]
            [vtranslate.engine.adapters.codec.timecode :as tc]
            [vtranslate.engine.adapters.codec.srt :as srt]
            [vtranslate.engine.adapters.codec.vtt :as vtt]
            [vtranslate.engine.adapters.codec.dispatch :as dispatch]
            [vtranslate.engine.adapters.codec.registry :as registry]
            [vtranslate.engine.adapters.segmenter.stub :as grid]
            [vtranslate.engine.adapters.transcriber.stub :as stub]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.port.subtitle :as sub]
            [vtranslate.engine.port.transcriber :as asr]
            [vtranslate.engine.port.segmenter :as seg]
            [vtranslate.engine.port.transcript-cache :as cache]
            [vtranslate.engine.port.fetcher :as fetch]))

(defn clock-observation [[ms sep :as in]]
  {:in in :out (tc/clock->ms (tc/ms->clock ms sep))})
(deftrifecta clock-roundtrip clock-observation
  {:golden-path "test/golden/adapters-clock.edn"
   :cases {:zero [0 ","] :minute [60001 "."] :last-two-digit-hour [359999999 ","]}
   :gen (gen/tuple (gen/choose 0 359999999) (gen/elements ["," "."]))
   :pred (fn [{:keys [in out]}] (= (first in) out))})

(defn grid-observation [[duration window :as in]]
  {:in in :out (grid/grid-spans duration window)})
(deftrifecta grid-tiling grid-observation
  {:golden-path "test/golden/adapters-grid.edn"
   :cases {:empty [0 20] :tail [21 10] :disabled [100 0]}
   :gen (gen/tuple (gen/choose 0 20000) (gen/choose 1 5000))
   :pred (fn [{[duration window] :in out :out}]
           (and (m/validate p/Spans out)
                (= duration (reduce + 0 (map #(- (:end-ms %) (:start-ms %)) out)))
                (every? #(<= (- (:end-ms %) (:start-ms %)) window) out)))})

(defn clamp-observation [[lo hi raw :as in]]
  {:in in :out (sup/clamp-to-window lo hi raw)})
(deftrifecta window-clamp clamp-observation
  {:golden-path "test/golden/adapters-clamp.edn"
   :cases {:overshoot [10 20 [{:start-ms 0 :end-ms 100 :text "a"}]]
           :padding [0 20 [{:start-ms 20 :end-ms 100 :text "b"}]]}
   :gen (gen/fmap (fn [xs] [10 100 (mapv (fn [[a b]] {:start-ms a :end-ms b :text (str a)}) xs)])
                  (gen/vector (gen/tuple (gen/choose 0 150) (gen/choose 0 200)) 0 20))
   :pred (fn [{[lo hi raw] :in out :out}]
           (and (every? #(<= lo (:start-ms %) (:end-ms %) hi) out)
                (= (mapv :text (filter #(< (:start-ms %) hi) raw)) (mapv :text out))))})

(defn offset-observation [[offset raw :as in]]
  {:in in :out (sup/offset-segments offset raw)})
(deftrifecta offset-inverse offset-observation
  {:golden-path "test/golden/adapters-offset.edn"
   :cases {:milliseconds [50 [{:start-ms 0 :end-ms 100 :text "a"}]]
           :seconds [1000 [{:start 0.0 :end 1.0 :text "b"}]]}
   :gen (gen/tuple (gen/choose 0 100000)
                   (gen/vector (gen/hash-map :start-ms (gen/choose 0 1000)
                                             :end-ms (gen/choose 1000 2000)) 0 20))
   :pred (fn [{[offset raw] :in out :out}]
           (= raw (sup/offset-segments (- offset) out)))})

(defn registry-observation [[registry format codec :as in]]
  {:in in :out (registry/codec-for (registry/register registry format codec) format)})
(deftrifecta registry-resolution registry-observation
  {:golden-path "test/golden/adapters-registry.edn"
   :cases {:new [{} :custom :codec] :replace [{:custom :old} :custom :new]}
   :gen (gen/tuple (gen/return {}) gen/keyword gen/string-alphanumeric)
   :pred (fn [{[_ _ codec] :in out :out}] (= {:ok codec} out))})

(defn codec-observation [[format start duration text :as in]]
  (let [codec (dispatch/make-codec)
        track {:format format :cues [{:index 1 :range {:start {:ms start} :end {:ms (+ start duration)}}
                                     :lines [text]}]}
        wire (sub/render-bytes codec track)]
    {:in in :out (sub/parse codec (:ok wire) format)}))
(deftrifecta codec-roundtrip codec-observation
  {:golden-path "test/golden/adapters-codec.edn"
   :cases {:srt [:format/srt 0 1000 "hello"] :vtt [:format/vtt 100 1000 "世界"]}
   :gen (gen/tuple (gen/elements [:format/srt :format/vtt])
                   (gen/choose 0 10000000) (gen/choose 1 10000)
                   (gen/fmap #(str "caption" %) gen/string-alphanumeric))
   :pred (fn [{[_ start duration text] :in out :out}]
           (and (m/validate p/ParseResult out)
                (= [{:index 1 :start-ms start :end-ms (+ start duration) :lines [text]}]
                   (:cues (:ok out)))))})

(deftest reference-adapters-conform
  (is (m/validate p/TranscribeResult
        (asr/transcribe (stub/make-transcriber 10) {:duration-ms 25} "en" {})))
  (is (m/validate p/SegmentResult
        (seg/segment (grid/make-segmenter 10) {:duration-ms 25} {})))
  (doseq [[codec format] [[(srt/make-codec) :format/srt] [(vtt/make-codec) :format/vtt]]]
    (is (m/validate p/RenderResult (sub/render-bytes codec {:cues []})))
    (is (m/validate p/ParseResult (sub/parse codec "" format)))
    (is (m/validate p/ParseResult (sub/parse codec "" :unknown))))
  (is (m/validate p/CacheFetchResult (cache/fetch cache/disabled "key")))
  (is (m/validate p/CacheWriteResult (cache/store! cache/disabled "key" {})))
  (is (m/validate p/CacheEvictResult (cache/evict! cache/disabled 1)))
  (is (m/validate p/FetchResult (fetch/fetch-media fetch/unavailable "https://fixture" {}))))

(deftest contracts-reject-corruption
  (doseq [bad [{:ok {:segments [{:start-ms 10 :end-ms 0 :text "a" :confidence 1.0}]}}
               {:ok {:segments [{:start-ms 0 :end-ms 10 :text "a" :confidence 2.0}]}}
               {:ok {:segments []} :error :error/asr-failed}]]
    (is (not (m/validate p/TranscribeResult bad)))))
