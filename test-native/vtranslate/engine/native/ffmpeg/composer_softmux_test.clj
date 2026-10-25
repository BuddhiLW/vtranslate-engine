(ns vtranslate.engine.native.ffmpeg.composer-softmux-test
  "Native contract for composer.softmux (make-composer + SoftMuxComposer) over the
   raw avformat mux in collect.ffmpeg. Run under :ffmpeg (see tests-native.edn).

   Relational property over {:cues :language :output?}:
   - the source's audio and video are STREAM-COPIED: same codecs, same packet
     counts, in the same stream slots;
   - exactly one mov_text stream is appended, tagged with the ISO 639-2 code of
     the track's BCP-47 language;
   - reading the subtitle samples back and dropping the muxer's empty gap
     samples gives the cues exactly (start, end, lines joined by newline);
   - the result names the requested output (or the .subbed.mp4 sibling) and no
     .part-* temp remains."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.composer.softmux :as softmux]
            [vtranslate.engine.native.ffmpeg.generators :as g]
            [vtranslate.engine.native.media-fixture :as fx]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.providers.composer-registry :as reg]
            [vtranslate.engine.schema.native-media :as nm]
            [vtranslate.engine.schema.port-values :as pv]))

(defn softmux-observation
  "Soft-mux `cues` (track language `language`) into a fresh fixture."
  [{:keys [cues language output?] :as in}]
  (fx/with-temp-dir [dir]
    (let [src    (fx/synth-source! dir)
          out    (if output? (str dir "/chosen.mp4") (str dir "/source.subbed.mp4"))
          result (p.comp/compose (softmux/make-composer {:composer-opts {}}) src
                                 (fx/rendered-track cues language)
                                 (cond-> {} output? (assoc :output-uri out)))]
      {:in  in
       :out {:result   (update-in result [:ok :output-uri] #(some-> % (str/replace dir "<tmp>")))
             :source   (fx/container-report src)
             :output   (fx/container-report out)
             :partials (fx/partials dir)}})))

(defn- holds? [{{:keys [cues language output?]} :in
                {:keys [result source output partials] :as out} :out}]
  (let [n-src (count (:streams source))
        sub   (get (:streams output) n-src)]
    (and (m/validate nm/CueSpecs cues)
         (m/validate nm/SoftMuxObservation out)
         (m/validate pv/ComposeResult result)
         (= (if output? "<tmp>/chosen.mp4" "<tmp>/source.subbed.mp4")
            (get-in result [:ok :output-uri]))
         (= [] partials)
         (= (inc n-src) (count (:streams output)))
         (= (:streams source) (subvec (:streams output) 0 n-src))
         (= (:packets source) (dissoc (:packets output) n-src))
         (= {:type 3 :codec "mov_text" :language (g/languages language)} sub)
         (= [] (:subtitles source))
         (= (mapv (fn [c] {:start-ms (:start-ms c) :end-ms (:end-ms c)
                           :text (str/join "\n" (:lines c))})
                  cues)
            (filterv (comp seq :text) (:subtitles output))))))

(deftrifecta compose softmux-observation
  {:golden-path "test/golden/native-composer-softmux.edn"
   :cases {:pt-br-explicit {:cues [{:start-ms 0 :end-ms 400 :lines ["Olá"]}
                                   {:start-ms 450 :end-ms 1000 :lines ["a" "über"]}]
                            :language "pt-BR" :output? true}
           :en-sibling     {:cues [{:start-ms 100 :end-ms 300 :lines ["hello world"]}]
                            :language "en" :output? false}
           :zh-adjacent    {:cues [{:start-ms 0 :end-ms 500 :lines ["一"]}
                                   {:start-ms 500 :end-ms 900 :lines ["二"]}]
                            :language "zh-CN" :output? true}}
   :gen (gen/hash-map :cues g/cues-gen :language g/language-gen :output? gen/boolean)
   :num-tests 20
   :pred holds?})

(deftrifecta container-language softmux/container-language
  {:golden-path "test/golden/native-softmux-container-language.edn"
   :cases {:en "en" :pt-br "pt-BR" :zh-cn "zh-CN" :blank "" :nil nil
           :und "und" :unknown "xx" :three-letter "deu"}
   :gen (gen/one-of [g/language-gen gen/string-alphanumeric (gen/return nil)])
   :pred #(m/validate [:re #"^[a-z]{3}$"] %)})

(deftest soft-is-registered-and-refusals-are-results
  (testing ":soft resolves through the composer registry"
    (is (instance? vtranslate.engine.adapters.composer.softmux.SoftMuxComposer
                   (:ok (reg/resolve-composer :soft {:composer-opts {}})))))
  (testing "make-composer keeps the deployment opts"
    (is (= {:x 1} (:opts (softmux/make-composer {:composer-opts {:x 1}})))))
  (testing "a missing source is a compose-failed Result and leaves no output or temp"
    (fx/with-temp-dir [dir]
      (let [r (p.comp/compose (softmux/make-composer {}) (str dir "/missing.mp4")
                              (fx/rendered-track [{:start-ms 0 :end-ms 100 :lines ["x"]}] "en")
                              {:output-uri (str dir "/out.mp4")})]
        (is (m/validate pv/ComposeResult r))
        (is (= :error/compose-failed (:error r)))
        (is (= [] (fx/partials dir)))
        (is (not (.exists (java.io.File. (str dir "/out.mp4")))))))))
