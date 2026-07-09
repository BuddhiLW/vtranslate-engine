(ns vtranslate.engine.domain.rendering-test
  "Trifecta — golden + property + mutation — for the SubtitleTrack aggregate:
   make-cue, make-subtitle-track, add-cue, render, extension. Records/ADTs are
   projected to plain EDN before snapshotting. No IO."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.domain.rendering :as rd]))

;; --- EDN projection (records/ADTs -> plain data) ---------------------------

(defn- project-cue [c]
  {:index    (:index c)
   :lines    (:lines c)
   :start-ms (get-in c [:range :start :ms])
   :end-ms   (get-in c [:range :end :ms])})

(defn- project-track
  "Record-free projection of a Result<SubtitleTrack> for snapshotting."
  [res]
  {:ok?       (r/ok? res)
   :error     (:error res)
   :id        (get-in res [:ok :id])
   :source-id (get-in res [:ok :source-id])
   :language  (get-in res [:ok :language])
   :format    (get-in res [:ok :format :adt/variant])
   :status    (get-in res [:ok :status :adt/variant])
   :cues      (mapv project-cue (get-in res [:ok :cues]))
   :extension (when (r/ok? res) (rd/extension (:ok res)))})

;; --- builders (real smart-ctors) -------------------------------------------

(defn- track-of [fmt]
  (:ok (rd/make-subtitle-track
        {:id "trk" :source-id "src" :language "en" :format fmt})))

(defn- build-rendered [fmt cue-inputs]
  (let [track (track-of fmt)
        cues  (map #(:ok (rd/make-cue %)) cue-inputs)]
    (rd/render (reduce rd/add-cue track cues))))

(def ^:private expected-ext
  {:format/srt "srt" :format/vtt "vtt" :format/ass "ass"})

;; =============================================================================
;; GOLDEN — a built + rendered track, projected to plain EDN
;; =============================================================================

(deftest-golden rendered-track-golden
  "test/golden/rendering-track-shape.edn"
  (project-track
   (build-rendered
    :format/srt
    [{:index 1 :start-ms 0    :end-ms 1000 :lines ["Hello"]}
     {:index 2 :start-ms 1000 :end-ms 2000 :lines ["World" "second line"]}
     {:index 3 :start-ms 2000 :end-ms 3000 :lines ["Third"]}])))

;; =============================================================================
;; PROPERTY — extension is total over the three formats and maps exhaustively
;; =============================================================================

(defspec extension-total-over-formats 200
  (prop/for-all [fmt (gen/elements [:format/srt :format/vtt :format/ass])]
    (let [ext (rd/extension (track-of fmt))]
      (and (string? ext)
           (contains? #{"srt" "vtt" "ass"} ext)
           (= (expected-ext fmt) ext)))))

;; =============================================================================
;; PROPERTY — add-cue grows :cues by exactly one (order + count preserved)
;; =============================================================================

(def ^:private gen-cue-input
  (gen/fmap (fn [[idx s d lines]]
              {:index idx :start-ms s :end-ms (+ s d) :lines lines})
            (gen/tuple (gen/choose 1 100)
                       (gen/choose 0 100000)
                       (gen/choose 0 5000)
                       (gen/vector gen/string-alphanumeric 1 3))))

(defspec add-cue-grows-cues-by-one 200
  (prop/for-all [cis (gen/vector gen-cue-input 0 8)]
    (let [cues  (mapv #(:ok (rd/make-cue %)) cis)
          track (reduce rd/add-cue (track-of :format/srt) cues)]
      (and (= (count cis) (count (:cues track)))
           (= (mapv :lines cues) (mapv :lines (:cues track)))))))

;; =============================================================================
;; PROPERTY — make-cue: valid inputs -> ok, lines/index preserved; empty -> err
;; =============================================================================

(defspec make-cue-preserves-valid-inputs 200
  (prop/for-all [ci gen-cue-input]
    (let [res (rd/make-cue ci)]
      (and (r/ok? res)
           (= (:index ci) (get-in res [:ok :index]))
           (= (vec (:lines ci)) (get-in res [:ok :lines]))))))

;; =============================================================================
;; UNIT — failure modes fail loud, matching the domain contract
;; =============================================================================

(deftest make-cue-empty-lines-fails
  (let [res (rd/make-cue {:index 1 :start-ms 0 :end-ms 1000 :lines []})]
    (is (r/err? res))
    (is (= :error/render-failed (:error res)))))

(deftest make-cue-inverted-range-fails
  (let [res (rd/make-cue {:index 1 :start-ms 1000 :end-ms 0 :lines ["x"]})]
    (is (r/err? res))))

(deftest make-track-unsupported-format-fails
  (let [res (rd/make-subtitle-track
             {:id "t" :source-id "s" :language "en" :format :format/foo})]
    (is (= :error/unsupported-format (:error res)))))

(deftest make-track-unsupported-language-fails
  (let [res (rd/make-subtitle-track
             {:id "t" :source-id "s" :language "xx" :format :format/srt})]
    (is (= :error/unsupported-language (:error res)))))

(deftest render-no-cues-fails
  (let [res (rd/render (track-of :format/vtt))]
    (is (r/err? res))
    (is (= :error/render-failed (:error res)))))

(deftest render-seals-status-rendered
  (let [res (build-rendered :format/srt
                            [{:index 1 :start-ms 0 :end-ms 1000 :lines ["a"]}])]
    (is (r/ok? res))
    (is (= :track/rendered (get-in res [:ok :status :adt/variant])))))

;; =============================================================================
;; MUTATION — prove the assertions catch broken implementations
;; =============================================================================

(deftest-mutations make-cue-mutations-caught
  vtranslate.engine.domain.rendering/make-cue
  [["accept-empty-lines"
    (fn [{:keys [index lines]}] (r/ok (rd/->Cue index nil (vec lines))))]
   ["skip-lines-check"
    (fn [{:keys [index start-ms end-ms lines]}]
      (r/let-ok [range (shared/make-time-range start-ms end-ms)]
        (r/ok (rd/->Cue index range (vec lines)))))]
   ["skip-range-check"
    (fn [{:keys [index lines]}]
      (if (seq lines)
        (r/ok (rd/->Cue index nil (vec lines)))
        (r/err :error/render-failed {})))]]
  (fn []
    (is (r/ok?  (rd/make-cue {:index 1 :start-ms 0 :end-ms 1000 :lines ["ok"]})))
    (is (r/err? (rd/make-cue {:index 1 :start-ms 0 :end-ms 1000 :lines []})))
    (is (r/err? (rd/make-cue {:index 1 :start-ms 1000 :end-ms 0 :lines ["x"]})))))

(deftest-mutations make-subtitle-track-mutations-caught
  vtranslate.engine.domain.rendering/make-subtitle-track
  [["accept-bad-format"
    (fn [{:keys [id source-id language format]}]
      (r/let-ok [lang (shared/make-language language)]
        (r/ok (rd/map->SubtitleTrack
               {:id id :source-id source-id :language lang
                :format format :cues [] :status nil}))))]
   ["skip-language-check"
    (fn [{:keys [id source-id language format]}]
      (r/ok (rd/map->SubtitleTrack
             {:id id :source-id source-id :language language
              :format format :cues [] :status nil})))]]
  (fn []
    (is (r/ok?  (rd/make-subtitle-track
                 {:id "t" :source-id "s" :language "en" :format :format/srt})))
    (is (= :error/unsupported-format
           (:error (rd/make-subtitle-track
                    {:id "t" :source-id "s" :language "en" :format :format/foo}))))
    (is (= :error/unsupported-language
           (:error (rd/make-subtitle-track
                    {:id "t" :source-id "s" :language "xx" :format :format/srt}))))))

(deftest-mutations add-cue-mutations-caught
  vtranslate.engine.domain.rendering/add-cue
  [["no-append"   (fn [track _cue] track)]
   ["replace-all" (fn [track cue] (assoc track :cues [cue]))]]
  (fn []
    (let [c1 (:ok (rd/make-cue {:index 1 :start-ms 0 :end-ms 1000 :lines ["a"]}))
          c2 (:ok (rd/make-cue {:index 2 :start-ms 1000 :end-ms 2000 :lines ["b"]}))
          t  (rd/add-cue (rd/add-cue (track-of :format/srt) c1) c2)]
      (is (= 2 (count (:cues t))))
      (is (= [["a"] ["b"]] (mapv :lines (:cues t)))))))

(deftest-mutations render-mutations-caught
  vtranslate.engine.domain.rendering/render
  [["always-ok"    (fn [track] (r/ok track))]
   ["always-err"   (fn [_track] (r/err :error/render-failed {}))]
   ["no-status"    (fn [{:keys [cues] :as track}]
                     (if (seq cues) (r/ok track)
                         (r/err :error/render-failed {})))]]
  (fn []
    (is (r/err? (rd/render (track-of :format/srt))))
    (let [res (build-rendered :format/srt
                              [{:index 1 :start-ms 0 :end-ms 1000 :lines ["a"]}])]
      (is (r/ok? res))
      (is (= :track/rendered (get-in res [:ok :status :adt/variant]))))))

(deftest-mutations extension-mutations-caught
  vtranslate.engine.domain.rendering/extension
  [["const-srt" (fn [_] "srt")]
   ["const-vtt" (fn [_] "vtt")]
   ["const-txt" (fn [_] "txt")]]
  (fn []
    (is (= "srt" (rd/extension (track-of :format/srt))))
    (is (= "vtt" (rd/extension (track-of :format/vtt))))
    (is (= "ass" (rd/extension (track-of :format/ass))))))
