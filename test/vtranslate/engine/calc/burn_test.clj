(ns vtranslate.engine.calc.burn-test
  "Backend choice table for the hardsub composer. Pure.

   The registered key set is an ARGUMENT here, exactly as the composer
   adapter passes it, so these tests never require the provider registry and
   never depend on which adapter namespaces happen to be loaded."
  (:require [clojure.test :refer [deftest is are testing]]
            [vtranslate.engine.calc.burn :as sut]))

(def ^:private registered
  "What the burner registry reports on the :ffmpeg classpath today."
  #{:ffmpeg-cli :ffmpeg-nvenc :ffmpeg-vaapi :javacv})

(deftest binary-defaults-to-path-lookup
  (is (= "ffmpeg" (sut/binary {})))
  (is (= "ffmpeg" (sut/binary {:ffmpeg-bin "  "})))
  (is (= "/usr/bin/ffmpeg" (sut/binary {:ffmpeg-bin " /usr/bin/ffmpeg "}))))

(deftest absent-and-explicit-auto-read-as-auto
  (are [opts] (= :auto (sut/requested opts registered))
    {}
    {:burn-backend nil}
    {:burn-backend :auto}))

(deftest a-registered-key-is-returned-as-asked
  (are [opts want] (= want (sut/requested opts registered))
    {:burn-backend :ffmpeg-cli}   :ffmpeg-cli
    {:burn-backend :javacv}       :javacv
    {:burn-backend :ffmpeg-nvenc} :ffmpeg-nvenc
    {:burn-backend :ffmpeg-vaapi} :ffmpeg-vaapi))

(deftest an-unknown-key-fails-loud-instead-of-degrading
  (testing "the bug this signature exists to remove: :nvenc used to read as
            :auto, the job encoded on libx264, and nothing said so"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown burn backend"
                          (sut/requested {:burn-backend :nvenc} registered)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown burn backend"
                          (sut/requested {:burn-backend "javacv"} registered))
        "a string is not the keyword the registry holds"))
  (testing "the refusal names what IS registered, so the typo is fixable"
    (let [data (try (sut/requested {:burn-backend :qsv} registered)
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :error/unknown-burn-backend (:error data)))
      (is (= :qsv (:burn-backend data)))
      (is (= (set registered) (set (:known data)))))))

(deftest a-registered-but-unlisted-backend-is-honoured
  (testing "REGRESSION. A backend the registry has registered but no literal
            set in calc lists must reach the burner. Before this, calc's
            closed `backends` set decided, so an adapter could register
            :ffmpeg-vaapi perfectly, a deployment could name it, and the job
            silently encoded on libx264 with no error and no log line."
    (let [known #{:ffmpeg-cli :javacv :ffmpeg-vaapi :ffmpeg-amf :some-future-burner}]
      (is (= :ffmpeg-vaapi (sut/requested {:burn-backend :ffmpeg-vaapi} known)))
      (is (= :ffmpeg-vaapi (sut/choose {:burn-backend :ffmpeg-vaapi} known true)))
      (is (= :ffmpeg-vaapi (sut/choose {:burn-backend :ffmpeg-vaapi} known false))
          "an explicit backend is honoured even when the binary did not answer"))
    (testing "a key calc has never heard of, registered by an addon"
      (let [known #{:ffmpeg-cli :javacv :ffmpeg-amf}]
        (is (= :ffmpeg-amf (sut/choose {:burn-backend :ffmpeg-amf} known true)))))
    (testing "and a key calc DOES know, unregistered, is still refused"
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/choose {:burn-backend :ffmpeg-nvenc} #{:ffmpeg-cli :javacv} true))
          "no adapter registered it, so nothing could build it"))))

(deftest choice-table
  (are [opts available? want] (= want (sut/choose opts registered available?))
    {}                            true  :ffmpeg-cli
    {}                            false :javacv
    {:burn-backend :auto}         false :javacv
    {:burn-backend :javacv}       true  :javacv
    {:burn-backend :ffmpeg-cli}   false :ffmpeg-cli
    {:burn-backend :ffmpeg-cli}   true  :ffmpeg-cli
    {:burn-backend :ffmpeg-nvenc} false :ffmpeg-nvenc
    {:burn-backend :ffmpeg-vaapi} true  :ffmpeg-vaapi))

(deftest auto-never-claims-a-device
  (is (= :ffmpeg-cli (sut/choose {} registered true))
      "a GPU is claimed by naming :ffmpeg-nvenc beside the device request")
  (is (= #{:ffmpeg-cli :javacv} sut/auto-backends)
      ":auto picks between the two backends that are never scheduled")
  (testing "even when a device backend is the only other registered one"
    (is (= :ffmpeg-cli (sut/choose {} #{:ffmpeg-cli :javacv :ffmpeg-vaapi} true)))))
