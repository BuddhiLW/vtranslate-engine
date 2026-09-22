(ns vtranslate.engine.calc.burn
  "Pure choice of the burn-in backend from :composer-opts. The adapter passes
   in what it observed (whether an ffmpeg binary answered) AND which backend
   keys are actually registered; this decides.

   The registered set is a PARAMETER and never a literal here. calc must not
   require vtranslate.engine.providers.*: the registry is L3 and this is L1,
   so a closed set copied into calc would mean a backend the registry HAS
   registered is unknown to the code that chooses one. The composer adapter
   passes (burner-registry/known) in at the boundary.")

(def auto-backends
  "The two backends :auto may pick between. Both are always registered on
   their own classpath, and neither is a scheduled resource.

   :auto never picks a hardware backend. A GPU is a scheduled, shared
   resource (the ASR holds one), so a deployment claims it by naming
   :ffmpeg-nvenc or :ffmpeg-vaapi beside the device request, not by a worker
   discovering one."
  #{:ffmpeg-cli :javacv})

(def default-binary "ffmpeg")

(defn binary
  "The ffmpeg executable to run: :ffmpeg-bin from the opts, else the bare
   name resolved from PATH."
  ^String [opts]
  (let [b (some-> (:ffmpeg-bin opts) str .trim)]
    (if (or (nil? b) (.isEmpty b)) default-binary b)))

(defn requested
  "The backend `opts` ask for, checked against `known` (the registered
   backend keys, as a set or any seqable of them).

   Absent, nil or :auto reads as :auto. Anything else must be a REGISTERED
   key, and an explicit key that is not throws: a deployment that named a
   backend and got libx264 instead has no way to tell, and that silence is
   the bug this signature exists to remove. It fails at wiring, before a
   single job is accepted, not per burn."
  [opts known]
  (let [k (:burn-backend opts)
        registered (set known)]
    (cond
      (or (nil? k) (= :auto k)) :auto
      (contains? registered k)  k
      :else
      (throw (ex-info (str "unknown burn backend " (pr-str k)
                           "; registered: " (pr-str (vec (sort-by str registered))))
                      {:error :error/unknown-burn-backend
                       :burn-backend k
                       :known (vec (sort-by str registered))})))))

(defn choose
  "The backend to wire. `known` is the registered key set; `available?` is
   whether the ffmpeg binary answered, and it only matters under :auto.

   An explicit backend is honoured whatever the binary said, so a deployment
   that named one fails loud at the burn rather than silently taking the slow
   path. No per-backend branch lives here: a key the registry knows is a key
   this returns."
  [opts known available?]
  (let [k (requested opts known)]
    (if (= :auto k)
      (if available? :ffmpeg-cli :javacv)
      k)))
