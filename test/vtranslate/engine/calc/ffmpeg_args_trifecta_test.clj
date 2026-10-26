(ns vtranslate.engine.calc.ffmpeg-args-trifecta-test
  "Golden decisions, generated output contracts, and killed counterexamples."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-render :as s]
            [vtranslate.engine.calc.ffmpeg-args :as f]))

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

(deftrifecta filter-escape-contract f/filter-escape
  {:golden-path "test/golden/calc-render-ffmpeg-args-filter-escape.edn"
   :apply? true
   :cases {:plain ["captions.ass"] :special ["C:\\temp/a:b,c;[x]'s.ass"] :nil [nil]}
   :gen (gen/tuple text-gen)
   :pred #(m/validate :string %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta video-filter-contract f/video-filter
  {:golden-path "test/golden/calc-render-ffmpeg-args-video-filter.edn"
   :apply? true
   :cases {:plain [plan "a.ass"] :scaled [(assoc plan :rescaled? true) "a.ass"] :suffix [plan "a.ass" "format=nv12,hwupload"]}
   :gen (gen/tuple plan-gen text-gen (gen/elements [nil "" "format=nv12,hwupload"]))
   :pred #(m/validate :string %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta watermark-graph-contract f/watermark-graph
  {:golden-path "test/golden/calc-render-ffmpeg-args-watermark-graph.edn"
   :apply? true
   :cases {:plain [plan "a.ass" ["W-w-12" "12"]] :suffix [plan "a.ass" ["W-w-12" "12"] "hwupload"]}
   :gen (gen/tuple plan-gen text-gen (gen/return ["W-w-12" "12"]) (gen/elements [nil "hwupload"]))
   :pred #(m/validate :string %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta encoders-from-contract f/encoders-from
  {:golden-path "test/golden/calc-render-ffmpeg-args-encoders-from.edn"
   :apply? true
   :cases {:defaults [nil] :override [{:encoders {:libx264 {:default-preset "slow"}}}] :custom [{:encoders {:custom {:codec "custom"}}}]}
   :gen (gen/tuple (gen/fmap #(hash-map :encoders {:custom {:codec %}}) text-gen))
   :pred #(m/validate s/EncoderTable %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta encoder-spec-contract f/encoder-spec
  {:golden-path "test/golden/calc-render-ffmpeg-args-encoder-spec.edn"
   :apply? true
   :cases {:cpu [:libx264] :nvenc [:h264-nvenc] :vaapi [:h264-vaapi] :custom [{:custom {:codec "mine"}} :custom]}
   :gen (gen/tuple encoder-gen)
   :pred #(m/validate s/EncoderSpec %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta encoder-preset-contract f/encoder-preset
  {:golden-path "test/golden/calc-render-ffmpeg-args-encoder-preset.edn"
   :apply? true
   :cases {:default [:libx264 {}] :override [:h264-nvenc {:nvenc-preset "p7"}] :none [:h264-vaapi {}]}
   :gen (gen/tuple encoder-gen (gen/fmap #(hash-map :preset % :nvenc-preset %) text-gen))
   :pred #(m/validate [:maybe :string] %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta burn-args-contract f/burn-args
  {:golden-path "test/golden/calc-render-ffmpeg-args-burn-args.edn"
   :apply? true
   :cases {:cpu [{:source "in.mp4" :out "out" :ass-path "a.ass" :plan plan}] :silent [{:source "in" :out "out" :ass-path "a" :plan plan :audio? false}] :marked-vaapi [{:source "in" :out "out" :ass-path "a" :plan plan :encoder :h264-vaapi :watermark {:png "mark.png" :position ["W-w-12" "12"]}}]}
   :gen (gen/tuple (gen/fmap (fn [[p e a]] {:source "in.mp4" :out "out" :ass-path "a.ass" :plan p :encoder e :audio? a}) (gen/tuple plan-gen encoder-gen gen/boolean)))
   :pred #(m/validate s/Argv %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta probe-args-contract f/probe-args
  {:golden-path "test/golden/calc-render-ffmpeg-args-probe-args.edn"
   :apply? true
   :cases {:video [{:source "in" :kind :video}] :audio [{:bin "/bin/ffprobe" :source "in" :kind :audio}]}
   :gen (gen/tuple (gen/fmap (fn [[s k]] {:source s :kind k}) (gen/tuple text-gen (gen/elements [:video :audio]))))
   :pred #(m/validate s/Argv %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta parse-frame-rate-contract f/parse-frame-rate
  {:golden-path "test/golden/calc-render-ffmpeg-args-parse-frame-rate.edn"
   :apply? true
   :cases {:ratio ["30000/1001"] :integer ["24"] :zero-denominator ["1/0"] :garbage ["N/A"] :empty [nil]}
   :gen (gen/tuple (gen/one-of [text-gen (gen/fmap (fn [[n d]] (str n "/" d)) (gen/tuple (gen/choose 0 120000) (gen/choose 0 1001)))]))
   :pred #(m/validate [:maybe :double] %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta parse-video-probe-contract f/parse-video-probe
  {:golden-path "test/golden/calc-render-ffmpeg-args-parse-video-probe.edn"
   :apply? true
   :cases {:valid ["1920,1080,30000/1001,6000000"] :missing-rate ["640,480,N/A,N/A"] :absent [nil] :invalid ["0,-1,30,1"]}
   :gen (gen/tuple (gen/one-of [text-gen (gen/fmap (fn [[w h]] (str w "," h ",30/1,6000000")) (gen/tuple dimension-gen dimension-gen))]))
   :pred #(m/validate s/VideoProbe %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta parse-audio-probe-contract f/parse-audio-probe
  {:golden-path "test/golden/calc-render-ffmpeg-args-parse-audio-probe.edn"
   :apply? true
   :cases {:stereo ["2,128000"] :unknown-rate ["1,N/A"] :absent [nil] :zero ["0,128000"]}
   :gen (gen/tuple (gen/one-of [text-gen (gen/fmap #(str % ",128000") (gen/choose 0 8))]))
   :pred #(m/validate s/AudioProbe %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta listing-args-contract f/listing-args
  {:golden-path "test/golden/calc-render-ffmpeg-args-listing-args.edn"
   :apply? true
   :cases {:filters [{:listing :filters}] :encoders [{:listing :encoders :bin "/bin/ffmpeg"}]}
   :gen (gen/tuple (gen/fmap #(hash-map :listing %) (gen/elements [:filters :encoders])))
   :pred #(m/validate s/Argv %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta lists-name?-contract f/lists-name?
  {:golden-path "test/golden/calc-render-ffmpeg-args-lists-name?.edn"
   :apply? true
   :cases {:present [" T.. subtitles Render subtitles
" "subtitles"] :substring [" T.. subtitles_extra Nope
" "subtitles"] :absent [nil "subtitles"] :regex [" V... a.b codec
" "a.b"]}
   :gen (gen/tuple text-gen text-gen)
   :pred #(m/validate :boolean %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta capabilities-for-contract f/capabilities-for
  {:golden-path "test/golden/calc-render-ffmpeg-args-capabilities-for.edn"
   :apply? true
   :cases {:cpu [:libx264] :hardware [:h264-vaapi] :custom [{:custom {:codec "mine"}} :custom]}
   :gen (gen/tuple encoder-gen)
   :pred #(m/validate s/Capabilities %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta capable?-contract f/capable?
  {:golden-path "test/golden/calc-render-ffmpeg-args-capable?.edn"
   :apply? true
   :cases {:yes [{:filters " T.. subtitles Render
" :encoders " V... libx264 Encode
"}] :no [nil] :hardware [{:filters " T.. subtitles Render
" :encoders " V... h264_vaapi Encode
"} :h264-vaapi]}
   :gen (gen/tuple (gen/fmap (fn [[f e]] {:filters f :encoders e}) (gen/tuple text-gen text-gen)) encoder-gen)
   :pred #(m/validate :boolean %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta encoder-probe-args-contract f/encoder-probe-args
  {:golden-path "test/golden/calc-render-ffmpeg-args-encoder-probe-args.edn"
   :apply? true
   :cases {:cpu [{:encoder :libx264}] :hardware [{:encoder :h264-vaapi}] :nvenc [{:encoder :h264-nvenc}]}
   :gen (gen/tuple (gen/fmap #(hash-map :encoder %) encoder-gen))
   :pred #(m/validate s/Argv %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta hardware-unavailable?-contract f/hardware-unavailable?
  {:golden-path "test/golden/calc-render-ffmpeg-args-hardware-unavailable?.edn"
   :apply? true
   :cases {:cuda ["Cannot load libcuda.so.1"] :vaapi ["Failed to open VAAPI device"] :media ["Invalid data found"] :empty [nil]}
   :gen (gen/tuple (gen/one-of [text-gen (gen/elements f/hardware-open-failures)]))
   :pred #(m/validate :boolean %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta probe-binary-contract f/probe-binary
  {:golden-path "test/golden/calc-render-ffmpeg-args-probe-binary.edn"
   :apply? true
   :cases {:bare ["ffmpeg"] :pinned ["/opt/bin/ffmpeg.exe"] :unknown ["avconv"] :nil [nil]}
   :gen (gen/tuple text-gen)
   :pred #(m/validate :string %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftest unknown-encoder-is-refused
  (doseq [call [(fn [] (f/encoder-spec :unknown))
                (fn [] (f/encoder-preset :unknown {}))
                (fn [] (f/capabilities-for :unknown))
                (fn [] (f/burn-args {:plan plan :encoder :unknown}))
                (fn [] (f/encoder-probe-args {:encoder :unknown}))]]
    (is (= :error/unknown-encoder
           (try (call) nil
                (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))))
