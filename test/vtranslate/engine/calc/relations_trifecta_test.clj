(ns vtranslate.engine.calc.relations-trifecta-test
  "Input/output contracts. Wrappers retain the input because trifecta :pred
   receives only the output. No production namespace depends on these tests."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [vtranslate.engine.calc.reflow :as r]
            [vtranslate.engine.calc.reflow.optimal]
            [vtranslate.engine.calc.captions :as c]
            [vtranslate.engine.calc.caption-layout :as l]
            [vtranslate.engine.calc.word-cues :as w]
            [vtranslate.engine.calc.subtitle-in :as si]
            [vtranslate.engine.calc.subtitle-out :as so]
            [vtranslate.engine.adapters.codec.srt :as srt]
            [vtranslate.engine.adapters.codec.vtt :as vtt]
            [vtranslate.engine.port.subtitle :as sub]))

(def token-gen (gen/elements ["one" "two" "café" "字幕" "end."]))
(def text-gen (gen/fmap #(str/join " " %) (gen/vector token-gen 1 8)))
(def cues-gen
  (gen/fmap (fn [texts]
              (mapv (fn [i text] {:index (+ 9 i) :start-ms (* i 1200)
                                  :end-ms (+ 1100 (* i 1200)) :lines [text]})
                    (range) texts))
            (gen/vector text-gen 0 5)))
(def sample [{:index 9 :start-ms 0 :end-ms 1000 :lines ["one two"]}
             {:index 8 :start-ms 1100 :end-ms 2000 :lines ["café end."]}])
(def music (conj sample {:index 0 :start-ms 2100 :end-ms 2200 :lines ["[music]"]}))
(def rules-gen
  (gen/hash-map :strategy (gen/elements [:greedy :optimal])
                :max-chars-line (gen/choose 5 25) :max-lines (gen/choose 1 3)
                :max-cps (gen/choose 2 30) :drop-music? gen/boolean
                :min-gap-ms (gen/choose 0 100) :max-dur-ms (gen/choose 200 4000)))
(defn- all-words [cs] (r/words (mapcat :lines cs)))
(defn- ordered? [cs]
  (and (every? #(<= (:start-ms %) (:end-ms %)) cs)
       (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b))) (partition 2 1 cs))))
(defn- numbered? [cs] (= (map :index cs) (range 1 (inc (count cs)))))
(defn- expected-words [in rules]
  ;; merge-overlaps explicitly sorts by time. Both wrappers hard-split a token
  ;; that cannot fit, by character, NOT by allowing an overwide output line.
  (let [cs (sort-by (juxt :start-ms :end-ms) (if (:drop-music? rules) (r/drop-music in) in))
        width (or (:max-chars-line rules) (when (= :optimal (:strategy rules)) 42))]
    (vec (mapcat (fn [word]
                   (if (and width (> (count word) width))
                     (map #(apply str %) (partition-all width word)) [word]))
                 (all-words cs)))))
(defn- shape-observation [in rules]
  {:in in :rules rules :out (r/shape in rules)})
(defn- reflow-observation [in rules]
  {:in in :rules rules :out (r/reflow in rules)})
(defn- reflow-contract? [{:keys [in rules out]}]
  (let [width (or (:max-chars-line rules) (when (= :optimal (:strategy rules)) 42))]
    (and (= (expected-words in rules) (all-words out))
         (ordered? out) (numbered? out)
         (or (nil? width) (every? #(<= (count %) width) (mapcat :lines out))))))
(deftrifecta shape-relation shape-observation
  {:golden-path "test/golden/calc-relation-shape.edn" :apply? true
   :cases {:empty [[] {}] :greedy [sample {:max-chars-line 5}]
           :optimal [sample {:strategy :optimal :max-chars-line 5}]
           :drop-music [music {:strategy :optimal :drop-music? true}]
           :overlap [[(first sample) (assoc (second sample) :start-ms 900)] {}]
           :hard-token [[{:index 4 :start-ms 0 :end-ms 1000 :lines ["extraordinary"]}]
                        {:strategy :optimal :max-chars-line 3}]}
   :gen (gen/tuple cues-gen rules-gen) :pred reflow-contract?})
(deftrifecta reflow-relation reflow-observation
  {:golden-path "test/golden/calc-relation-reflow.edn" :apply? true
   :cases {:greedy [sample {:max-chars-line 5}] :optimal [sample {:strategy :optimal}]
           :drop-music [music {:drop-music? true}]
           :hard-token [[{:index 4 :start-ms 0 :end-ms 1000 :lines ["extraordinary"]}]
                        {:max-chars-line 3 :max-cps 2}]}
   :gen (gen/tuple cues-gen rules-gen) :pred reflow-contract?})

(defn- roll-observation [in rules]
  (let [rules (assoc rules :strategy :optimal :style :roll-up)]
    {:in in :rules rules :base (r/shape in (dissoc rules :style))
     :out (r/shape in rules) :reflow-out (r/reflow in rules)}))
(defn- roll-contract? [{:keys [in rules base out reflow-out]}]
  ;; Roll-up deliberately repeats the previous BASE caption's last line.
  ;; Test the exact allowed repetition, rather than weakening conservation to
  ;; a set/subsequence comparison that could hide arbitrary duplication.
  (and (reflow-contract? {:in in :rules (dissoc rules :style) :out base})
       (= out reflow-out)
       (= out (mapv (fn [i cue]
                      (let [prev (get base (dec i))]
                        (if (and prev (< (count (:lines cue)) (:max-lines rules 2))
                                 (< (- (:start-ms cue) (:end-ms prev)) (:close-gap-ms rules 1000)))
                          (update cue :lines #(into [(last (:lines prev))] %)) cue)))
                    (range) base))))
(deftrifecta roll-up-relation roll-observation
  {:golden-path "test/golden/calc-relation-roll-up.edn" :apply? true
   :cases {:empty [[] {}] :carry [sample {:max-group 1 :max-lines 2}]
           :no-room [sample {:max-group 1 :max-lines 1}]
           :distant [sample {:max-group 1 :close-gap-ms 0}]}
   :gen (gen/tuple cues-gen (gen/fmap #(assoc % :max-group 1) rules-gen))
   :pred roll-contract?})

(defn- split-observation [in rules]
  {:in in :rules rules
   :out (r/split-cues (:max-cps rules) (:max-chars-line rules) (:max-lines rules) in)})
(defn- split-contract? [{:keys [in rules out]}]
  (and (= (expected-words in rules) (all-words out)) (ordered? out)
       ;; Every piece belongs inside its source cue, not merely inside the
       ;; track's min/max (which would miss a piece leaking into a silent gap).
       (every? (fn [piece]
                 (some #(and (= (:index %) (:index piece))
                             (<= (:start-ms %) (:start-ms piece) (:end-ms piece) (:end-ms %))) in)) out)
       (or (empty? in)
           (and (= (:start-ms (first in)) (:start-ms (first out)))
                (= (:end-ms (last in)) (:end-ms (last out)))))))
(deftrifecta split-cues-relation split-observation
  {:golden-path "test/golden/calc-relation-split-cues.edn" :apply? true
   :cases {:empty [[] {:max-cps 2}]
           :dense [sample {:max-cps 2 :max-chars-line 5 :max-lines 1}]
           :zero [[(assoc (first sample) :end-ms 0)] {:max-cps 1}]
           :short [[(assoc (first sample) :end-ms 1)] {:max-cps 1}]}
   :gen (gen/tuple cues-gen (gen/hash-map :max-cps (gen/choose 1 30)
                                         :max-chars-line (gen/choose 5 30) :max-lines (gen/choose 1 3)))
   :pred split-contract?})

(def word-input-gen
  (gen/fmap (fn [texts] (mapv (fn [i text] {:start (double i) :end (+ 0.5 i) :word (str " " text)})
                             (range) texts)) (gen/vector token-gen 1 20)))
(defn- recut-observation [in rules]
  {:in in :rules rules :out (w/recut (:segments in) (:words in) rules)})
(defn- recut-contract? [{:keys [in out]}]
  (let [ws (:words in) segments (:segments in)]
    (if (or (empty? ws) (empty? segments))
      (= segments out)
      (and (= (r/words (map :word ws)) (r/words (map :text out)))
           (= (:start (first ws)) (:start (first out)))
           (= (:end (last ws)) (:end (last out)))
           (every? #(= 0.9 (:confidence %)) out)
           (every? (fn [piece]
                     (let [members (filter #(<= (:start piece) (:start %) (:end %) (:end piece)) ws)]
                       (and (seq members) (= (:start piece) (:start (first members)))
                            (= (:end piece) (:end (last members)))
                            (= (:text piece) (str/trim (apply str (map :word members))))))) out)))))
(deftrifecta recut-relation recut-observation
  {:golden-path "test/golden/calc-relation-recut.edn" :apply? true
   :cases {:no-words [{:segments [{:start 0 :end 30 :text "unchanged"}] :words []} {}]
           :no-segments [{:segments [] :words [{:start 0 :end 1 :word " hi"}]} {}]
           :sentence [{:segments [{:start 0 :end 30 :confidence 0.9}]
                       :words [{:start 0 :end 1 :word " Hello."} {:start 1 :end 2 :word " world"}]} {}]}
   :gen (gen/tuple (gen/fmap #(hash-map :segments [{:start 0 :end 30 :confidence 0.9}] :words %) word-input-gen)
                   (gen/hash-map :max-ms (gen/choose 500 7000) :max-chars (gen/choose 5 30)
                                 :pause-ms (gen/choose 100 800) :min-ms (gen/choose 0 1500)))
   :pred recut-contract?})

(def style-gen (gen/hash-map :wrap (gen/choose 1 30) :bold? gen/boolean
                             :size-pct (gen/choose 1 10) :min-size-px (gen/choose 1 30)
                             :size-px (gen/choose -1 100) :y-position (gen/elements [-1 0 0.5 1 2])
                             :plate-opacity (gen/elements [-1 0 0.5 1 2])))
(defn- caption-observation [in rules]
  (let [{:keys [height n lh]} in]
    {:in in :rules rules :out {:style (c/style rules) :size (c/font-size-px height rules)
                              :bottom (c/block-bottom-px height rules)
                              :baselines (mapv #(c/baseline-px height rules n lh %) (range n))
                              :alpha (c/plate-alpha rules) :colors (c/colors rules)}}))
(defn- caption-contract? [{:keys [in rules out]}]
  (let [{:keys [height n lh]} in st (:style out) b (:bottom out)]
    (and (= (merge c/defaults (into {} (remove (comp nil? val)) rules)) st)
         (= (:size out) (if (pos? (:size-px st 0)) (long (:size-px st))
                         (max (long (:min-size-px st)) (long (* height (/ (:size-pct st) 100.0))))))
         (= b (max 0 (min height (long (* height (:y-position st))))))
         (= (:baselines out) (mapv #(- b (* lh (- n 1 %))) (range n)))
         (= (:alpha out) (max 0 (min 255 (Math/round (* 255.0 (:plate-opacity st))))))
         (= (:colors out) {:text (c/rgb (:text-color st)) :outline (c/rgb (:outline-color st))
                           :plate (c/rgb (:plate-color st))})) ))
(deftrifecta caption-geometry-relation caption-observation
  {:golden-path "test/golden/calc-relation-caption-geometry.edn" :apply? true
   :cases {:defaults [{:height 1080 :n 3 :lh 40} {}]
           :nil-style [{:height 100 :n 1 :lh 10} {:wrap nil :font-family nil}]
           :explicit [{:height 100 :n 2 :lh 20} {:size-px 37 :y-position 2 :plate-opacity -1}]}
   :gen (gen/tuple (gen/hash-map :height (gen/choose 1 2160) :n (gen/choose 1 5) :lh (gen/choose 1 100)) style-gen)
   :pred caption-contract?})
(defn- rgb-observation [in] {:in in :out (c/rgb (apply str "#" (map #(format "%02x" %) in)))})
(deftrifecta rgb-relation rgb-observation
  {:golden-path "test/golden/calc-relation-rgb.edn" :apply? true
   :cases {:black [[0 0 0]] :mixed [[17 171 255]]}
   :gen (gen/tuple (gen/vector (gen/choose 0 255) 3)) :pred #(= (:in %) (:out %))})
(defn- wrap-observation [in rules]
  {:in in :rules rules :out (l/wrap-text in (:chars rules) (:px rules) count)})
(defn- wrap-contract? [{:keys [in rules out]}]
  (and (= (r/words [(str in)]) (r/words out))
       (every? (fn [line]
                 (or (and (or (nil? (:chars rules)) (not (pos? (:chars rules)))
                              (<= (count line) (:chars rules)))
                          (<= (count line) (:px rules)))
                     ;; Unlike reflow, caption-layout preserves a long word whole.
                     (and (= 1 (count (r/words [line])))
                          (some #{line} (r/words [(str in)]))))) out)))
(deftrifecta caption-wrap-relation wrap-observation
  {:golden-path "test/golden/calc-relation-caption-wrap.edn" :apply? true
   :cases {:hard-word ["extraordinary" {:chars 3 :px 3}] :empty ["" {:chars 5 :px 5}]
           :wrap ["one two three" {:chars 5 :px 7}]}
   :gen (gen/tuple text-gen (gen/hash-map :chars (gen/choose 1 30) :px (gen/choose 1 50)))
   :pred wrap-contract?})

(defn- layout-observation [in rules]
  (let [measure (l/approximate-measure rules)
        out (l/layout (:frame in) rules (:lines in) measure)
        size (:font-size-px out)]
    {:in in :rules rules :out out
     :measured (mapv #(measure size %) (:lines out))
     :double-measured (mapv #((l/approximate-measure rules) (* 2 size) %) (:lines out))}))

(deftrifecta caption-layout-relation layout-observation
  {:golden-path "test/golden/calc-relation-caption-layout.edn" :apply? true
   :cases {:empty [{:frame {:width 360 :height 640} :lines []} {}]
           :shrink [{:frame {:width 10 :height 1080} :lines ["extraordinary"]} {}]
           :unicode [{:frame {:width 360 :height 640} :lines ["café 字幕" nil "one two"]} {}]}
   :gen (gen/tuple (gen/hash-map :frame (gen/hash-map :width (gen/choose 1 1920) :height (gen/choose 1 2160))
                                 :lines (gen/vector text-gen 0 5)) style-gen)
   :pred (fn [{:keys [in rules out measured double-measured]}]
           (let [width (get-in in [:frame :width])
                 size (:font-size-px out)]
             (and (= (r/words (remove nil? (:lines in))) (r/words (:lines out)))
                  (<= 1 size (c/font-size-px (get-in in [:frame :height]) rules))
                  (= (:max-line-px out)
                     (max 1 (- width (* 2 (Math/round (* 0.06 (double width)))))))
                  (= (:widest-px out) (long (Math/ceil (reduce max 0.0 measured))))
                  (or (= size 1) (every? #(<= % (:max-line-px out)) measured))
                  ;; Approximate widths scale linearly with pixel font size.
                  (every? true? (map #(== (* 2 %1) %2) measured double-measured)))) )})

(def spec {:id "track" :source-id "source" :language "en" :format :format/srt})
(defn- subtitle-observation [in rules]
  (let [translations (so/cue-texts in)
        translated (so/apply-translations in translations)
        track (:ok (si/build-subtitle-track in (merge spec rules)))
        codec (case (:format rules) :format/vtt (vtt/make-codec) (srt/make-codec))
        rendered (when track (:ok (sub/render-bytes codec track)))]
    {:in in :rules rules :out {:texts translations :translated translated
                               :cues (mapv (fn [cue] {:index (:index cue) :lines (:lines cue)
                                                     :start-ms (get-in cue [:range :start :ms])
                                                     :end-ms (get-in cue [:range :end :ms])}) (:cues track))
                               :parsed (when rendered (:cues (:ok (sub/parse codec rendered (:format rules)))))}}))
(defn- subtitle-contract? [{:keys [in out]}]
  (let [numbered (mapv #(assoc %1 :index %2) in (range 1 (inc (count in))))]
    (and (= (mapv #(str/join "\n" (:lines %)) in) (:texts out))
         (= in (:ok (:translated out)))
         (= numbered (:cues out) (:parsed out)))))
(deftrifecta subtitle-roundtrip-relation subtitle-observation
  {:golden-path "test/golden/calc-relation-subtitle-roundtrip.edn" :apply? true
   :cases {:srt [sample {:format :format/srt}]
           :vtt [sample {:format :format/vtt}]
           :multiline [[(assoc (first sample) :lines ["café 字幕" "two"])] {:format :format/srt}]}
   ;; Wire-valid nonblank lines without embedded newlines; at least one cue is
   ;; required by build-subtitle-track. Parser/render equality is exact in ms.
   :gen (gen/tuple (gen/such-that seq cues-gen 100)
                   (gen/hash-map :format (gen/elements [:format/srt :format/vtt])))
   :pred subtitle-contract?})
(defn- translation-observation [in rules]
  {:in in :rules rules :out (so/apply-translations in rules)})
(deftrifecta translations-relation translation-observation
  {:golden-path "test/golden/calc-relation-translations.edn" :apply? true
   :cases {:mismatch [sample []] :multiline [sample ["new\r\ntext" "changed"]] :empty [[] []]}
   :gen (gen/bind cues-gen (fn [cs] (gen/tuple (gen/return cs) (gen/vector text-gen (count cs)))))
   :pred (fn [{:keys [in rules out]}]
           (if (not= (count in) (count rules)) (= :error/translation-failed (:error out))
             (= (mapv #(assoc %1 :lines (str/split-lines %2)) in rules) (:ok out))))})
