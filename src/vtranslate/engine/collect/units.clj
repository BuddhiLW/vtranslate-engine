(ns vtranslate.engine.collect.units
  "Pure unit conversions for the Collect boundary. No effects, no native deps —
   so the conversion the whole pipeline's timing correctness rests on is testable
   without loading bytedeco. ffmpeg counts time in microseconds; the domain
   counts integer milliseconds (TimeRange/Timecode), and this is the single place
   that bridges the two.")

(def ^:const us-per-ms 1000)

(defn us->ms
  "ffmpeg microseconds -> non-negative integer milliseconds (round-to-nearest).
   Negative inputs (ffmpeg's AV_NOPTS / unknown duration) clamp to 0 so a bad
   probe never yields a negative TimeRange downstream."
  ^long [^long us]
  (if (neg? us)
    0
    (Math/round (/ (double us) us-per-ms))))
