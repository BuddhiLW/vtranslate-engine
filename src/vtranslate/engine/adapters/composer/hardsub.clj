(ns vtranslate.engine.adapters.composer.hardsub
  "IVideoComposer that BURNS subtitle cues into the video picture (hardsub),
   re-encoding H.264/AAC through an IHardsubBurner. Self-registers
   (defmethod resolve-composer :hard).

   Boundary: the burner is chosen once, at wiring, by calc.burn from
   :composer-opts, the burner registry's own key set, and one observation
   (whether the system ffmpeg is capable), then resolved through that
   registry. Requiring the
   burner adapters here registers them, which is what makes the hardsub
   composer load ONLY on the :ffmpeg classpath (the JavaCV burner imports
   bytedeco)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.adapters.burner.ffmpeg-cli :as cli-burner]
            [vtranslate.engine.adapters.burner.ffmpeg-hw]
            [vtranslate.engine.adapters.burner.ffmpeg-nvenc]
            [vtranslate.engine.adapters.burner.javacv]
            [vtranslate.engine.adapters.composer.support :as support]
            [vtranslate.engine.calc.burn :as burn]
            [vtranslate.engine.calc.paths :as paths]
            [vtranslate.engine.collect.ffmpeg-cli :as cli]
            [vtranslate.engine.port.burner :as p.burner]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.providers.burner-registry :as burners]
            [vtranslate.engine.providers.composer-registry :as reg]))

(defrecord HardsubComposer [opts backend burner]
  p.comp/IVideoComposer
  (compose [_ video-source subtitle-track compose-opts]
    ;; Per-job style wins over the deployment's defaults: caption size and
    ;; placement are a property of the video being made, not of the pod.
    (let [style (merge opts (dissoc compose-opts :output-uri))
          out   (or (:output-uri compose-opts)
                    (paths/sibling-output video-source ".subbed.mp4"))]
      (r/try-effect* :error/compose-failed
        (do (support/atomically out #(p.burner/burn! burner video-source % subtitle-track style))
            {:output-uri out})))))

(defn- ffmpeg-capable?
  "The one observation the choice needs, made only when :auto asks for it:
   an explicit backend is honoured without probing."
  [opts known]
  (and (= :auto (burn/requested opts known))
       (cli/capable? (cli-burner/executables opts))))

(defn make-composer
  "Build a HardsubComposer from config's :composer-opts. Anything absent is
   filled by calc.captions defaults at draw time. The backend is chosen and
   resolved here, once per wiring, not per job; `:backend` on the record
   says which won. => (r/ok composer) | (r/err ...) when no burner is
   registered for the chosen backend."
  [config]
  ;; Not try-effect*: that wraps its body in an ok, and the body already
  ;; answers a Result. The throw being caught is calc.burn's loud refusal of
  ;; an unregistered backend key, which must reach the caller as an err.
  (try
    (let [opts    (get config :composer-opts {})
          ;; The registered set crosses the boundary HERE. calc.burn must not
          ;; require the registry, so the adapter that has both hands it
          ;; over, and a backend an adapter registered can never be unknown
          ;; to the code that chooses one.
          known   (burners/known)
          backend (burn/choose opts known (ffmpeg-capable? opts known))]
      (r/let-ok [burner (burners/resolve-burner backend opts)]
        (r/ok (->HardsubComposer opts backend burner))))
    (catch clojure.lang.ExceptionInfo e
      ;; r/err merges its data over {:error category}: the category is
      ;; lifted out of the ex-data and the data must not keep a copy.
      (r/err (get (ex-data e) :error :error/compose-wiring-failed)
             (assoc (dissoc (ex-data e) :error) :message (ex-message e))))))

(defmethod reg/resolve-composer :hard [_ config]
  (make-composer config))
