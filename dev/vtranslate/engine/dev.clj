(ns vtranslate.engine.dev
  "REPL + integration helpers (loaded via the :dev alias; dev/ on the classpath).
   Reproducible entry points so neither a human nor an agent hand-types native
   eval forms.

   Native helpers need the :ffmpeg alias; the bytedeco backend resolves lazily so
   this ns loads fine under plain :dev/:test too. Corpus helpers are location
   agnostic — resolved via $VT_CORPUS + a candidate list — and return nil when no
   corpus is present (callers skip rather than fail).

   Native REPL:  bash dev/repl-ffmpeg.sh [port]   ;; clojure -M:dev:ffmpeg nREPL
   At the REPL:  (require 'vtranslate.engine.dev)
                 (in-ns 'vtranslate.engine.dev)
                 (smoke!)              ;; hermetic probe+extract round-trip
                 (probe! \"/any/file.mp4\")
                 (corpus-probe! \"speech/pt.mp3\")"
  (:require [clojure.java.io :as io]
            [hive-dsl.result :as r]
            [vtranslate.engine.collect.protocols :as p]
            [vtranslate.engine.port.media :as pm])
  (:import [javax.sound.sampled AudioFormat AudioInputStream AudioSystem AudioFileFormat$Type]
           [java.io ByteArrayInputStream File]))

;; --- corpus resolution (location agnostic) ---------------------------------

(def corpus-candidates
  "Ordered corpus-dir candidates: $VT_CORPUS wins, then engine-local, monorepo
   root sibling, and the CLI repo. Add one here if corpus relocates."
  [(System/getenv "VT_CORPUS") "corpus" "../corpus" "../vtranslate-cli/corpus"])

(defn corpus-dir
  "First existing corpus directory among the candidates, or nil."
  ^File []
  (some (fn [c] (when c (let [f (io/file c)] (when (.isDirectory f) f))))
        corpus-candidates))

(defn corpus-file
  "Absolute path to `rel` under the resolved corpus dir, or nil if no corpus /
   no such file. e.g. (corpus-file \"speech/pt.mp3\")."
  ^String [rel]
  (when-let [d (corpus-dir)]
    (let [f (io/file d rel)]
      (when (.exists f) (.getCanonicalPath f)))))

;; --- hermetic fixture (pure JDK, no system ffmpeg, no corpus) ---------------

(defn silent-wav
  "Write `secs` of 16 kHz mono silence as a real WAV via the JDK. Returns the temp
   path (deleted on JVM exit)."
  (^String [] (silent-wav 1))
  (^String [secs]
   (let [sr 16000, n (* sr (long secs))
         fmt (AudioFormat. (float sr) 16 1 true false)
         ais (AudioInputStream. (ByteArrayInputStream. (byte-array (* n 2))) fmt n)
         f   (doto (File/createTempFile "vt-fixture-" ".wav") .deleteOnExit)]
     (AudioSystem/write ais AudioFileFormat$Type/WAVE f)
     (.getPath f))))

(defn stereo-tone-wav
  "Write `secs` of 48 kHz STEREO 16-bit sine at `hz` as a real WAV via the JDK.
   Returns the temp path (deleted on JVM exit).

   Deliberately UNLIKE `silent-wav`, which is already 16 kHz mono and so shares
   both the rate and the channel count of the extractor's target: a fixture that
   needs no conversion cannot witness a conversion bug. Stereo at a non-target
   rate is the shape that does, and a TONE rather than silence is what makes the
   resulting pitch measurable."
  (^String [] (stereo-tone-wav 2 440))
  (^String [secs] (stereo-tone-wav secs 440))
  (^String [secs hz]
   (let [sr     48000
         frames (* sr (long secs))
         buf    (byte-array (* frames 2 2))         ; frames * channels * 2 bytes
         bb     (doto (java.nio.ByteBuffer/wrap buf)
                  (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
     (dotimes [i frames]
       (let [v (short (* 12000 (Math/sin (* 2 Math/PI hz (/ (double i) sr)))))]
         (.putShort bb v)                           ; L
         (.putShort bb v)))                         ; R
     (let [fmt (AudioFormat. (float sr) 16 2 true false)
           ais (AudioInputStream. (ByteArrayInputStream. buf) fmt frames)
           f   (doto (File/createTempFile "vt-stereo-" ".wav") .deleteOnExit)]
       (AudioSystem/write ais AudioFileFormat$Type/WAVE f)
       (.getPath f)))))

;; --- ports (bytedeco resolved lazily) --------------------------------------

(defn ffmpeg-port
  "CollectMediaPort over the real JavaCv backend. Needs the :ffmpeg alias."
  []
  ((requiring-resolve 'vtranslate.engine.collect.port/collect-media-port)
   ((requiring-resolve 'vtranslate.engine.collect.ffmpeg/make-backend))))

(defn throwing-port
  "CollectMediaPort over a stub backend whose probe/extract THROW — drives the
   try-effect* + anti-corruption remap with no native deps."
  []
  ((requiring-resolve 'vtranslate.engine.collect.port/collect-media-port)
   (reify p/IMediaProbe
     (probe [_ _] (throw (ex-info "stub probe throw" {})))
     p/IAudioExtractor
     (extract-audio [_ _ _ _] (throw (RuntimeException. "stub extract throw"))))))

;; --- one-call helpers -------------------------------------------------------

(defn probe!   [path]          (pm/probe (ffmpeg-port) path))
(defn extract! ([path]      (extract! path {}))
               ([path opts] (pm/extract-audio (ffmpeg-port) path opts)))

(defn corpus-probe!
  "Probe a corpus file by relative path (e.g. \"speech/pt.mp3\"); nil if absent."
  [rel]
  (when-let [path (corpus-file rel)] (probe! path)))

(defn smoke!
  "Hermetic native round-trip on a generated WAV: probe + extract.
   => {:probe <Result> :extract <Result> :wav-bytes n}."
  []
  (let [port (ffmpeg-port)
        src  (silent-wav 1)
        pr   (pm/probe port src)
        au   (pm/extract-audio port src {})]
    {:probe pr
     :extract au
     :wav-bytes (when (r/ok? au) (.length (File. ^String (:ok au))))}))
