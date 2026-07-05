(ns vtranslate.engine.domain.ingestion
  "Ingestion bounded context — the MediaAsset aggregate root: the source
   artifact a job ingests (video / audio / external subtitle) plus the probe
   facts the Collect layer fills in. Pure domain; no bytedeco/ffmpeg type ever
   crosses in here (those stay in engine.collect, behind the boundary).
   DDD: a job references a MediaAsset BY ID (:id), never embeds it."
  (:require [clojure.string :as str]
            [hive-dsl.adt :refer [defadt adt-case]]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]))

;; --- Kind + lifecycle (closed sums) ----------------------------------------

(defadt MediaKind
  "What the source artifact is — selects the ingress path."
  :media/video        ;; demux audio -> ASR
  :media/audio        ;; ASR directly
  :media/subtitle)    ;; parse path, no ASR

(def ^:private media-kinds
  #{:media/video :media/audio :media/subtitle})

(defadt AssetStatus
  "Ingestion lifecycle. :asset/rejected is terminal."
  :asset/registered   ;; URI known, not yet probed
  :asset/probed       ;; container/streams read
  :asset/ready        ;; usable audio (or is subtitle) -> ingress can start
  :asset/rejected)    ;; unusable (no audio / unsupported container)

;; --- Probe facts (value object, filled by Collect at the boundary) ---------

(defrecord ProbeInfo
  [container duration-ms has-audio? audio-codec])

;; --- Aggregate root --------------------------------------------------------

(defrecord MediaAsset
  [id source-ref kind status probe])   ; source-ref : shared/SourceRef VO

(defn make-media-asset
  "Smart ctor for a freshly registered MediaAsset (:asset/registered, no probe).
   `source-uri` is wrapped in a shared/SourceRef VO (validated, non-blank).
   => (r/ok MediaAsset)
      | (r/err :error/unsupported-format {:format kind} | :error/invalid-source ...)."
  [{:keys [id source-uri kind]}]
  (if (contains? media-kinds kind)
    (r/let-ok [src (shared/make-source-ref source-uri)]
      (r/ok (map->MediaAsset
             {:id id
              :source-ref src
              :kind (media-kind kind)
              :status (asset-status :asset/registered)
              :probe nil})))
    (r/err :error/unsupported-format {:format kind})))

(defn with-probe
  "Attach probe facts and move :asset/registered -> :asset/probed."
  [asset probe]
  (assoc asset :probe probe :status (asset-status :asset/probed)))

(defn ready
  "Mark a probed asset ready for ingress. Video/audio require an audio stream;
   subtitles are always ready.
   => (r/ok asset') | (r/err :error/no-audio-stream {:source-id ...})."
  [{:keys [kind probe] :as asset}]
  (if (or (= (:adt/variant kind) :media/subtitle)
          (:has-audio? probe))
    (r/ok (assoc asset :status (asset-status :asset/ready)))
    (r/err :error/no-audio-stream {:source-id (str (:id asset))})))

(defn reject
  "Move an asset to terminal :asset/rejected."
  [asset]
  (assoc asset :status (asset-status :asset/rejected)))

(defn ingress-path
  "Which pipeline ingress this asset feeds. adt-case ⇒ adding a MediaKind
   variant breaks this at compile time (exhaustiveness)."
  [{:keys [kind]}]
  (adt-case MediaKind kind
    :media/video    :ingress/demux-asr
    :media/audio    :ingress/asr
    :media/subtitle :ingress/parse))

;; --- kind inference (boundary hint; make-media-asset validates the result) ----

(def ^:private subtitle-extensions
  "Filename extensions that select the parse (no-ASR) ingress."
  #{"srt" "vtt" "ass"})

(defn infer-kind
  "Infer a source's MediaKind from its filename extension: a subtitle extension
   selects :media/subtitle (parse ingress), anything else defaults to
   :media/video (demux + ASR). A plain keyword — make-media-asset validates it."
  [source-uri]
  (let [ext (some-> source-uri (str/split #"\.") last str/lower-case)]
    (if (contains? subtitle-extensions ext)
      :media/subtitle
      :media/video)))
