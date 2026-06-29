(ns vtranslate.engine.adapters.codec.timecode
  "Pure SRT/WebVTT timecode <-> integer-millisecond conversions. The two wire
   forms differ only in the fractional separator (',' for SRT, '.' for WebVTT)
   and SRT's mandatory hour field; parsing accepts either separator and an
   optional hour. The single place wire timecodes meet the domain's integer ms."
  (:require [clojure.string :as str]))

(def ^:private ms-per-hour   3600000)
(def ^:private ms-per-minute 60000)
(def ^:private ms-per-second 1000)

(defn ms->clock
  "Integer ms -> \"HH:MM:SS<sep>mmm\" (sep = \",\" for SRT, \".\" for WebVTT)."
  [ms sep]
  (format "%02d:%02d:%02d%s%03d"
          (quot ms ms-per-hour)
          (quot (rem ms ms-per-hour) ms-per-minute)
          (quot (rem ms ms-per-minute) ms-per-second)
          sep
          (rem ms ms-per-second)))

(def ^:private clock-re
  #"(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})")

(defn clock->ms
  "\"[HH:]MM:SS[,.]mmm\" -> integer ms, or nil when it does not match."
  [s]
  (when-let [[_ h m sec ms] (re-matches clock-re (str/trim s))]
    (+ (* (if h (parse-long h) 0) ms-per-hour)
       (* (parse-long m) ms-per-minute)
       (* (parse-long sec) ms-per-second)
       (parse-long ms))))
