(ns vtranslate.engine.adapters.asr-support-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.adapters.transcriber.support :as s])
  (:import [javax.sound.sampled AudioSystem] [java.io ByteArrayInputStream]))
(defn path-observation [[path kind :as in]]
  {:in in :out (s/audio->path (case kind :string path :map {:path path} :file (java.io.File. path) :other 42))})
(deftrifecta audio->path path-observation
  {:golden-path "test/golden/adapters-support-path.edn"
   :cases {:string ["a.wav" :string] :map ["a.wav" :map] :file ["a.wav" :file] :other ["a.wav" :other]}
   :gen (gen/tuple (gen/fmap #(str % ".wav") gen/string-alphanumeric) (gen/elements [:string :map :file :other]))
   :pred (fn [{[path kind] :in out :out}] (and (m/validate [:maybe :string] out) (= out (when (not= kind :other) path))))})
(defn speech-observation [[text annotation? :as in]]
  {:in in :out (s/non-speech-text? (if annotation? (str "[" text "]") (str "spoken " text)))})
(deftrifecta non-speech-text? speech-observation
  {:golden-path "test/golden/adapters-support-nonspeech.edn"
   :cases {:marker ["BLANK_AUDIO" true] :spoken ["music" false] :annotation ["silence" true]}
   :gen (gen/tuple (gen/elements (vec s/non-speech-markers)) gen/boolean)
   :pred (fn [{[_ annotation?] :in out :out}] (and (m/validate :boolean out) (= annotation? out)))})
(defn duplicate-observation [n]
  (let [text (clojure.string/join " " (map #(str "word" %) (range n)))]
    {:in n :out (s/pad-duplicate? text (.toUpperCase text java.util.Locale/ROOT))}))
(deftrifecta pad-duplicate? duplicate-observation
  {:golden-path "test/golden/adapters-support-duplicate.edn"
   :cases {:empty 0 :one 1 :two 2 :long 8}
   :gen (gen/choose 0 20)
   :pred (fn [{:keys [in out]}] (and (m/validate :boolean out) (= out (>= in 2))))})
(defn merge-observation [[text longer? :as in]]
  (let [a {:start-ms 0 :end-ms 100 :text text}
        b {:start-ms 50 :end-ms 200 :text (if longer? (str text " extra") text)}]
    {:in in :out (s/merge-padded-window [a] 100 [b])}))
(deftrifecta merge-padded-window merge-observation
  {:golden-path "test/golden/adapters-support-merge.edn"
   :cases {:duplicate ["one two" false] :longer ["one two" true]}
   :gen (gen/tuple (gen/fmap #(str "spoken words " %) gen/string-alphanumeric) gen/boolean)
   :pred (fn [{[text longer?] :in out :out}]
           (and (m/validate [:vector [:map [:start-ms :int] [:end-ms :int] [:text :string]]] out)
                (= 1 (count out)) (= (if longer? (str text " extra") text) (:text (first out)))
                (= (if longer? 200 100) (:end-ms (first out)))))})
(defn normalize-observation [raw] {:in raw :out (s/normalize-segments raw)})
(deftrifecta normalize-segments normalize-observation
  {:golden-path "test/golden/adapters-support-normalize.edn"
   :cases {:repair [{:start-ms 20 :end-ms 80 :text " first "} {:start-ms 10 :end-ms 40 :text "second"}]
           :silence [{:start-ms 0 :end-ms 100 :text "[BLANK_AUDIO]"}]}
   :gen (gen/vector (gen/hash-map :start-ms (gen/choose -10 1000) :end-ms (gen/choose 0 2000)
                                 :text (gen/elements ["words" "[silence]" " " "hello"])) 0 15)
   :pred (fn [{:keys [in out]}]
           (and (m/validate p/Segments out) (= out (s/normalize-segments out))
                (<= (count out) (count in)) (not-any? #(s/non-speech-text? (:text %)) out)))})
(defn offset-observation [[offset raw :as in]] {:in in :out (s/offset-segments offset raw)})
(deftrifecta offset-segments offset-observation
  {:golden-path "test/golden/adapters-support-offset.edn"
   :cases {:missing [10 [{}]] :ms [10 [{:start-ms 1 :end-ms 3}]] :seconds [1000 [{:start 0.0 :end 1.0}]]}
   :gen (gen/tuple (gen/choose 0 10000) (gen/vector (gen/hash-map :start-ms gen/nat :end-ms gen/nat) 0 10))
   :pred (fn [{[offset raw] :in out :out}]
           (and (m/validate [:vector :map] out) (= raw (s/offset-segments (- offset) out)) (= (count raw) (count out))))})
(defn clamp-observation [[lo hi raw :as in]] {:in in :out (s/clamp-to-window lo hi raw)})
(deftrifecta clamp-to-window clamp-observation
  {:golden-path "test/golden/adapters-support-clamp.edn"
   :cases {:overshoot [10 20 [{:start-ms 0 :end-ms 100}]] :empty [20 20 [{:start-ms 0 :end-ms 100}]]}
   :gen (gen/fmap (fn [raw] [10 100 raw]) (gen/vector (gen/hash-map :start-ms (gen/choose 0 150) :end-ms (gen/choose 0 200)) 0 15))
   :pred (fn [{[lo hi raw] :in out :out}]
           (and (m/validate [:vector [:map [:start-ms :int] [:end-ms :int]]] out)
                (every? #(<= lo (:start-ms %) (:end-ms %) hi) out)
                (= (count out) (count (filter #(< (:start-ms %) hi) raw)))) )})
(defn opts-observation [opts] {:in opts :out (s/resolve-decode-opts opts)})
(deftrifecta resolve-decode-opts opts-observation
  {:golden-path "test/golden/adapters-support-options.edn"
   :cases {:empty {} :nil {:beam-size nil} :coerce {:beam-size 3.7 :temperature 0 :no-context? false :unknown 42}}
   :gen (gen/hash-map :beam-size (gen/choose 1 8) :no-context? gen/boolean :initial-prompt gen/string-alphanumeric :unknown gen/nat)
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map-of :keyword :any] out)
                (= (set (keys out)) (set (filter #(some? (get in %)) (keys s/decode-knobs))))
                (= out (s/resolve-decode-opts out))
                (every? (fn [[k v]] (= v ((get s/decode-knobs k) (get in k)))) out)))})
(defn wav-observation [samples]
  (let [bytes (s/samples->wav-bytes (float-array samples) 16000)]
    (with-open [in (AudioSystem/getAudioInputStream (ByteArrayInputStream. bytes))]
      {:in samples :out {:frames (.getFrameLength in) :rate (double (.getSampleRate (.getFormat in)))
                        :channels (.getChannels (.getFormat in)) :bytes (alength bytes)}})))
(deftrifecta samples->wav-bytes wav-observation
  {:golden-path "test/golden/adapters-support-wav.edn"
   :cases {:empty [] :clip [-1.0 0.0 1.0]}
   :gen (gen/vector (gen/elements [-1.0 -0.5 0.0 0.5 1.0]) 0 100)
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:frames :int] [:rate :double] [:channels :int] [:bytes :int]] out)
                (= (count in) (:frames out)) (= 16000.0 (:rate out)) (= 1 (:channels out))
                (= (+ 44 (* 2 (count in))) (:bytes out))))})
