(ns vtranslate.engine.adapters.composer.both-test
  "BothComposer: one compose -> two delegated composes (soft then hard) into
   variant output paths, short-circuiting on the first failure. Stub composers —
   no natives."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.adapters.composer.both :as sut]
            [vtranslate.engine.providers.composer-registry :as reg]))

(defn- recording-composer
  "IVideoComposer stub: records every compose's :output-uri on `calls`, ok's it back."
  [calls]
  (reify p.comp/IVideoComposer
    (compose [_ _video-source _subtitle-track opts]
      (swap! calls conj (:output-uri opts))
      (r/ok {:output-uri (:output-uri opts)}))))

(defn- failing-composer []
  (reify p.comp/IVideoComposer
    (compose [_ _video-source _subtitle-track _opts]
      (r/err :error/compose-failed {:reason "boom"}))))

(deftest compose-delegates-soft-then-hard-into-variant-paths
  (let [calls (atom [])
        res   (p.comp/compose (sut/make-composer (recording-composer calls)
                                                 (recording-composer calls))
                              "/v/movie.mkv" {:cues []}
                              {:output-uri "/v/movie.pt-BR.mp4"})]
    (is (r/ok? res))
    (is (= {:output-uris {:soft "/v/movie.pt-BR.soft.mp4"
                          :hard "/v/movie.pt-BR.hard.mp4"}}
           (:ok res)))
    (is (= ["/v/movie.pt-BR.soft.mp4" "/v/movie.pt-BR.hard.mp4"] @calls)
        "soft composes first, then hard")))

(deftest compose-without-output-uri-uses-the-subbed-sibling-convention
  (let [calls (atom [])
        res   (p.comp/compose (sut/make-composer (recording-composer calls)
                                                 (recording-composer calls))
                              "/v/movie.mkv" {:cues []} {})]
    (is (r/ok? res))
    (is (= {:output-uris {:soft "/v/movie.subbed.soft.mp4"
                          :hard "/v/movie.subbed.hard.mp4"}}
           (:ok res)))))

(deftest compose-short-circuits-on-soft-failure
  (let [calls (atom [])
        res   (p.comp/compose (sut/make-composer (failing-composer)
                                                 (recording-composer calls))
                              "/v/movie.mkv" {:cues []}
                              {:output-uri "/v/movie.pt-BR.mp4"})]
    (is (r/err? res))
    (is (= :error/compose-failed (:error res)))
    (is (empty? @calls) "hard never runs when soft failed")))

(deftest resolve-both-fails-loud-without-soft-and-hard
  (testing "resolving :both delegates to :soft/:hard; unregistered => unknown-composer"
    (let [res (reg/resolve-composer :both {})]
      (is (r/err? res))
      (is (= :error/unknown-composer (:error res))))))
