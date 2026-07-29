(ns vtranslate.engine.adapters.transcriber.support-test
  "The normalize-segments contract IS the LSP guarantee for the whole ITranscriber
   family: whatever messy hypotheses a backend emits, the promoted segments are
   ordered, non-overlapping, start<=end, with non-blank string text. If this holds
   here, every adapter that funnels through it satisfies check-transcriber."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [vtranslate.engine.adapters.transcriber.support :as sup]))

(defn- ordered-non-overlapping? [segs]
  (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b))) (partition 2 1 segs)))

(defn- contract-shaped? [segs]
  (and (vector? segs)
       (every? (fn [s] (and (nat-int? (:start-ms s))
                            (<= (:start-ms s) (:end-ms s))
                            (string? (:text s))
                            (seq (:text s)))) segs)
       (ordered-non-overlapping? segs)))

(deftest normalize-hardens-messy-input
  (testing "out-of-order, overlapping, end<start, and blank segments are all repaired"
    (let [raw   [{:start 2.0 :end 1.0 :text "  b  "}    ; end<start + padding
                 {:start 0.0 :end 3.0 :text "a"}        ; overlaps next
                 {:start 1.0 :end 2.5 :text "mid"}      ; overlaps prior
                 {:start 5.0 :end 6.0 :text "   "}      ; blank -> dropped
                 {:start 4.0 :end 5.0 :text "c" :confidence 0.7}]
          out   (sup/normalize-segments raw {:unit :s})]
      (is (contract-shaped? out) "normalized output satisfies the transcriber contract")
      (is (= 4 (count out)) "the blank-text segment is dropped, the rest survive")
      (is (= 700 (long (* 1000 (:confidence (last out)))))  ; 0.7 preserved
          "explicit confidence is preserved"))))

(deftest non-speech-markers-are-not-speech
  (testing "a hypothesis that is entirely a bracketed annotation carries no speech"
    (doseq [t ["[BLANK_AUDIO]" "[ Silence ]" "(upbeat music)" "*laughs*" "[]" "   "]]
      (is (sup/non-speech-text? t) (str t " is a non-speech marker"))))
  (testing "real speech is never mistaken for a marker"
    (doseq [t ["hello" "[Music] and then he spoke" "he said (quietly) yes"]]
      (is (not (sup/non-speech-text? t)) (str t " is speech")))))

(deftest blank-audio-markers-are-dropped
  (testing "whisper's non-speech placeholders never reach the contract"
    (is (= ["real speech"]
           (mapv :text (sup/normalize-segments
                        [{:start-ms 0    :end-ms 1000 :text "[BLANK_AUDIO]"}
                         {:start-ms 1000 :end-ms 2000 :text "real speech"}
                         {:start-ms 2000 :end-ms 3000 :text "(silence)"}]
                        {:unit :ms}))))))

;; Observed shape, ggml-medium + :grid over corpus/sintel/clips/sintel_105-140s.mp4:
;; whisper labels each silent grid window [BLANK_AUDIO] with a span covering the
;; whole gap, so the marker's end lands PAST the start of the next window's real
;; speech. Sheared in that state, genuine dialogue collapses to zero extent and
;; reached the index as an untimed `[00:00:12.260 --> 00:00:12.260]` chunk.
(deftest markers-are-dropped-before-the-shear
  (let [raw [{:start-ms 0     :end-ms 4260  :text "This blade has a dark past."}
             {:start-ms 4260  :end-ms 12260 :text "[BLANK_AUDIO]"}
             {:start-ms 10500 :end-ms 12800 :text "It has shed much innocent blood."}]
        out (sup/normalize-segments raw {:unit :ms})]
    (testing "the marker never reaches the contract"
      (is (= ["This blade has a dark past." "It has shed much innocent blood."]
             (mapv :text out))))
    (testing "and the speech it straddled keeps its own timing"
      (is (= [[0 4260] [10500 12800]] (mapv (juxt :start-ms :end-ms) out)))
      (is (every? (fn [s] (< (:start-ms s) (:end-ms s))) out)
          "no real segment is sheared down to zero extent"))))

(deftest pad-duplicate-recognises-a-re-transcription
  (testing "a fragment whose words the previous segment already carries"
    (is (sup/pad-duplicate? "You're a fool for traveling alone so completely unprepared."
                            "I'm completely unprepared."))
    (is (sup/pad-duplicate? "So, what brings you to the land?"
                            "It brings you to the land of the gatekeepers.")))
  (testing "case and punctuation do not matter"
    (is (sup/pad-duplicate? "COMPLETELY, UNPREPARED!" "completely unprepared")))
  (testing "genuinely new speech is not a duplicate"
    (is (not (sup/pad-duplicate? "It brings you to the land of the gatekeepers."
                                 "I'm searching for someone."))))
  (testing "an incidental short phrase in a long segment is not a duplicate"
    (is (not (sup/pad-duplicate? "he walked up to the door"
                                 "to the west the whole valley was already burning"))))
  (testing "a single shared word is never enough"
    (is (not (sup/pad-duplicate? "someone" "someone")))
    (is (not (sup/pad-duplicate? "" "anything")))))

;; Grid windows are decoded with :span-pad-ms of leading/trailing context, so
;; whisper re-hears the tail of the previous window and re-emits it as a segment
;; of its own. Observed on corpus/sintel/clips/sintel_105-140s.mp4, ggml-medium.
(deftest padded-windows-keep-one-hypothesis-per-utterance
  (testing "a re-transcribed FRAGMENT of the previous window is dropped"
    (is (= ["You're a fool for traveling alone so completely unprepared."
            "You're lucky your blood's still flowing."]
           (mapv :text
                 (sup/merge-padded-window
                  [{:start-ms 9500 :end-ms 15500
                    :text "You're a fool for traveling alone so completely unprepared."}]
                  15000
                  [{:start-ms 14500 :end-ms 16260 :text "I'm completely unprepared."}
                   {:start-ms 16260 :end-ms 18740 :text "You're lucky your blood's still flowing."}])))))

  (testing "when the re-transcription is the FULLER hypothesis it replaces the
            truncated one — the previous window cut the utterance at its boundary"
    (is (= ["Thank you." "It brings you to the land of the gatekeepers."]
           (mapv :text
                 (sup/merge-padded-window
                  [{:start-ms 19500 :end-ms 22500 :text "Thank you."}
                   {:start-ms 22500 :end-ms 25240 :text "So, what brings you to the land?"}]
                  25000
                  [{:start-ms 24500 :end-ms 30520
                    :text "It brings you to the land of the gatekeepers."}])))))

  (testing "a segment that merely STARTS inside the pad keeps its new speech"
    (is (= ["It brings you to the land of the gatekeepers."
            "I'm searching for someone."
            "Someone very dear."]
           (mapv :text
                 (sup/merge-padded-window
                  [{:start-ms 24500 :end-ms 30520
                    :text "It brings you to the land of the gatekeepers."}]
                  30000
                  [{:start-ms 29900 :end-ms 31460 :text "I'm searching for someone."}
                   {:start-ms 31460 :end-ms 33700 :text "Someone very dear."}])))))

  (testing "only the LEADING pad is suspect — a repeat starting after the span
            boundary is real dialogue"
    (is (= 3 (count (sup/merge-padded-window
                     [{:start-ms 16000 :end-ms 19000 :text "Thank you."}]
                     19000
                     [{:start-ms 19500 :end-ms 22500 :text "Thank you."}
                      {:start-ms 22500 :end-ms 25240 :text "So, what brings you to the land?"}])))))

  (testing "the first window has no predecessor, so nothing is dropped"
    (is (= 1 (count (sup/merge-padded-window
                     [] 0
                     [{:start-ms 0 :end-ms 4260 :text "This blade has a dark past."}]))))))

(deftest unit-conversion
  (testing ":s multiplies to ms; :ms passes through; rounding is half-up"
    (is (= 1400 (:end-ms (first (sup/normalize-segments [{:start 0 :end 1.4 :text "x"}] {:unit :s})))))
    (is (= 1400 (:end-ms (first (sup/normalize-segments [{:start-ms 0 :end-ms 1400 :text "x"}] {:unit :ms})))))))

(deftest empty-and-all-blank
  (is (= [] (sup/normalize-segments [] {:unit :s})) "empty in, empty out")
  (is (= [] (sup/normalize-segments [{:start 0 :end 1 :text ""}] {:unit :s})) "all-blank -> empty"))

(deftest preserves-segment-language
  (is (= ["de" "es"]
         (mapv :language
               (sup/normalize-segments [{:start-ms 0 :end-ms 100 :text "eins" :language "de"}
                                        {:start-ms 100 :end-ms 200 :text "dos" :language "es"}])))))

;; Property: for ANY generated set of ragged segments, the output is always
;; contract-shaped. This is the machine-checked LSP invariant.
(def gen-raw-seg
  (gen/let [a gen/nat, b gen/nat, t (gen/fmap #(apply str %) (gen/vector gen/char-alpha 0 6))]
    {:start-ms (min a b) :end-ms (max a b) :text t}))

(defspec normalized-output-is-always-contract-shaped 200
  (prop/for-all [raw (gen/vector gen-raw-seg 0 40)]
    (contract-shaped? (sup/normalize-segments raw {:unit :ms}))))