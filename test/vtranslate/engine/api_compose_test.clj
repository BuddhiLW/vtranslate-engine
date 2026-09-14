(ns vtranslate.engine.api-compose-test
  "Where a multi-language job's videos are WRITTEN.

   The regression this suite exists for: a job asking for eleven target
   languages produced eleven download links that all served the same file. The
   compose stage passed the job's `:output` straight to the composer, and a job
   submitted through the app names no `:output` at all, so every composer fell
   back to its own `<source>.subbed.mp4` default. That default does not know a
   second language is coming, so all eleven burns wrote the same path and each
   overwrote the last. The surviving file was whichever language finished last,
   and every language's link served it.

   Stub composer, no natives."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.port.composer :as p.comp]))

(def ^:private compose-sink #'vtranslate.engine.api/compose-sink)

(def ^:private targets
  "The languages of the job that surfaced this, in the order it asked for them.
   `fa` is last, and Persian is written in Arabic script, which is why every
   video read as Arabic."
  ["en-us" "pt-BR" "es" "fr" "de" "ru" "zh" "ja" "ar" "he" "fa"])

(def ^:private source "/media/jobs/job_acdf5928/source.mp4")

(defn- recording-composer
  "IVideoComposer stub recording every `:output-uri` it is handed, and
  defaulting a nil one exactly as the real composers do."
  [calls]
  (reify p.comp/IVideoComposer
    (compose [_ video-source _track opts]
      (let [out (or (:output-uri opts)
                    (str (subs video-source 0 (.lastIndexOf video-source "."))
                         ".subbed.mp4"))]
        (swap! calls conj out)
        (r/ok {:output-uri out})))))

(deftest multi-target-sinks-are-distinct
  (testing "a job that names no :output still gets one path per language"
    (let [sinks (mapv #(compose-sink source nil true %) targets)]
      (is (= (count targets) (count (distinct sinks)))
          "eleven languages must not share one output path")
      (is (every? some? sinks)
          "nil would put the composer back on its one-path default")
      (is (= "/media/jobs/job_acdf5928/source.subbed.ar.mp4"
             (compose-sink source nil true "ar")))))

  (testing "a named :output is still tagged per language"
    (is (= ["/out/v.en-us.mp4" "/out/v.fa.mp4"]
           [(compose-sink source "/out/v.mp4" true "en-us")
            (compose-sink source "/out/v.mp4" true "fa")])))

  (testing "a single-target job is passed through untouched, composer default included"
    (is (nil? (compose-sink source nil false "ar")))
    (is (= "/out/v.mp4" (compose-sink source "/out/v.mp4" false "ar")))))

(deftest every-language-is-composed-to-its-own-file
  (testing "the stage writes one file per target, none overwriting another"
    (let [calls    (atom [])
          composer (recording-composer calls)
          outs     (mapv (fn [lang]
                           (:ok (#'vtranslate.engine.api/compose-one
                                 composer
                                 {:source source :output nil}
                                 true
                                 {:target-language lang :subtitle-track {}})))
                         targets)]
      (is (= (count targets) (count (distinct @calls)))
          "before the fix every call carried the same path")
      (is (= (count targets) (count (distinct (map :output-video outs))))
          "and so every output reported the same video back to the app")
      (is (= (set @calls) (set (map :output-video outs)))
          "what was written is what the job says it wrote"))))
