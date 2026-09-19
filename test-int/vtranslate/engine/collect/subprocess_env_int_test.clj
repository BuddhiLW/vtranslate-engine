(ns vtranslate.engine.collect.subprocess-env-int-test
  "OPT-IN native integration suite (run: clojure -M:test:itest:ffmpeg).

   The unit suite proves the environment policy as a function on maps, and
   proves that env(1) sees only the allowlist. What neither can prove is that
   a REAL media tool still works once the environment is taken away from it.
   That is the regression this file exists to catch: an allowlist missing a
   variable ffmpeg or fontconfig actually needs fails nowhere near here, in a
   burn that renders no glyphs.

   $VT_FFMPEG picks the binary (default /usr/bin/ffmpeg). The tests SKIP, and
   say so, when no libass-capable ffmpeg is present."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [vtranslate.engine.collect.ffmpeg-cli :as cli]
            [vtranslate.engine.collect.process :as process])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private ffmpeg-bin
  (or (System/getenv "VT_FFMPEG") "/usr/bin/ffmpeg"))

(def ^:private usable?
  (and (.canExecute (File. ^String ffmpeg-bin))
       (cli/capable? (cli/ffmpeg-cli ffmpeg-bin))))

(defn- skipped
  "The skip path still asserts, as the silero suite's does: kaocha fails a
   test that ran no assertions."
  []
  (testing (str "skipped: no libass-capable ffmpeg at " ffmpeg-bin)
    (is (not usable?) (str "run it with VT_FFMPEG pointing at a libass-capable ffmpeg"))))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "vt-env-int" (make-array FileAttribute 0))))

(defn- source!
  "A one second clip, generated through the SANITIZED runner. Generating it
   this way is itself an assertion: a scrubbed environment has to be enough to
   run ffmpeg at all."
  [^File dir]
  (let [out (io/file dir "src.mp4")
        {:keys [exit stderr]}
        (process/exec! process/system-runner
                       [ffmpeg-bin "-hide_banner" "-loglevel" "error" "-y"
                        "-f" "lavfi" "-i" "color=c=navy:size=320x240:duration=1:rate=10"
                        "-pix_fmt" "yuv420p" (.getPath out)])]
    (is (zero? exit) (str "generating the source failed: " stderr))
    out))

(deftest a-real-burn-survives-the-sanitized-environment
  (if-not usable?
    (skipped)
    (let [dir (temp-dir)]
      (try
        (let [src  (source! dir)
              out  (io/file dir "burned.mp4")
              ;; Latin and CJK together: CJK is what fails first when
              ;; fontconfig cannot reach a cache or a font directory.
              cues [{:start-ms 0 :end-ms 1000 :lines ["Hello 字幕テスト"]}]]
          (testing "burn-hardsub runs the real ffmpeg with no inherited environment"
            (cli/burn-hardsub (cli/ffmpeg-cli ffmpeg-bin) src out cues {})
            (is (.exists out))
            (is (pos? (.length out)) "a burn that rendered nothing still writes a file")))
        (finally
          (run! #(.delete ^File %) (reverse (file-seq dir))))))))

(deftest the-sanitized-burn-is-byte-identical-to-an-inherited-one
  (if-not usable?
    (skipped)
    (let [dir (temp-dir)]
      (try
        (let [src      (source! dir)
              cues     [{:start-ms 0 :end-ms 1000 :lines ["Hello 字幕テスト"]}]
              burn     (fn [runner ^String name]
                         (let [out (io/file dir name)]
                           (cli/burn-hardsub (cli/ffmpeg-cli runner ffmpeg-bin)
                                             src out cues {})
                           (Files/readAllBytes (.toPath out))))
              scrubbed (burn process/system-runner "scrubbed.mp4")
              ;; nil env is the pre-fix behaviour: the child inherits everything.
              inherited (burn (process/runner nil) "inherited.mp4")]
          (testing "taking the environment away changes no rendered pixel"
            (is (pos? (alength ^bytes scrubbed)))
            (is (java.util.Arrays/equals ^bytes scrubbed ^bytes inherited)
                (str "the sanitized burn differs from the inherited one, so the "
                     "allowlist is missing something ffmpeg or fontconfig reads"))))
        (finally
          (run! #(.delete ^File %) (reverse (file-seq dir))))))))
