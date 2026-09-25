(ns vtranslate.engine.calc.word-cues-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [vtranslate.engine.calc.word-cues :as wc]))

;; Production speaches 0.9 on 2026-09-25, 25 s of a sung Egyptian song through
;; the Arabic dialect fine-tune with word timestamps: ONE segment for the whole
;; window, sixteen timed words.
(def window-segment
  {:start 0.0 :end 23.92 :text "حسب الجنسية، وتلك قضية، وتلك قضية. كيف تكون إنسانا راقب ومطابك للإشترطات؟ كل كلام لها بالسواق."
   :avg_logprob -0.097 :compression_ratio 1.46 :no_speech_prob 0.0})

(def window-words
  [{:start 0.0 :end 0.48 :word "حسب"} {:start 0.48 :end 2.24 :word " الجنسية،"}
   {:start 2.24 :end 3.7 :word " وتلك"} {:start 3.7 :end 6.14 :word " قضية،"}
   {:start 6.14 :end 8.12 :word " وتلك"} {:start 8.12 :end 10.16 :word " قضية."}
   {:start 11.32 :end 12.72 :word " كيف"} {:start 12.72 :end 13.52 :word " تكون"}
   {:start 13.52 :end 14.66 :word " إنسانا"} {:start 14.66 :end 15.4 :word " راقب"}
   {:start 15.4 :end 17.32 :word " ومطابك"} {:start 17.32 :end 19.26 :word " للإشترطات؟"}
   {:start 19.26 :end 21.16 :word " كل"} {:start 21.16 :end 22.16 :word " كلام"}
   {:start 22.16 :end 23.06 :word " لها"} {:start 23.06 :end 23.92 :word " بالسواق."}])

(defn- dur-ms [s] (* 1000.0 (- (:end s) (:start s))))

(deftest a-window-long-segment-becomes-subtitle-sized-cues
  (let [cues (wc/recut [window-segment] window-words)]
    (is (= [[0.0 6.14] [6.14 10.16] [11.32 17.32] [17.32 19.26] [19.26 23.92]]
           (mapv (juxt :start :end) cues))
        "cut before 7 s, after a sentence's end, and at the 1.2 s pause")
    (is (every? #(<= (dur-ms %) 7000) cues) "no cue outlives the policy, whatever the window was")
    (is (= (str/join " " (map (comp str/trim :word) window-words))
           (str/join " " (map :text cues)))
        "every word is kept, in order, once")
    (is (every? #(= -0.097 (:avg_logprob %)) cues)
        "each piece keeps its segment's metrics for the hallucination filters")))

(deftest the-policy-is-data
  (is (= 2 (count (wc/recut [window-segment] window-words {:max-ms 20000 :pause-ms 5000 :min-ms 60000 :max-chars 500})))
      "a lax policy only cuts where the duration forces it")
  (is (every? #(<= (count (:text %)) 20) (wc/recut [window-segment] window-words {:max-chars 20}))))

(deftest without-words-the-server-timing-stands
  (is (= [window-segment] (wc/recut [window-segment] nil)))
  (is (= [window-segment] (wc/recut [window-segment] [])))
  (testing "a segment no word falls in is kept as it came"
    (let [tail {:start 30.0 :end 31.0 :text "tail"}]
      (is (= tail (last (wc/recut [window-segment tail] (take 2 window-words))))))))

(deftest short-segments-with-words-keep-their-shape
  (is (= [{:start 0.0 :end 1.2 :text "Hello there" :avg_logprob -0.2}]
         (wc/recut [{:start 0.0 :end 1.4 :text "Hello there" :avg_logprob -0.2}]
                   [{:start 0.0 :end 0.5 :word " Hello"} {:start 0.6 :end 1.2 :word " there"}]))))

(deftest spaceless-scripts-join-without-spaces
  (is (= ["你好世界"]
         (mapv :text (wc/recut [{:start 0.0 :end 2.0 :text "你好世界"}]
                               [{:start 0.0 :end 0.5 :word "你好"} {:start 0.5 :end 1.0 :word "世界"}])))))

(def gen-words
  (gen/let [gaps  (gen/vector (gen/choose 0 2000) 1 60)
            lens  (gen/vector (gen/choose 50 3000) (count gaps))
            texts (gen/vector (gen/fmap #(str " " %) (gen/not-empty gen/string-alphanumeric)) (count gaps))]
    (let [starts (reductions + 0 (map + gaps (cons 0 lens)))]
      (mapv (fn [s l t] {:start (/ s 1000.0) :end (/ (+ s l) 1000.0) :word t}) starts lens texts))))

(defspec every-word-lands-in-one-cue-and-no-multiword-cue-outgrows-the-policy 100
  (prop/for-all [words gen-words]
    (let [seg  {:start 0.0 :end (:end (peek words)) :text "x"}
          cues (wc/recut [seg] words)]
      (and (= (str/join " " (map (comp str/trim :word) words))
              (str/join " " (map :text cues)))
           (every? (fn [c] (or (<= (dur-ms c) 7000)
                               (= 1 (count (str/split (:text c) #" ")))))
                   cues)))))
