(ns vtranslate.engine.adapters.composer.both
  "IVideoComposer that produces BOTH a softsubbed and a hardsubbed output from one
   compose: delegates to the :soft and :hard composers, resolved through the
   registry so this ns loads WITHOUT bytedeco. Self-registers (defmethod
   resolve-composer :both) — OCP."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.calc.paths :as paths]
            [vtranslate.engine.providers.composer-registry :as reg]))

(defn- base-output
  "Sink the variants derive from: the boundary-owned :output-uri, else the
   \".subbed.mp4\" sibling convention."
  [video-source compose-opts]
  (or (:output-uri compose-opts)
      (paths/sibling-output video-source ".subbed.mp4")))

(defrecord BothComposer [soft hard]
  p.comp/IVideoComposer
  (compose [_ video-source subtitle-track compose-opts]
    (let [outs (paths/both-outputs (base-output video-source compose-opts))]
      (r/let-ok [_ (p.comp/compose soft video-source subtitle-track
                                   {:output-uri (:soft outs)})
                 _ (p.comp/compose hard video-source subtitle-track
                                   {:output-uri (:hard outs)})]
                (r/ok {:output-uris outs})))))

(defn make-composer
  "Build a BothComposer over the resolved `soft` and `hard` IVideoComposers."
  [soft hard]
  (->BothComposer soft hard))

(defmethod reg/resolve-composer :both [_ config]
  (r/let-ok [soft (reg/resolve-composer :soft config)
             hard (reg/resolve-composer :hard config)]
            (r/ok (make-composer soft hard))))
