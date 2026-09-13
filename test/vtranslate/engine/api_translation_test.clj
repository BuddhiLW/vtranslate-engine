(ns vtranslate.engine.api-translation-test
  "What a multi-language job does when one language does not come back:
   by default the job fails naming that language and the ones that finished;
   with :deliver-partial? the finished languages are delivered and the failed
   ones travel as :failed-targets. Mock ports, no IO."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.port.media :as p.media]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.port.subtitle :as p.sub]))

(defn- ports
  "Mock ports whose translator refuses every language in `broken`."
  [broken]
  {:media (reify
            p.media/IMediaProbe
            (probe [_ _] (r/ok {:container "mp4" :duration-ms 2000 :has-audio? true :audio-codec "aac"}))
            p.media/IAudioExtractor
            (extract-audio [_ _ _] (r/ok {:path "/tmp/a.wav"})))
   :transcriber (reify p.asr/ITranscriber
                  (transcribe [_ _ _ _]
                    (r/ok {:segments [{:start-ms 0 :end-ms 1000 :text "hello" :confidence 0.9}
                                      {:start-ms 1000 :end-ms 2000 :text "world" :confidence 0.8}]})))
   :translator (reify p.tr/ITranslator
                 (translate-batch [_ txts _ target _]
                   (if (contains? broken target)
                     (r/err :error/translation-failed {:class "HttpTimeoutException"
                                                       :message "request timed out"})
                     (r/ok (mapv #(str % "-" target) txts)))))
   :renderer (reify p.sub/ISubtitleRenderer
               (render-bytes [_ t]
                 (r/ok (str/join "\n\n" (for [c (:cues t)]
                                          (str (:index c) "\n" (str/join "\n" (:lines c))))))))})

(def ^:private targets ["pt-BR" "fr" "de"])

(defn- run [broken config]
  (api/run-job (assoc (ports broken) :config config)
               {:job-id "j" :source "/v.mp4" :source-language "en"
                :target-languages targets :format :format/srt}))

(deftest one-failed-language-fails-the-job-and-names-it
  (let [res (run #{"fr"} {})]
    (is (r/err? res))
    (is (= :error/translation-failed (:error res)))
    (is (= "fr" (:target-language res)) "the language is in the failure")
    (is (= ["fr"] (mapv :target-language (:failed-targets res))))
    (is (= ["pt-BR" "de"] (:delivered-targets res))
        "the work that finished is on record even though it is not delivered")))

(deftest partial-delivery-hands-over-what-finished
  (let [res (run #{"fr"} {:translator-opts {:deliver-partial? true}})]
    (is (r/ok? res))
    (is (= ["pt-BR" "de"] (mapv :target-language (get-in res [:ok :outputs]))))
    (is (= ["fr"] (mapv :target-language (get-in res [:ok :failed-targets]))))
    (is (= "request timed out" (get-in res [:ok :failed-targets 0 :message]))
        "the provider's own explanation survives")
    (is (some? (get-in res [:ok :translated]))
        "a single-target caller still finds :translated bound")))

(deftest every-language-failing-fails-the-job-even-with-partial-delivery
  (let [res (run (set targets) {:translator-opts {:deliver-partial? true}})]
    (is (r/err? res))
    (is (= "pt-BR" (:target-language res)) "the first target in request order")
    (is (= targets (mapv :target-language (:failed-targets res))))
    (is (= [] (:delivered-targets res)))))

(deftest nothing-failing-carries-no-failed-targets
  (let [res (run #{} {})]
    (is (r/ok? res))
    (is (= targets (mapv :target-language (get-in res [:ok :outputs]))))
    (is (nil? (get-in res [:ok :failed-targets])))))

(deftest target-concurrency-follows-the-request-up-to-a-cap
  ;; Measured 2026-09-13: a 7-language job at concurrency 3 ran its languages
  ;; in three waves of 60-200 s each. Each language is one provider request,
  ;; so the pool is sized to the request, up to the provider-politeness cap.
  (let [concurrency #'api/target-concurrency]
    (testing "one pool slot per language"
      (is (= 1 (concurrency {} ["pt"])))
      (is (= 3 (concurrency {} ["pt" "fr" "de"])))
      (is (= 7 (concurrency {} (repeat 7 "x")))))
    (testing "capped, so a 30-language request does not open 30 connections"
      (is (= 7 (concurrency {} (repeat 30 "x")))))
    (testing "an explicit bound wins"
      (is (= 2 (concurrency {:translator-opts {:target-concurrency 2}} (repeat 7 "x")))))
    (testing "never zero"
      (is (= 1 (concurrency {} []))))))
