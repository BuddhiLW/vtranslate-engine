;; LSP contract + native integration for CollectMediaPort (closes task ...61b30242).
;; Proves the JavaCV Collect adapter is substitutable BEHIND the canonical
;; port.media (Liskov): the same checks ports_contract runs on a mock are run
;; here against the real bridge. Standalone so kaocha stays native-free; run:
;;   clojure -M:ffmpeg -i dev/contract_collect_port.clj
;; Assertions mirror vtranslate.engine.contract.ports-contract/check-media-probe
;; + check-audio-extractor (inlined to avoid pulling the :test classpath).
(require '[vtranslate.engine.collect.port :as cport]
         '[vtranslate.engine.collect.protocols :as cp]
         '[vtranslate.engine.port.media :as port]
         '[hive-dsl.result :as r])

(def failed (atom false))
(defn check [label pred]
  (println (format "  %-42s %s" label (if pred "PASS" "FAIL")))
  (when-not pred (reset! failed true)))

;; Inner backend (collect.protocols) stub — lets us exercise the BRIDGE +
;; port.media contract without a real decode. CollectMediaPort accepts it via DI.
(def stub
  (reify
    cp/IMediaProbe
    (probe [_ _uri]
      {:container "mp4" :duration-ms 2000 :has-audio? true
       :audio-codec "aac" :sample-rate 48000 :channels 2})
    cp/IAudioExtractor
    (extract-audio [_ _uri out-path _opts] out-path)))

(defn check-media-probe [impl uri]
  (let [res (port/probe impl uri)]
    (check "probe returns a hive-dsl Result" (or (r/ok? res) (r/err? res)))
    (when (r/ok? res)
      (let [m (:ok res)]
        (check "ok probe carries all ProbeInfo keys"
               (every? #(contains? m %) [:container :duration-ms :has-audio? :audio-codec]))
        (check "ok probe :has-audio? is boolean" (boolean? (:has-audio? m)))))))

(defn check-audio-extractor [impl uri]
  (let [res (port/extract-audio impl uri {})]
    (check "extract-audio returns a hive-dsl Result" (or (r/ok? res) (r/err? res)))))

(println "=== CollectMediaPort over a STUB inner backend (bridge contract) ===")
(let [p (cport/collect-media-port stub)]
  (check "CollectMediaPort satisfies port.media/IMediaProbe"   (satisfies? port/IMediaProbe p))
  (check "CollectMediaPort satisfies port.media/IAudioExtractor" (satisfies? port/IAudioExtractor p))
  (check-media-probe p "deps.edn")            ;; existing source -> ok
  (check-audio-extractor p "deps.edn")
  (check "probe of a MISSING source is an err Result"
         (r/err? (port/probe p "/vtranslate/__nope__.mp4"))))

(println "=== CollectMediaPort over the REAL JavaCvMedia (native integration) ===")
(let [p   (cport/collect-media-port)          ;; default backend = JavaCvMedia
      res (port/probe p "/home/leibniz/whatsapp_video.mp4")]
  (check "real native probe is ok"      (r/ok? res))
  (check "real probe duration-ms > 0"   (pos? (:duration-ms (:ok res))))
  (check "real probe detects audio"     (true? (:has-audio? (:ok res)))))

(println (if @failed "\n=== CONTRACT: FAIL ===" "\n=== CONTRACT: PASS ==="))
(shutdown-agents)
(System/exit (if @failed 1 0))
