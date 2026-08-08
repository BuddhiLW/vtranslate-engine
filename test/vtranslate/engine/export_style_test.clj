(ns vtranslate.engine.export-style-test
  "Caption typography/placement and output quality — the pure half of burn-in.

   Both are expressed as FRACTIONS of the frame rather than pixels, because the
   same style has to render identically at 360p and 4K and has to be previewable
   in a browser that knows nothing but percentages."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [vtranslate.engine.calc.captions :as captions]
            [vtranslate.engine.calc.encoding :as encoding]))

;; ---------------------------------------------------------------------------
;; Quality: what comes back must be what went in, unless asked otherwise.
;; ---------------------------------------------------------------------------

(deftrifecta encoding-plan-rule encoding/plan
  {:golden-path "test/golden/encoding-plan.edn"
   :apply? true
   :cases
   {;; The default: hand back exactly what arrived.
    :source-1080p
    [{:source-width 1920 :source-height 1080 :source-video-bitrate 8000000
      :source-audio-bitrate 192000 :frame-rate 30.0}]
    ;; 4K passes through untouched too.
    :source-4k
    [{:source-width 3840 :source-height 2160 :source-video-bitrate 45000000
      :source-audio-bitrate 256000 :frame-rate 60.0 :quality :source}]
    ;; An explicit downscale, with the bitrate following the pixel count down.
    :downscale-to-720
    [{:source-width 1920 :source-height 1080 :source-video-bitrate 8000000
      :source-audio-bitrate 192000 :frame-rate 30.0 :quality :720p}]
    ;; A preset above the source must not upscale.
    :no-upscaling
    [{:source-width 640 :source-height 360 :source-video-bitrate 800000
      :source-audio-bitrate 96000 :frame-rate 25.0 :quality :1080p}]
    ;; Odd source height: H.264 needs even dimensions.
    :odd-dimensions
    [{:source-width 1919 :source-height 1079 :source-video-bitrate 6000000
      :frame-rate 30.0 :quality :720p}]
    ;; The container reported no bitrate, so one has to be estimated.
    :unknown-source-bitrate
    [{:source-width 1280 :source-height 720 :source-video-bitrate 0
      :source-audio-bitrate 0 :frame-rate 30.0}]}
   :gen (gen/tuple
         (gen/hash-map :source-width (gen/choose 160 3840)
                       :source-height (gen/choose 120 2160)
                       :source-video-bitrate (gen/choose 0 50000000)
                       :source-audio-bitrate (gen/choose 0 320000)
                       :frame-rate (gen/elements [24.0 25.0 30.0 50.0 60.0])
                       :quality (gen/elements [:source :1080p :720p :480p])))
   :pred #(and (pos-int? (:width %))
               (pos-int? (:height %))
               (even? (:width %))
               (even? (:height %))
               (>= (:video-bitrate %) encoding/min-video-bitrate)
               (pos-int? (:audio-bitrate %)))
   :mutations
   [;; The bug this whole namespace exists to prevent: no bitrate set, so the
    ;; encoder's default silently destroys every video above SD.
    ["encoder-default-bitrate"
     (fn [args] (assoc (encoding/plan args) :video-bitrate encoding/min-video-bitrate))]
    ;; Honours a preset larger than the source, inventing detail and paying to
    ;; encode a file nobody asked to be bigger.
    ["upscales"
     (fn [{:keys [source-width source-height quality] :as args}]
       (let [target (get encoding/presets quality)]
         (merge (encoding/plan args)
                (when target
                  {:height target
                   :width (long (* source-width (/ (double target) source-height)))}))))]
    ;; Forgets that H.264 wants even dimensions.
    ["odd-dimensions-allowed"
     (fn [{:keys [source-width source-height quality] :as args}]
       (let [target (get encoding/presets quality)]
         (if (and target (< target source-height))
           (assoc (encoding/plan args)
                  :height target
                  :width (long (Math/round (* (double source-width)
                                              (/ (double target) source-height)))))
           (encoding/plan args))))]]})

(deftest a-source-quality-export-returns-what-it-was-given
  (let [plan (encoding/plan {:source-width 3840 :source-height 2160
                             :source-video-bitrate 45000000
                             :source-audio-bitrate 256000
                             :frame-rate 60.0})]
    (is (= [3840 2160] [(:width plan) (:height plan)]))
    (is (= 45000000 (:video-bitrate plan))
        "a re-encode must not quietly cost the customer their bitrate")
    (is (= 256000 (:audio-bitrate plan)))
    (is (false? (:rescaled? plan)))))

(deftest a-downscale-drops-the-bitrate-with-the-pixels
  (let [plan (encoding/plan {:source-width 1920 :source-height 1080
                             :source-video-bitrate 8000000
                             :frame-rate 30.0 :quality :720p})]
    (is (= [1280 720] [(:width plan) (:height plan)]))
    (is (true? (:rescaled? plan)))
    (is (< (:video-bitrate plan) 8000000)
        "720p at a 1080p bitrate wastes bytes for no visible gain")
    (is (> (:video-bitrate plan) 3000000)
        "and must not collapse to the encoder's default either")))

;; ---------------------------------------------------------------------------
;; Captions: fractions of the frame, so a preview can be honest.
;; ---------------------------------------------------------------------------

(deftest caption-size-scales-with-the-frame-not-the-screen
  (testing "the same style reads the same at every resolution"
    (is (= 45 (captions/font-size-px 1080 {})))
    (is (= 90 (captions/font-size-px 2160 {})))
    (is (= 18 (captions/font-size-px 240 {}))
        "clamped, because 4% of a tiny frame is unreadable"))
  (testing "an explicit pixel size overrides the fraction"
    (is (= 64 (captions/font-size-px 1080 {:size-px 64})))))

(deftest caption-placement-is-a-fraction-so-it-can-clear-a-logo
  (is (= 1015 (captions/block-bottom-px 1080 {})))
  (is (= 540 (captions/block-bottom-px 1080 {:y-position 0.5})))
  (testing "a fraction outside the frame is clamped rather than drawn off it"
    (is (= 1080 (captions/block-bottom-px 1080 {:y-position 2.0})))
    (is (= 0 (captions/block-bottom-px 1080 {:y-position -1.0})))))

(deftest a-second-line-pushes-the-block-up-not-off
  (let [bottom (captions/block-bottom-px 1080 {})]
    (testing "the last line always sits on the block's bottom"
      (is (= bottom (captions/baseline-px 1080 {} 1 50 0)))
      (is (= bottom (captions/baseline-px 1080 {} 2 50 1)))
      (is (= bottom (captions/baseline-px 1080 {} 3 50 2))))
    (testing "earlier lines stack upward from it"
      (is (= (- bottom 50) (captions/baseline-px 1080 {} 2 50 0)))
      (is (= (- bottom 100) (captions/baseline-px 1080 {} 3 50 0))))))

(deftest a-partly-filled-style-does-not-blank-the-rest
  (testing "nils are absent, not instructions to clear a default"
    (let [resolved (captions/style {:text-color nil :y-position 0.8})]
      (is (= "#ffffff" (:text-color resolved)))
      (is (= 0.8 (:y-position resolved)))
      (is (= "SansSerif" (:font-family resolved))))))

(deftest colours-survive-anything-the-form-can-send
  (is (= [255 255 255] (captions/rgb "#ffffff")))
  (is (= [0 0 0] (captions/rgb "#000000")))
  (is (= [255 204 0] (captions/rgb "#fc0")) "shorthand hex expands")
  (is (= [18 52 86] (captions/rgb "123456")) "a missing # is tolerated")
  (testing "an unusable colour falls back rather than failing a paid job"
    (is (= [255 255 255] (captions/rgb "rebeccapurple")))
    (is (= [255 255 255] (captions/rgb nil)))
    (is (= [255 255 255] (captions/rgb "#zzzzzz")))))

(deftest the-plate-can-be-turned-off-entirely
  (is (= 140 (captions/plate-alpha {:plate-opacity 0.55})))
  (is (= 0 (captions/plate-alpha {:plate-opacity 0}))
      "zero opacity leaves outlined text over the picture, no plate drawn")
  (is (= 255 (captions/plate-alpha {:plate-opacity 1.0})))
  (testing "out-of-range opacity is clamped, never wrapped"
    (is (= 255 (captions/plate-alpha {:plate-opacity 4.0})))
    (is (= 0 (captions/plate-alpha {:plate-opacity -1.0})))))
