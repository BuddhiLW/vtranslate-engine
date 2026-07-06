(ns vtranslate.engine.corpus-validator-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vtranslate.engine.adapters.codec.srt :as srt]
            [vtranslate.engine.port.subtitle :as p.sub]
            [clojure.set :as set]))

(def corpus-root
  (.getCanonicalFile (io/file ".." "corpus")))

(def manifest-path
  (io/file corpus-root "MANIFEST.edn"))

(defn read-edn-file [file]
  (edn/read-string (slurp file)))

(defn manifest []
  (read-edn-file manifest-path))

(defn corpus-file [relative-path]
  (io/file corpus-root relative-path))

(defn srt-entries [manifest]
  (for [bucket (:buckets manifest)
        file-entry (:files bucket)
        :when (:cues file-entry)]
    (assoc file-entry :bucket (:id bucket))))

(defn parse-srt-file [relative-path]
  (let [result (p.sub/parse (srt/make-codec)
                            (slurp (corpus-file relative-path))
                            :format/srt)]
    (or (:ok result) (throw (ex-info "SRT parse failed" {:file relative-path
                                                         :result result})))))

(defn cue-text [cue]
  (str/trim
   (str/replace (str/join " " (:lines cue)) #"^\[[^\]]+\]\s*" "")))

(defn tokens [s]
  (->> (str/split (str/lower-case s) #"[^\p{L}\p{N}]+")
       (remove str/blank?)
       set))

(defn similarity [a b]
  (let [as (tokens a)
        bs (tokens b)
        union (set/union as bs)]
    (if (empty? union)
      1.0
      (/ (double (count (set/intersection as bs)))
         (count union)))))

(defn within-ms? [tolerance-ms a b]
  (<= (abs (- (long a) (long b))) tolerance-ms))

(defn cue-count-report []
  (mapv (fn [{:keys [file cues] :as entry}]
          (let [actual (count (:cues (parse-srt-file file)))]
            (assoc entry :expected cues :actual actual :ok? (= cues actual))))
        (srt-entries (manifest))))

(defn timeline []
  (read-edn-file (corpus-file "multisource/multisource.timeline.edn")))

(defn multisource-cues [relative-path]
  (:cues (parse-srt-file relative-path)))

(defn timing-report [relative-path tolerance-ms]
  (mapv (fn [cue expected]
          {:index (:index cue)
           :lang (:lang expected)
           :expected-start (:start-ms expected)
           :actual-start (:start-ms cue)
           :expected-end (:end-ms expected)
           :actual-end (:end-ms cue)
           :ok? (and (within-ms? tolerance-ms (:start-ms expected) (:start-ms cue))
                     (within-ms? tolerance-ms (:end-ms expected) (:end-ms cue)))})
        (multisource-cues relative-path)
        (timeline)))

(defn english-reference-report [min-similarity]
  (mapv (fn [cue expected]
          (let [score (similarity (cue-text cue) (:eng-ref expected))]
            {:index (:index cue)
             :lang (:lang expected)
             :actual (cue-text cue)
             :expected (:eng-ref expected)
             :similarity score
             :ok? (>= score min-similarity)}))
        (multisource-cues "multisource/multisource.en.srt")
        (timeline)))

(deftest manifest-cue-counts-match-parsed-srt
  (let [report (cue-count-report)]
    (is (seq report))
    (is (every? :ok? report) (pr-str (remove :ok? report)))))

(deftest multisource-timing-follows-ground-truth
  (testing "source-language and English reference SRTs stay aligned to timeline"
    (doseq [relative-path ["multisource/multisource.src.srt"
                           "multisource/multisource.en.srt"]]
      (let [report (timing-report relative-path 20)]
        (is (= (count (timeline)) (count report)))
        (is (every? :ok? report) (str relative-path " " (pr-str (remove :ok? report))))))))

(deftest multisource-english-reference-texts-match
  (let [report (english-reference-report 0.99)]
    (is (= (count (timeline)) (count report)))
    (is (every? :ok? report) (pr-str (remove :ok? report)))))
