(ns vtranslate.engine.providers.burner-registry
  "L3: hardsub-burner provider registry. Open multimethod: each burner
   adapter ns self-registers (defmethod resolve-burner <backend-key> ...).
   resolve-burner => (r/ok <IHardsubBurner impl>) | (r/err ...); :default
   fails loud with the known backend set, so a typo in :composer-opts
   :burn-backend is a wiring error, not a slow job."
  (:require [vtranslate.engine.providers.registry :as registry]))

(defmulti resolve-burner
  "Build an IHardsubBurner for `backend-key` (:javacv | :ffmpeg-cli |
   :ffmpeg-nvenc), reading
   its options from `opts` (the composer's :composer-opts)."
  (fn [backend-key _opts] backend-key))

(defn known
  "Registered burner backend keys, excluding the :default fallthrough."
  []
  (registry/known-keys resolve-burner))

(defmethod resolve-burner :default
  [backend-key _opts]
  (registry/unknown-error
   :burner backend-key resolve-burner
   "set :composer-opts :burn-backend to :auto, :ffmpeg-cli, :ffmpeg-nvenc or :javacv, or load an adapter ns that registers it"))
