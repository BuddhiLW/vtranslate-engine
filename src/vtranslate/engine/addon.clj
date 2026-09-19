(ns vtranslate.engine.addon
  "IAddon facade for vtranslate addons, so an addon's own addon ns is a few lines.

   An addon names its id, capabilities and a `register!` fn
   ([config] => Result, registering its providers, decorators and middleware
   into the engine's open registries). `facade` returns the two entry points the
   engine's addon loader (vtranslate.engine.addons) looks for:

     :init-as-addon!      [config] => IAddon record when hive-mcp is on the
                          classpath, else the registration Result
     :register-adapters!  [config] => the registration Result

   The IAddon protocol is resolved at runtime, so neither the engine nor the
   addon requires hive-mcp."
  (:require [hive-dsl.result :as r]))

(defrecord Addon [id capabilities register! state])

(defn- try-resolve [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

(defn addon-config
  "The addon's own config out of a loader spec map. => map"
  [config]
  (or (:addon/config config) (:config config) config {}))

(defn- register [addon config]
  ((:register! addon) (addon-config config)))

(defn- method-map []
  {:addon-id     (fn [this] (:id this))
   :addon-type   (fn [_] :native)
   :capabilities (fn [this] (:capabilities this))
   :initialize!  (fn [this config]
                   (let [config       (addon-config (or config (:config @(:state this))))
                         registration (register this config)]
                     (swap! (:state this) assoc :initialized? true :config config
                            :registration registration)
                     {:success? (r/ok? registration)
                      :metadata {:registration registration}}))
   :shutdown!    (fn [this] (swap! (:state this) assoc :initialized? false) nil)
   :tools        (fn [_] [])
   :schema-extensions (fn [_] {})
   :health       (fn [this]
                   (if (:initialized? @(:state this))
                     {:status :ok :details {:addon-id (:id this)
                                            :capabilities (:capabilities this)}}
                     {:status :down :details {:message (str (:id this) " not initialized")}}))
   :excluded-tools (fn [_] #{})
   :hooks        (fn [_] {})})

(defonce ^:private extended? (atom false))

(defn- ensure-extended! []
  (or @extended?
      (when-let [iaddon (try-resolve 'hive-mcp.addons.protocol/IAddon)]
        (extend Addon @iaddon (method-map))
        (reset! extended? true))))

(defn facade
  "Entry points for an addon. `spec` is {:id string :capabilities #{kw}
   :register! (fn [config] => Result)}.
   => {:init-as-addon! f :register-adapters! f}"
  [{:keys [id capabilities register!]}]
  (let [make (fn [config]
               (->Addon id capabilities register!
                        (atom {:initialized? false :config (addon-config config)})))]
    {:init-as-addon!     (fn [config]
                           (if (ensure-extended!)
                             (make config)
                             (register! (addon-config config))))
     :register-adapters! (fn [config] (register! (addon-config config)))}))
