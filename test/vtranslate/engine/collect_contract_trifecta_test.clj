(ns vtranslate.engine.collect-contract-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.collect-contract :as c]
            [vtranslate.engine.collect.units :as units]
            [vtranslate.engine.collect.audio :as audio]
            [vtranslate.engine.collect.media-port :as media]
            [vtranslate.engine.collect.protocols :as p]
            [vtranslate.engine.port.media :as domain]
            [vtranslate.engine.collect.process :as process]
            [vtranslate.engine.collect.ffmpeg-cli :as cli]
            [vtranslate.engine.collect.font-metrics :as fonts]
            [vtranslate.engine.collect.watermark :as mark]
            [vtranslate.engine.calc.ffmpeg-args :as args]))

(defn units-observation [us] {:in us :out (units/us->ms us)})
(deftrifecta microseconds units-observation
  {:golden-path "test/golden/collect-contract-units.edn"
   :cases {:negative -1 :zero 0 :below-half 1499 :half 1500 :above-half 1501}
   :gen (gen/choose -100000000 100000000)
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Milliseconds out)
                (if (neg? in) (zero? out) (<= (abs (- in (* 1000 out))) 500))))})

(defn facts-observation [[duration audio? video?]]
  (let [facts (cond-> {:container "mp4" :duration-ms duration :has-audio? audio?
                      :audio-codec "aac" :sample-rate 16000 :channels 1}
                (some? video?) (assoc :has-video? video?))]
    {:in facts :out (into {} (audio/to-probe-info facts))}))
(deftrifecta probe-info facts-observation
  {:golden-path "test/golden/collect-contract-probe-info.edn"
   :cases {:legacy [0 false nil] :audio [123 true false] :video [999 true true]}
   :gen (gen/tuple (gen/choose 0 1000000) gen/boolean (gen/elements [nil true false]))
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Facts out)
                (= (select-keys in [:container :duration-ms :has-audio? :audio-codec :has-video?]) out)))})

(def categories [:collect/source-not-found :fs/check-failed :collect/probe-failed
                 :collect/probe-timeout :collect/extract-failed :unmapped/error])
(defn media-error-observation [[cat message]]
  (let [input {:error cat :message message :marker 7}]
    {:in input :out (media/media-err "source" input)}))
(deftrifecta media-error-remapping media-error-observation
  {:golden-path "test/golden/collect-contract-media-error.edn"
   :cases {:missing [:collect/source-not-found "gone"] :probe [:collect/probe-timeout "late"]
           :extract [:collect/extract-failed "broken"] :unknown [:unmapped/error "retained"]}
   :gen (gen/tuple (gen/elements categories) gen/string-alphanumeric)
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/ErrorResult out)
                (= out (case (:error in)
                         (:collect/source-not-found :fs/check-failed)
                         {:error :error/source-unreadable :source-uri "source"}
                         (:collect/probe-failed :collect/probe-timeout)
                         {:error :error/probe-failed :reason (:message in)}
                         :collect/extract-failed
                         {:error :error/audio-extract-failed :reason (:message in)}
                         in))))})

(defn bridge-observation [x]
  (let [backend (reify p/IMediaProbe
                  (probe [_ uri] {:container uri :duration-ms 0 :has-audio? false :audio-codec ""})
                  p/IAudioExtractor (extract-audio [_ _ out _] out))
        bridge (media/collect-media-port backend)]
    {:in x :out {:probe (satisfies? domain/IMediaProbe bridge)
                 :extract (satisfies? domain/IAudioExtractor bridge)
                 :backend (identical? backend (:backend bridge))
                 :facts (p/probe backend x) :path (p/extract-audio backend "source" x {})}}))
(deftrifecta injected-collect-protocols bridge-observation
  {:golden-path "test/golden/collect-contract-bridge.edn"
   :cases {:empty "" :path "out.wav"} :gen gen/string-alphanumeric
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Bridge out) (m/validate c/Facts (:facts out))
                (every? true? ((juxt :probe :extract :backend) out))
                (= in (:path out) (get-in out [:facts :container]))))})

(defn process-observation [[exit text]]
  (let [runner (reify process/IProcessRunner
                 (exec! [_ argv] {:exit exit :stderr text})
                 (capture! [_ argv] {:exit exit :out text}))
        command ["fixture" text]]
    {:in [exit text] :out {:exec (process/exec! runner command)
                          :capture (process/capture! runner command)
                          :starts (process/starts? runner command)
                          :output (process/output-of runner command)
                          :constructor (satisfies? process/IProcessRunner (process/runner {}))}}))
(deftrifecta process-stubs process-observation
  {:golden-path "test/golden/collect-contract-process.edn"
   :cases {:success [0 "ok"] :failure [1 "bad"]}
   :gen (gen/tuple (gen/choose 0 8) gen/string-alphanumeric)
   :pred (fn [{:keys [in out]}]
           (let [[exit text] in]
             (and (m/validate c/ProcessExec (:exec out)) (m/validate c/ProcessCapture (:capture out))
                  (m/validate [:map [:starts :boolean] [:output [:maybe :string]] [:constructor :boolean]] out)
                  (= (zero? exit) (:starts out)) (:constructor out)
                  (= (when (zero? exit) text) (:output out))
                  (= exit (get-in out [:exec :exit]) (get-in out [:capture :exit])))))})

(defn process-throw-observation [security?]
  (let [fail (fn [] (if security? (throw (SecurityException. "blocked"))
                               (throw (java.io.IOException. "missing"))))
        runner (reify process/IProcessRunner (exec! [_ _] (fail)) (capture! [_ _] (fail)))]
    {:in security? :out [(process/starts? runner ["never-run"]) (process/output-of runner ["never-run"])]}))
(deftrifecta missing-executable process-throw-observation
  {:golden-path "test/golden/collect-contract-process-throw.edn"
   :cases {:io false :security true} :gen gen/boolean
   :pred (fn [{:keys [out]}] (and (m/validate [:tuple :boolean [:maybe :string]] out) (= [false nil] out)))})

(defn cli-constructor-observation [bin]
  (let [runner (process/runner {}) value (cli/ffmpeg-cli runner bin)]
    {:in bin :out {:bin (:bin value) :same-runner (identical? runner (:runner value))}}))
(deftrifecta cli-constructor cli-constructor-observation
  {:golden-path "test/golden/collect-contract-cli.edn"
   :cases {:plain "ffmpeg" :spaces "some path/ffmpeg"} :gen gen/string-alphanumeric
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:bin :string] [:same-runner :boolean]] out)
                (= in (:bin out)) (:same-runner out)))})

(defn font-observation [[size bold?]]
  (let [font (fonts/awt-font {:bold? bold?} size)]
    {:in [size bold?] :out {:size (.getSize font) :bold (.isBold font)}}))
(deftrifecta awt-font font-observation
  {:golden-path "test/golden/collect-contract-font.edn"
   :cases {:small [12 false] :bold [30 true]} :gen (gen/tuple (gen/choose 1 100) gen/boolean)
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Font out) (= in [(:size out) (:bold out)])))})

(defn measure-observation [size]
  (let [awt (fonts/awt-measure {}) measure (fonts/measure {})]
    {:in size :out {:awt? (boolean awt)
                   :empty (measure size "")
                   :single (measure size "M")
                   :double (measure size "MM")
                   :repeat (= (measure size "fixture") (measure size "fixture"))}}))

(deftrifecta font-measure measure-observation
  {:golden-path "test/golden/collect-contract-measure.edn"
   :cases {:small 12 :large 24} :gen (gen/choose 8 64)
   :pred (fn [{:keys [out]}]
           (and (m/validate [:map [:awt? :boolean] [:empty number?] [:single number?]
                                [:double number?] [:repeat :boolean]] out)
                (zero? (:empty out)) (pos? (:single out))
                (>= (:double out) (:single out)) (:repeat out)))})

(defn watermark-observation [size]
  (let [img (mark/render size 0 1.0)]
    {:in size :out {:width (.getWidth img) :height (.getHeight img)
                   :alpha (bit-and 255 (unsigned-bit-shift-right (.getRGB img 0 0) 24))
                   :center (.getRGB img (quot size 2) (quot size 2))}}))
(deftrifecta watermark-image watermark-observation
  {:golden-path "test/golden/collect-contract-watermark.edn"
   :cases {:small 16 :large 64} :gen (gen/choose 8 96)
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Image out) (= in (:width out) (:height out))
                (= 255 (:alpha out)) (neg? (:center out))))})

;; There is no soft-mux argv builder in this revision: soft-mux calls avformat
;; directly. The CLI's actual pure argv builder is burn-args; test its input
;; accounting here, including a second (watermark) input and awkward paths.
(defn argv-observation [[token marked? audio?]]
  (let [source (str "source/" token ".mp4") png (str "mark/" token ".png") out (str "out/" token)
        opts {:source source :out out :ass-path "captions.ass"
              :plan {:width 640 :height 360 :video-bitrate 1000000 :audio-bitrate 128000}
              :audio? audio? :watermark (when marked? {:png png :position ["10" "10"]})}]
    {:in {:source source :png png :out out :marked marked? :audio audio?}
     :out (args/burn-args opts)}))
(deftrifecta ffmpeg-input-accounting argv-observation
  {:golden-path "test/golden/collect-contract-argv.edn"
   :cases {:plain ["video" false true] :mark ["space ; quote'" true false]}
   :gen (gen/tuple gen/string-alphanumeric gen/boolean gen/boolean)
   :pred (fn [{:keys [in out]}]
           (let [inputs (mapv second (filter #(= "-i" (first %)) (partition 2 1 out)))]
             (and (m/validate c/Argv out)
                  (= (cond-> [(:source in)] (:marked in) (conj (:png in))) inputs)
                  (= (:out in) (last out))
                  (= (if (:audio in) 0 1) (count (filter #{"-an"} out))))))})
