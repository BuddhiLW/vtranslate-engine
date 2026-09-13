(ns vtranslate.engine.calc.burn-test
  "Backend choice table for the hardsub composer. Pure."
  (:require [clojure.test :refer [deftest is are]]
            [vtranslate.engine.calc.burn :as sut]))

(deftest binary-defaults-to-path-lookup
  (is (= "ffmpeg" (sut/binary {})))
  (is (= "ffmpeg" (sut/binary {:ffmpeg-bin "  "})))
  (is (= "/usr/bin/ffmpeg" (sut/binary {:ffmpeg-bin " /usr/bin/ffmpeg "}))))

(deftest unknown-requests-read-as-auto
  (are [opts want] (= want (sut/requested opts))
    {}                          :auto
    {:burn-backend :auto}       :auto
    {:burn-backend :ffmpeg-cli} :ffmpeg-cli
    {:burn-backend :javacv}     :javacv
    {:burn-backend :nvenc}      :auto
    {:burn-backend "javacv"}    :auto))

(deftest choice-table
  (are [opts available? want] (= want (sut/choose opts available?))
    {}                          true  :ffmpeg-cli
    {}                          false :javacv
    {:burn-backend :javacv}     true  :javacv
    {:burn-backend :ffmpeg-cli} false :ffmpeg-cli
    {:burn-backend :ffmpeg-cli} true  :ffmpeg-cli))
