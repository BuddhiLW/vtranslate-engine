(ns vtranslate.engine.collect.font-metrics
  "Collect: caption text width from java.awt font metrics, the ones the
   Java2D burner draws with. JDK only (no bytedeco), so it loads on any
   classpath, headless included."
  (:require [vtranslate.engine.calc.caption-layout :as layout]
            [vtranslate.engine.calc.captions :as captions])
  (:import [java.awt Font FontMetrics Graphics2D RenderingHints]
           [java.awt.image BufferedImage]))

(defn awt-font
  "The java.awt Font the Java2D burner sets for `requested` style at
   `font-size-px`."
  ^Font [requested font-size-px]
  (let [{:keys [font-family bold?]} (captions/style requested)]
    (Font. ^String (str font-family)
           (if bold? Font/BOLD Font/PLAIN)
           (int font-size-px))))

(defn- metrics-fn
  "(fn [font-size-px] -> FontMetrics) for `requested`, from an antialiased
   scratch Graphics2D (the rendering hint the burner sets), one per size."
  [requested]
  (let [img (BufferedImage. 1 1 BufferedImage/TYPE_INT_ARGB)
        g2  ^Graphics2D (.createGraphics img)
        cache (atom {})]
    (.setRenderingHint g2 RenderingHints/KEY_ANTIALIASING
                       RenderingHints/VALUE_ANTIALIAS_ON)
    (fn [size]
      (or (get @cache size)
          (let [fm (locking g2 (.getFontMetrics g2 (awt-font requested size)))]
            (swap! cache assoc size fm)
            fm)))))

(defn awt-measure
  "A calc.caption-layout `measure` for `requested` style over java.awt
   metrics, or nil when this JVM cannot produce them (no font at all)."
  [requested]
  (try
    (let [metrics (metrics-fn requested)]
      (.stringWidth ^FontMetrics (metrics 12) "Mg")
      (fn [font-size-px text]
        (.stringWidth ^FontMetrics (metrics (long font-size-px)) (str text))))
    (catch Throwable _ nil)))

(defn measure
  "The `measure` a burner lays captions out with: java.awt metrics when the
   JVM has them, else calc.caption-layout/approximate-measure."
  [requested]
  (or (awt-measure requested)
      (layout/approximate-measure requested)))
