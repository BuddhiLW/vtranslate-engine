(ns vtranslate.engine.calc.burn-trifecta-test
  "Golden decisions, generated output contracts, and killed counterexamples."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-render :as s]
            [vtranslate.engine.calc.burn :as sut]))

(def ^:private text-gen
  (gen/one-of [gen/string-alphanumeric
               (gen/elements ["" "a b c" "a:b,c;[x]'\\path" "Olá 世界"])]))
(def ^:private dimension-gen (gen/choose 2 7680))
(def ^:private quality-gen (gen/elements [:source :360p :720p :1080p :2160p nil]))
(def ^:private encoder-gen (gen/elements [:libx264 :h264-nvenc :h264-vaapi]))
(def ^:private plan
  {:width 1280 :height 720 :video-bitrate 4000000 :audio-bitrate 128000 :rescaled? false})
(def ^:private plan-gen
  (gen/fmap #(assoc plan :rescaled? %) gen/boolean))

(deftrifecta binary-contract sut/binary
  {:golden-path "test/golden/calc-render-burn-binary.edn"
   :apply? true
   :cases {:default [nil] :blank [{:ffmpeg-bin "  "}] :pinned [{:ffmpeg-bin " /opt/ffmpeg "}]}
   :gen (gen/tuple (gen/fmap #(hash-map :ffmpeg-bin %) text-gen))
   :pred #(m/validate :string %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta requested-contract sut/requested
  {:golden-path "test/golden/calc-render-burn-requested.edn"
   :apply? true
   :cases {:auto [{} []] :registered [{:burn-backend :custom} [:custom]] :explicit-auto [{:burn-backend :auto} []]}
   :gen (gen/fmap (fn [k] [{:burn-backend k} [:custom :javacv]]) (gen/elements [nil :auto :custom :javacv]))
   :pred #(m/validate :keyword %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta choose-contract sut/choose
  {:golden-path "test/golden/calc-render-burn-choose.edn"
   :apply? true
   :cases {:available [{} #{} true] :unavailable [{} #{} false] :explicit [{:burn-backend :custom} #{:custom} false]}
   :gen (gen/fmap (fn [[k a]] [{:burn-backend k} #{:custom} a]) (gen/tuple (gen/elements [:auto :custom]) gen/boolean))
   :pred #(m/validate :keyword %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftest unknown-backend-is-refused
  (doseq [f [(fn [] (sut/requested {:burn-backend :unknown} #{:custom}))
             (fn [] (sut/choose {:burn-backend :unknown} #{:custom} true))]]
    (is (= :error/unknown-burn-backend
           (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))))
