(ns vtranslate.engine.fetch-ingress-test
  "URL ingress: a remote source is resolved to a local path BEFORE kind inference,
   and with no fetch addon loaded it fails loud rather than handing a URL to probe."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.ingestion :as ing]
            [vtranslate.engine.main :as main]
            [vtranslate.engine.port.fetcher :as port]))

;; --- the snag this closes ---------------------------------------------------

(deftest source-extension-reads-the-last-path-segment-not-the-host
  (testing "a URL with no file in its path has no extension"
    (is (nil? (ing/source-extension "https://youtube.com/watch?v=dQw4w9WgXcQ")))
    (is (nil? (ing/source-extension "https://some.host.tv/watch?v=x#t=30")))
    (is (nil? (ing/source-extension "https://host.tv/"))))

  (testing "a real extension is still found, through query and fragment"
    (is (= "mp4" (ing/source-extension "https://cdn.host.tv/a/clip.mp4")))
    (is (= "mp4" (ing/source-extension "https://cdn.host.tv/a/clip.mp4?token=1")))
    (is (= "srt" (ing/source-extension "https://cdn.host.tv/a/subs.SRT#x")))
    (is (= "srt" (ing/source-extension "/home/me/subs.srt")))
    (is (nil?    (ing/source-extension "/home/me/noext")))
    (is (nil?    (ing/source-extension nil)))))

(deftest a-url-no-longer-misinfers-its-kind-from-the-host-name
  (testing "the regression: host dot-segments used to decide the ingress"
    (is (= :media/video (ing/infer-kind "https://youtube.com/watch?v=x")))
    (is (= :media/video (ing/infer-kind "https://host.tv/watch?v=x"))
        "a `.tv` host must not read as an extension")
    (is (= :media/video (ing/infer-kind "https://a.host/x.ass/watch?v=1"))
        "a subtitle-looking DIRECTORY is not the last path segment"))

  (testing "genuine subtitle URLs still take the parse ingress"
    (is (= :media/subtitle (ing/infer-kind "https://cdn.host/a/subs.srt")))
    (is (= :media/subtitle (ing/infer-kind "https://cdn.host/a/subs.vtt?t=1")))))

(deftest remote-source-recognises-network-schemes-only
  (is (ing/remote-source? "https://host/x"))
  (is (ing/remote-source? "HTTP://host/x"))
  (is (ing/remote-source? "rtsp://host/x"))
  (is (not (ing/remote-source? "/home/me/clip.mp4")))
  (is (not (ing/remote-source? "clip.mp4")))
  (is (not (ing/remote-source? "/home/me/https://weird")))
  (is (not (ing/remote-source? nil))))

;; --- the port contract ------------------------------------------------------

(defrecord StubFetcher [path]
  port/ISourceFetcher
  (fetch-media [_ uri _]
    (r/ok {:local-path path :title "A Clip" :metadata {:origin uri}})))

(defrecord RefusingFetcher []
  port/ISourceFetcher
  (fetch-media [_ uri _] (r/err :error/fetch-failed {:source uri :exit 1})))

(deftest the-unavailable-default-refuses-a-remote-source-loudly
  (is (port/fetcher? port/unavailable))
  (let [res (port/fetch-media port/unavailable "https://host/x" {})]
    (is (r/err? res))
    (is (= :error/no-source-fetcher (:error res)))
    (is (= "https://host/x" (:source res)) "the error names the URL that was refused")))

(deftest checked-fetcher-admits-the-port-and-rejects-anything-else
  (is (r/ok? (port/checked-fetcher (->StubFetcher "/tmp/a.mp4"))))
  (is (= :error/invalid-source-fetcher
         (:error (port/checked-fetcher {:fetch-media identity})))))

;; --- the boundary rewrite ---------------------------------------------------

(deftest a-fetched-spec-points-at-the-file-and-remembers-the-locator
  (let [spec {:job-id "j1" :source "https://host/watch?v=x" :target-language "pt"}
        out  (main/localized-spec spec {:local-path "/tmp/j1/clip.mp4"
                                        :title "A Clip"
                                        :metadata {:duration 42}})]
    (is (= "/tmp/j1/clip.mp4" (:source out)) "downstream sees a path, never a URL")
    (is (= "https://host/watch?v=x" (:source/origin out)))
    (is (= "A Clip" (:source/title out)))
    (is (= {:duration 42} (:source/metadata out)))
    (is (= "j1" (:job-id out)) "the rest of the spec is untouched")
    (is (= :media/video (ing/infer-kind (:source out)))
        "kind is inferred from the FETCHED path, which is the whole point"))

  (testing "a fetch that reports no title adds no empty keys"
    (let [out (main/localized-spec {:source "https://host/x"} {:local-path "/tmp/a.mp4"})]
      (is (not (contains? out :source/title)))
      (is (not (contains? out :source/metadata))))))

(deftest a-fetched-subtitle-url-takes-the-parse-ingress
  (let [out (main/localized-spec {:source "https://host/get?id=9"}
                                 {:local-path "/tmp/subs.srt"})]
    (is (= :media/subtitle (ing/infer-kind (:source out))))))
