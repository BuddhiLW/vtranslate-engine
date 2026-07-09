(ns vtranslate.engine.addons)

(defn- keyword->symbol [x]
  (symbol (if-let [n (namespace x)]
            (str n "." (name x))
            (name x))))

(defn- ns-symbol [x]
  (cond
    (symbol? x) x
    (string? x) (symbol x)
    (keyword? x) (keyword->symbol x)
    :else nil))

(def addon-catalog
  {:vtranslate/context {:ns 'vtranslate.context.addon
                        :classpath/aliases [:addon-context]}})

(declare catalog-id)

(defn- catalog-entry [spec]
  (some-> (catalog-id spec) addon-catalog))

(defn- catalog-id [spec]
  (cond
    (keyword? spec) spec
    (map? spec) (or (:id spec) (:addon/id spec) (:catalog spec)
                    (when (keyword? (:addon spec)) (:addon spec)))
    :else nil))

(defn normalize-spec [spec]
  (let [entry (catalog-entry spec)
        id (catalog-id spec)]
    (cond
      (keyword? spec)
      (assoc (or entry {:ns (ns-symbol spec)})
             :id id
             :config {})

      (or (symbol? spec) (string? spec))
      {:ns (ns-symbol spec) :config {}}

      (map? spec)
      (let [addon-ns (or (:ns spec) (:addon/ns spec)
                         (when-not (keyword? (:addon spec)) (:addon spec))
                         (:ns entry) (:addon/ns entry))]
        (cond-> (assoc (merge entry spec)
                       :ns (ns-symbol addon-ns)
                       :config (or (:config spec) (:addon/config spec) {}))
          id (assoc :id id)))

      :else
      {:ns nil :config {} :invalid spec})))

(defn- init-symbol [addon-ns fn-name]
  (symbol (str addon-ns) fn-name))

(defn- init-var [addon-ns]
  (or (requiring-resolve (init-symbol addon-ns "init-as-addon!"))
      (requiring-resolve (init-symbol addon-ns "register-adapters!"))))

(defn- safe-resolve [sym]
  (try
    (requiring-resolve sym)
    (catch Exception _ nil)))

(defn- iaddon-protocol []
  (safe-resolve 'hive-mcp.addons.protocol/IAddon))

(defn- iaddon-initialize []
  (safe-resolve 'hive-mcp.addons.protocol/initialize!))

(defn- iaddon-instance? [result]
  (when-let [protocol (iaddon-protocol)]
    (satisfies? @protocol result)))

(defn- initialize-iaddon! [addon config]
  (when-let [initialize! (iaddon-initialize)]
    (initialize! addon config)))

(defn- addon-init-result [init addon-config]
  (let [result (init addon-config)]
    (if (iaddon-instance? result)
      {:addon/instance result
       :result (initialize-iaddon! result addon-config)}
      {:result result})))

(defn load-addon! [spec]
  (let [{addon-ns :ns addon-config :config :as normalized} (normalize-spec spec)
        base {:addon/ns addon-ns
              :addon/id (:id normalized)
              :classpath/aliases (:classpath/aliases normalized)}]
    (try
      (if-not addon-ns
        (merge base
               {:loaded? false
                :error :addon/invalid-spec
                :spec normalized})
        (do
          (require addon-ns)
          (if-let [init (init-var addon-ns)]
            (merge base
                   {:loaded? true}
                   (addon-init-result init addon-config))
            (merge base
                   {:loaded? false
                    :error :addon/no-init}))))
      (catch Throwable t
        (merge base
               {:loaded? false
                :error :addon/load-failed
                :message (.getMessage t)})))))

(defn load-addons! [specs]
  (mapv load-addon! (or specs [])))

(defn classpath-aliases [specs]
  (->> (or specs [])
       (mapcat (comp :classpath/aliases normalize-spec))
       distinct
       vec))