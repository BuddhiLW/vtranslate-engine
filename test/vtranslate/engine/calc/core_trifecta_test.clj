(ns vtranslate.engine.calc.core-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-dsl.result :as r]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-core :as s]
            [vtranslate.engine.calc.batching :as b]
            [vtranslate.engine.calc.cache-key :as k]
            [vtranslate.engine.calc.coverage :as c]
            [vtranslate.engine.calc.paths :as p]
            [vtranslate.engine.calc.progress :as progress]
            [vtranslate.engine.calc.promote :as promote]
            [vtranslate.engine.domain.rendering :as rd]
            [clojure.walk :as walk]
            [vtranslate.engine.schema.calc-render]
            [vtranslate.engine.schema.calc-reflow]
            [vtranslate.engine.schema.calc-text]
            [vtranslate.engine.schema.calc-process]))

(def texts (gen/vector gen/string-alphanumeric 0 20))
(def mismatch (fn [expected actual] (r/err :count-mismatch {:expected expected :actual actual})))
(defn item-cue [i text]
  (rd/make-cue {:index i :start-ms (* i 100) :end-ms (* (inc i) 100) :lines [text]}))

(deftrifecta context-windows b/context-windows
  {:apply? true :golden-path "test/golden/calc-core-windows.edn"
   :cases {:empty [[] 3 1] :overlap [["a" "b" "c" "d"] 2 1] :clamped [["a" "b"] 0 -1]}
   :gen (gen/tuple texts (gen/choose -2 10) (gen/choose -2 10))
   :pred #(m/validate s/Windows %)
   :mutations [["drops-all" (fn [& _] [])]]})

(deftrifecta reassemble b/reassemble
  {:golden-path "test/golden/calc-core-reassemble.edn"
   :cases {:empty [] :ok [(r/ok ["a"]) (r/ok ["b"])]
           :first-error [(r/err :first) (r/err :second)]}
   :gen (gen/vector (gen/one-of [(gen/fmap r/ok texts) (gen/return (r/err :failed))]) 0 10)
   :pred #(m/validate s/StringResult %)
   :mutations [["drops-all" (fn [_] (r/ok []))]]})

(deftrifecta index-groups b/index-groups
  {:apply? true :golden-path "test/golden/calc-core-groups.edn"
   :cases {:empty [[] even?] :interleaved [[1 2 3 4] even?]}
   :gen (gen/tuple (gen/vector gen/small-integer 0 20) (gen/return even?))
   :pred #(m/validate s/Groups %)
   :mutations [["no-groups" (fn [& _] {})]]})

(deftrifecta group-payload b/group-payload
  {:apply? true :golden-path "test/golden/calc-core-payload.edn"
   :cases {:empty [[] inc] :ordered [[[2 4] [0 7]] inc]}
   :gen (gen/tuple (gen/vector (gen/tuple gen/nat gen/small-integer) 0 20) (gen/return inc))
   :pred #(m/validate s/Payload %)
   :mutations [["no-payload" (fn [& _] {:indices [] :values []})]]})

(deftrifecta zip-indices b/zip-indices
  {:apply? true :golden-path "test/golden/calc-core-zip.edn"
   :cases {:match [[2 0] ["b" "a"] mismatch] :mismatch [[0] [] mismatch]}
   :gen (gen/tuple (gen/vector gen/nat 0 10) texts (gen/return mismatch))
   :pred #(m/validate s/IndexedResult %)
   :mutations [["always-empty" (fn [& _] (r/ok []))]]})

(def scatter-args
  (gen/bind texts (fn [xs]
                    (gen/fmap (fn [pairs] [(count xs) pairs mismatch])
                              (gen/shuffle (vec (map-indexed vector xs)))))))
(deftrifecta scatter b/scatter
  {:apply? true :golden-path "test/golden/calc-core-scatter.edn"
   :cases {:empty [0 [] mismatch] :reverse [2 [[1 "b"] [0 "a"]] mismatch]
           :incomplete [2 [[1 "b"]] mismatch] :nil-value [1 [[0 nil]] mismatch]}
   :gen scatter-args :pred #(m/validate s/StringResult %)
   :mutations [["always-empty" (fn [& _] (r/ok []))]]})

(deftrifecta digest k/digest
  {:golden-path "test/golden/calc-core-digest.edn"
   :cases {:empty "" :ascii "abc" :unicode "字幕🎬" :nil nil}
   :gen gen/string :pred #(m/validate s/Sha256 %)
   :mutations [["constant" (fn [_] (apply str (repeat 64 "0")))]]})

(deftrifecta transcript-key k/transcript-key
  {:golden-path "test/golden/calc-core-key.edn"
   :cases {:empty {} :model {:model "whisper"} :version {:model "whisper" :engine-version "2"}}
   :gen (gen/hash-map :content-sha gen/string-alphanumeric :model gen/string-alphanumeric
                      :span-pad-ms gen/nat :engine-version gen/string-alphanumeric)
   :pred #(m/validate s/Sha256 %)
   :mutations [["ignores-inputs" (fn [_] (k/digest ""))]]})

(deftrifecta cache-file-name k/cache-file-name
  {:golden-path "test/golden/calc-core-cache-name.edn"
   :cases {:digest (k/digest "a") :empty ""}
   :gen (gen/fmap k/digest gen/string)
   :pred #(m/validate s/CacheFilename %)
   :mutations [["no-extension" identity]]})

(def span-gen
  (gen/fmap (fn [[a b]] {:start-ms (min a b) :end-ms (max a b)})
            (gen/tuple (gen/choose 0 10000) (gen/choose 0 10000))))
(def spans-gen (gen/vector span-gen 0 10))
(deftrifecta gaps c/gaps
  {:apply? true :golden-path "test/golden/calc-core-gaps.edn"
   :cases {:none [[] 100 1] :covered [[{:start-ms 0 :end-ms 100}] 100 1]
           :overlap [[{:start-ms 10 :end-ms 60} {:start-ms 30 :end-ms 80}] 100 1]}
   :gen (gen/tuple spans-gen (gen/choose 0 10000) (gen/choose 1 1000))
   :pred #(m/validate s/Spans %)
   :mutations [["no-gaps" (fn [& _] [])]]})

(deftrifecta tile c/tile
  {:apply? true :golden-path "test/golden/calc-core-tile.edn"
   :cases {:remainder [{:start-ms 10 :end-ms 100} 40]
           :disabled [{:start-ms 0 :end-ms 100} 0] :zero [{:start-ms 0 :end-ms 0} 10]}
   :gen (gen/tuple span-gen (gen/choose 1 2000))
   :pred #(and (m/validate s/Spans %) (seq %)
               (every? (fn [[a b]] (= (:end-ms a) (:start-ms b))) (partition 2 1 %)))
   :mutations [["no-tiles" (fn [& _] [])]]})

(deftrifecta fill-gaps c/fill-gaps
  {:apply? true :golden-path "test/golden/calc-core-fill.edn"
   :cases {:positional [[] 100 40 1]
           :request [{:spans [] :duration-ms 100 :window-ms 40 :min-gap-ms 1}]
           :preserve [[{:start-ms 20 :end-ms 80}] 100 40 1]}
   :gen (gen/tuple spans-gen (gen/choose 1 10000) (gen/choose 1 2000) (gen/choose 1 1000))
   :pred #(and (m/validate s/Spans %)
               (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b))) (partition 2 1 %)))
   :mutations [["drops-coverage" (fn [& _] [])]]})

(def path-gen (gen/fmap #(str "out/" % ".mp4") gen/string-alphanumeric))
(deftrifecta sibling-output p/sibling-output
  {:apply? true :golden-path "test/golden/calc-core-sibling.edn"
   :cases {:nested ["dir/movie.mp4" ".srt"] :dotfile [".hidden" ".srt"] :bare ["movie" ".srt"]}
   :gen (gen/tuple path-gen (gen/elements [".srt" ".vtt" ".subbed.mp4"]))
   :pred #(m/validate :string %) :mutations [["unchanged" (fn [p _] p)]]})
(deftrifecta language-variant p/language-variant
  {:apply? true :golden-path "test/golden/calc-core-language.edn"
   :cases {:tag ["out/a.mp4" "pt-BR"] :blank ["out/a.mp4" " "] :nil ["a" nil]}
   :gen (gen/tuple path-gen (gen/elements [nil "" "en" "pt-BR" " ja "]))
   :pred #(m/validate :string %) :mutations [["unchanged" (fn [p _] p)]]})
(deftrifecta both-outputs p/both-outputs
  {:golden-path "test/golden/calc-core-both.edn"
   :cases {:nested "out/a.mp4" :bare "a"}
   :gen path-gen :pred #(and (m/validate s/BothPaths %) (not= (:soft %) (:hard %)))
   :mutations [["same-path" (fn [p] {:soft p :hard p})]]})

(def stages (gen/elements [:ingesting :transcribing :translating :rendering :composing :completed :unknown nil]))
(deftrifecta floor-of progress/floor-of
  {:golden-path "test/golden/calc-core-floor.edn"
   :cases {:first :ingesting :last :completed :unknown :unknown}
   :gen stages :pred #(m/validate s/MaybePercent %) :mutations [["always-zero" (constantly 0)]]})
(deftrifecta ceiling-of progress/ceiling-of
  {:golden-path "test/golden/calc-core-ceiling.edn"
   :cases {:first :ingesting :last :completed :unknown :unknown}
   :gen stages :pred #(m/validate s/Percent %) :mutations [["always-100" (constantly 100)]]})
(deftrifecta within-band progress/within-band
  {:apply? true :golden-path "test/golden/calc-core-within.edn"
   :cases {:half [:translating 1 2] :finished [:rendering 10 10] :negative [:ingesting -2 10]
           :no-work [:composing 0 0] :terminal [:completed 1 1] :unknown [:unknown 1 2]}
   :gen (gen/tuple stages (gen/choose -10 100) (gen/choose -2 100))
   :pred #(m/validate s/MaybePercent %) :mutations [["always-nil" (fn [& _] nil)]]})

(deftrifecta fill-cues promote/fill-cues
  {:apply? true :golden-path "test/golden/calc-core-promote.edn"
   ;; The golden store reads records as plain maps; normalize only snapshots.
   :xf #(walk/postwalk (fn [x] (if (record? x) (into {} x) x)) %)
   :cases {:empty [{:cues []} item-cue []]
           :ordered [{:cues []} item-cue ["first" "second"]]
           :error [{:cues []} item-cue ["first" "" "third"]]}
   :gen (gen/tuple (gen/return {:cues []}) (gen/return item-cue)
                   (gen/vector (gen/fmap #(str "text" %) gen/string-alphanumeric) 0 20))
   :pred #(and (m/validate s/TrackResult %)
               (or (:error %) (= (map :index (get-in % [:ok :cues]))
                                 (range 1 (inc (count (get-in % [:ok :cues])))))))
   :mutations [["no-cues" (fn [track _ _] (r/ok track))]]})

(deftest batching-and-coverage-conservation
  (doseq [xs [[] ["a"] ["a" "b" "c" "d"]] size [1 2 10]]
    (is (= xs (vec (mapcat :texts (b/context-windows xs size 2)))))
  (doseq [n [0 1 10 100]]
    (is (= n (reduce + (map #(- (:end-ms %) (:start-ms %))
                            (c/fill-gaps [] n 7 1))))))))

(deftest every-pure-public-calc-function-has-a-registered-contract
  ;; Use function-valued vars, not only defn :arglists: reflow/shape is a
  ;; public MultiFn. file-sha is explicitly excluded because it performs IO.
  (doseq [area '[ass batching burn cache-key caption-layout captions coverage
                encoding ffmpeg-args overlay paths progress promote reflow
                reflow.optimal reflow.linebreak rendering subtitle-in subtitle-out
                transcription translation watermark word-cues native-stack subprocess-env]
          :let [n (symbol (str "vtranslate.engine.calc." area))]
          [name v] (ns-publics n)
          :when (and (or (fn? @v) (instance? clojure.lang.MultiFn @v))
                     (not= [area name] '[cache-key file-sha]))]
    (is (some? (get-in (m/function-schemas) [n name :schema]))
        (str n "/" name " must register under its qualified namespace, not an alias"))))
