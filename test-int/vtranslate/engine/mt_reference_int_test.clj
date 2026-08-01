(ns vtranslate.engine.mt-reference-int-test
  "Scores a REAL machine-translation provider against the corpus's human
   reference subtitles.

   The offline suites prove the localization pipeline with :translator :identity,
   which exercises plumbing but says nothing about translation. This drives the
   no-ASR (subtitle-in) ingress instead — corpus/sintel/subs/sintel.en.srt is
   parsed, translated by a live provider, and each rendered cue is scored against
   the corresponding cue of the human sintel.<lang>.srt. Using the parse ingress
   keeps ASR noise out of the measurement: cue segmentation is identical on both
   sides, so cues align 1:1.

   OPT-IN. Costs money and needs the network, so it is inert unless VT_MT_E2E is
   set. VT_MT_PROVIDER selects the provider (default venice; openrouter also
   registered). The key comes from the provider's own `pass` path or env var —
   see adapters.translator.llm/provider-defaults.

     VT_MT_E2E=1 clojure -M:test --config-file tests-int.edn"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.codec.srt :as srt]
            [vtranslate.engine.main :as main]
            [vtranslate.engine.port.subtitle :as p.sub])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

;; --- opt-in gate ------------------------------------------------------------

(def enabled?
  (boolean (some-> (System/getenv "VT_MT_E2E") str/trim not-empty)))

(def provider
  (keyword (or (some-> (System/getenv "VT_MT_PROVIDER") str/trim not-empty)
               "venice")))

(defn- env [k] (some-> (System/getenv k) str/trim not-empty))

(def provider-opts
  "Endpoint/model/key overrides for the live pass, so the scorecard can be
   produced against ANY OpenAI-compatible endpoint — a hosted provider or a
   local one (VT_MT_API_URL=http://localhost:11434/v1/chat/completions).
   VT_MT_SECRET_ENV names an env var holding the key and VT_MT_SECRET_PASS a
   passwordstore path; the adapter resolves either itself, so the key value
   never has to be handled here. A local endpoint that wants no key can point
   VT_MT_SECRET_ENV at any always-set variable, since the adapter only needs it
   non-blank. nil => the provider's own built-in defaults."
  (let [opts (cond-> {}
               (env "VT_MT_API_URL")     (assoc :api-url (env "VT_MT_API_URL"))
               (env "VT_MT_MODEL")       (assoc :model (env "VT_MT_MODEL"))
               (env "VT_MT_SECRET_ENV")  (assoc :secret-env (env "VT_MT_SECRET_ENV")
                                                :secret-pass nil)
               (env "VT_MT_SECRET_PASS") (assoc :secret-pass (env "VT_MT_SECRET_PASS")))]
    (not-empty opts)))

;; --- fixtures ---------------------------------------------------------------

(defn- sibling [relative]
  (.getCanonicalPath (io/file (System/getProperty "user.dir") ".." relative)))

(def corpus-dir
  (or (System/getenv "VTRANSLATE_CORPUS_DIR") (sibling "corpus")))

(defn- sintel-subs [lang]
  (str corpus-dir "/sintel/subs/sintel." lang ".srt"))

;; The MANIFEST records 26 cues for each of these; he (32) and en-us (110) use a
;; different granularity and cannot be aligned cue-for-cue, so they are excluded.
(def target-languages
  ["es" "pt" "de" "ru" "zh-hans" "ar"])

(def ship-ratio-floor
  "Least acceptable mean-per-cue chrF, in MULTIPLES of the target language's own
   chance level. Applies to every script: an absolute chrF floor is not
   comparable across writing systems, a ratio to chance is."
  2.2)

(def passthrough-margin
  "How far above the untranslated-source ratio a shippable translation must sit
   in languages that share the source's script."
  1.15)

;; --- chrF -------------------------------------------------------------------
;; Character n-gram F-score. Chosen over BLEU because it needs no tokenizer,
;; which is what makes one metric valid across es/pt/de, ru, zh-hans and ar
;; alike. Whitespace is stripped so cue line-wrapping cannot move the score.

(def ^:private chrf-max-n 6)
(def ^:private chrf-beta 2.0)

(defn- char-ngrams [n text]
  (let [squeezed (str/replace (str text) #"\s+" "")]
    (if (< (count squeezed) n)
      {}
      (frequencies (map #(apply str %) (partition n 1 squeezed))))))

(defn- overlap [a b]
  (reduce-kv (fn [total gram count-a]
               (+ total (min count-a (get b gram 0))))
             0
             a))

(defn chrf
  "chrF between a hypothesis and a reference string, in [0.0, 1.0].
   Precision and recall are averaged over character n-grams of order 1..6 and
   combined with beta=2 (recall-weighted, the chrF default)."
  [hypothesis reference]
  (let [scores (for [n (range 1 (inc chrf-max-n))
                     :let [h (char-ngrams n hypothesis)
                           r (char-ngrams n reference)
                           hits (overlap h r)
                           h-total (reduce + 0 (vals h))
                           r-total (reduce + 0 (vals r))]
                     :when (and (pos? h-total) (pos? r-total))]
                 [(/ (double hits) h-total) (/ (double hits) r-total)])
        n      (count scores)]
    (if (zero? n)
      0.0
      (let [precision (/ (reduce + (map first scores)) n)
            recall    (/ (reduce + (map second scores)) n)
            b2        (* chrf-beta chrf-beta)]
        (if (zero? (+ (* b2 precision) recall))
          0.0
          (/ (* (+ 1 b2) precision recall)
             (+ (* b2 precision) recall)))))))

;; --- corpus helpers ---------------------------------------------------------

(defn- cue-text [cue]
  (str/join " " (or (:lines cue) (:text cue))))

(defn- parse-reference
  "Cue texts of a reference .srt, in order, via the engine's own SRT codec."
  [path]
  (let [result (p.sub/parse (srt/make-codec) (slurp path) :format/srt)]
    (is (r/ok? result) (str "could not parse reference " path))
    (mapv cue-text (:cues (:ok result)))))

(defn- localize-subtitles
  "Run the no-ASR ingress over `source`. `translator-opts` overrides the provider
   endpoint (used by the stub-backed harness proof); omit it for the live
   provider. => cue texts."
  ([source target-language] (localize-subtitles source target-language nil))
  ([source target-language translator-opts]
   (main/register-adapters! {})
   (let [result (main/run
                 [(pr-str (cond-> {:job-id          (str "mt-ref-" target-language)
                                   :source          source
                                   :source-language "en"
                                   :target-language target-language
                                   :format          :format/srt
                                   :config          {:translator provider}}
                            translator-opts
                            (assoc-in [:config :translator-opts] translator-opts)))])]
     (is (r/ok? result)
         (str "localize to " target-language " failed: " (pr-str result)))
     (when (r/ok? result)
       (mapv cue-text (get-in result [:ok :subtitle-track :cues]))))))

(defn- mean [xs]
  (if (seq xs) (/ (reduce + xs) (count xs)) 0.0))

(defn chance-level
  "Mean chrF of each cue of `cues` against a DIFFERENT cue of the same sequence
   (rotated by one) — the score two arbitrary sentences of that language reach
   by sharing its alphabet, morphology and function words.
   => double in [0.0, 1.0]; 0.0 for fewer than two cues."
  [cues]
  (if (< (count cues) 2)
    0.0
    (mean (map chrf cues (concat (rest cues) [(first cues)])))))

(defn ratio-to-chance
  "`cues` scored against `reference`, in multiples of the reference language's
   chance level. => double; 0.0 when chance cannot be measured."
  [cues reference]
  (let [chance (chance-level reference)]
    (if (pos? chance) (/ (mean (map chrf cues reference)) chance) 0.0)))

(defn ship-floor
  "Least ratio-to-chance a shippable translation into `reference`'s language must
   reach, derived from the corpus: the global floor, raised where the untranslated
   `source` already scores well because it shares the target's script.
   => double."
  [source reference]
  (max ship-ratio-floor (* passthrough-margin (ratio-to-chance source reference))))

;; --- the metric's own sanity ------------------------------------------------
;; Runs offline, so a broken scorer is caught even when the paid suite is off.

(deftest chrf-behaves-like-a-similarity-score
  (testing "identical text scores 1.0, disjoint text scores 0.0"
    (is (= 1.0 (chrf "uma frase qualquer" "uma frase qualquer")))
    (is (zero? (chrf "aaaa" "bbbb"))))
  (testing "whitespace and line wrapping do not move the score"
    (is (= 1.0 (chrf "uma frase\nqualquer" "uma frase qualquer"))))
  (testing "a near translation beats an unrelated one"
    (is (> (chrf "Esta lâmina tem um passado sombrio."
                 "Esta lâmina tem um passado escuro.")
           (chrf "O gato subiu no telhado."
                 "Esta lâmina tem um passado escuro."))))
  (testing "it works without a tokenizer, on CJK and RTL too"
    (is (= 1.0 (chrf "这把剑有黑暗的过去" "这把剑有黑暗的过去")))
    (is (> (chrf "هذا النصل له ماض مظلم" "هذا النصل له ماضٍ مظلم") 0.5))))

(deftest chance-level-is-script-dependent
  ;; Runs offline against the corpus's own human references, so the unit the
  ;; ship floor is expressed in is verified without calling a provider.
  (let [chance (into {} (for [lang (conj target-languages "es-419")]
                          [lang (chance-level (parse-reference (sintel-subs lang)))]))]
    (testing "two arbitrary sentences of one language already share n-grams"
      (is (every? pos? (vals chance))))
    (testing "the same chrF number means different things in different scripts"
      (is (< (chance "zh-hans") (* 0.5 (chance "es")))
          (str "Han chance " (chance "zh-hans") " should be far below Latin "
               (chance "es") " — this is why one absolute floor cannot serve both"))
      (is (< (chance "ar") (chance "ru"))))
    (let [english (parse-reference (sintel-subs "en"))]
      (testing "a second INDEPENDENT human reference clears the floor"
        ;; es vs es-419 are two human translations of the same film: the best a
        ;; provider could plausibly score, and the sanity check on the floor.
        (let [es    (parse-reference (sintel-subs "es"))
              es419 (parse-reference (sintel-subs "es-419"))]
          (is (> (ratio-to-chance es es419) (ship-floor english es419))
              (str "human-vs-human Spanish scores only "
                   (ratio-to-chance es es419) "x chance"))))
      (testing "the untranslated source never clears the floor it derives"
        (doseq [lang target-languages]
          (let [reference (parse-reference (sintel-subs lang))]
            (is (< (ratio-to-chance english reference)
                   (ship-floor english reference))
                (str lang ": the floor cannot discriminate a passthrough")))))
      (testing "a shared script raises the floor above the global minimum"
        (is (> (ship-floor english (parse-reference (sintel-subs "es")))
               ship-ratio-floor)
            "Latin-script targets must clear more than the global floor")
        (is (= ship-ratio-floor
               (ship-floor english (parse-reference (sintel-subs "zh-hans"))))
            "a target sharing no script with the source keeps the global floor")))))

;; --- harness proof, no credentials ------------------------------------------
;; The scorecard below is only trustworthy if the chain that produces it —
;; subtitle-in ingress, provider routing, batching, cue alignment, scoring — is
;; itself correct. A stub OpenAI-compatible endpoint that "translates" by handing
;; back the human reference lets that chain be verified end to end offline: a
;; perfect translator must score ~1.0, well above the untranslated baseline.

(defn- reply-json [^HttpExchange exchange body]
  (let [payload (.getBytes (json/generate-string body) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange 200 (alength payload))
    (with-open [out (.getResponseBody exchange)]
      (.write out payload))))

(defn- oracle-server
  "An OpenAI-compatible endpoint that answers each batch with the next `n`
   entries of `answers` — i.e. a translator that is right by construction.
   => {:url ... :stop (fn [])}."
  [answers]
  (let [remaining (atom (vec answers))
        http      (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     http "/"
     (reify HttpHandler
       (handle [_ exchange]
         (let [request (json/parse-string (slurp (.getRequestBody exchange)) true)
               ;; the user message ends with the JSON array of texts to translate,
               ;; optionally preceded by context blocks
               content (->> (:messages request)
                            (filter #(= "user" (:role %)))
                            first
                            :content)
               texts   (json/parse-string
                        (second (re-find #"(?s)(\[.*\])\s*\z" content)))
               batch   (vec (take (count texts) @remaining))]
           (swap! remaining #(vec (drop (count texts) %)))
           (reply-json exchange
                       {:choices [{:message {:content (json/generate-string batch)}}]})))))
    (.setExecutor http nil)
    (.start http)
    {:url  (str "http://127.0.0.1:" (.getPort (.getAddress http))
                "/v1/chat/completions")
     :stop #(.stop http 0)}))

(deftest ^:mt scoring-harness-separates-a-perfect-translation-from-an-untranslated-one
  (let [source     (sintel-subs "en")
        english    (parse-reference source)
        reference  (parse-reference (sintel-subs "pt"))
        {:keys [url stop]} (oracle-server reference)]
    (try
      (let [translated (localize-subtitles source "pt"
                                           {:api-url    url
                                            ;; any always-set env var: the adapter
                                            ;; only needs a non-blank key to call out
                                            :secret-env "PATH"
                                            :secret-pass nil})
            scored     (chrf (str/join " " translated) (str/join " " reference))
            baseline   (chrf (str/join " " english) (str/join " " reference))]
        (testing "the ingress preserves cue alignment through a real HTTP provider"
          (is (= (count english) (count translated)))
          (is (= (count reference) (count translated))))
        (testing "a perfect translator scores near 1.0, far above the baseline"
          (is (> scored 0.95) (str "oracle scored only " scored))
          (is (> scored baseline)))
        (testing "and the untranslated baseline is genuinely below the floor
                  the live proof asserts, so the floor can discriminate"
          (is (< baseline 0.95))))
      (finally (stop)))))

;; --- the paid proof ---------------------------------------------------------

(deftest ^:mt real-provider-translations-track-the-reference-subtitles
  (if-not enabled?
    (do (println "[mt-reference] skipped — set VT_MT_E2E=1 to score a live provider")
        (is (not enabled?) "opt-in: no live provider was called"))
    (let [source    (sintel-subs "en")
          english   (parse-reference source)
          scorecard
          (into {}
                (for [lang target-languages]
                  (let [reference   (parse-reference (sintel-subs lang))
                        translated  (localize-subtitles source lang provider-opts)]
                    (testing (str lang ": the pipeline preserves cue alignment")
                      (is (= (count english) (count translated))
                          "one translated cue per source cue")
                      (is (= (count reference) (count translated))
                          "the reference uses the same cue granularity"))
                    [lang {:translated translated
                           :reference  reference
                           :per-cue    (mapv chrf translated reference)
                           :chance     (chance-level reference)
                           :corpus     (chrf (str/join " " translated)
                                             (str/join " " reference))
                           :baseline   (chrf (str/join " " english)
                                             (str/join " " reference))}])))]

      ;; Printed so a run is auditable — the floors above were set from these.
      (println (format "[mt-reference] provider=%s endpoint=%s model=%s"
                       (name provider)
                       (or (:api-url provider-opts) "<default>")
                       (or (:model provider-opts) "<default>")))
      (doseq [[lang {:keys [corpus baseline per-cue chance]}] (sort-by key scorecard)]
        (println (format (str "[mt-reference] %-8s chrF=%.3f  per-cue=%.3f  "
                              "chance=%.3f  x-chance=%.2f  untranslated-baseline=%.3f")
                         lang corpus (mean per-cue) chance
                         (if (pos? chance) (/ (mean per-cue) chance) 0.0)
                         baseline)))

      (doseq [[lang {:keys [corpus baseline per-cue translated reference]}] scorecard]
        (testing (str lang " is a real translation, not a passthrough")
          ;; The load-bearing assertion: the output must be closer to the human
          ;; reference than the untranslated English is. It needs no magic
          ;; constant, holds across providers, models and prompt changes, and is
          ;; the ONE check that applies to every script.
          (is (> corpus baseline)
              (str lang ": translation (" corpus ") no closer to the reference "
                   "than the untranslated source (" baseline ")"))
          (is (not= translated (take (count translated) english))
              (str lang ": output is identical to the English input"))
          (is (every? seq translated) (str lang ": some cue came back empty"))
          (is (= (count reference) (count per-cue))))

        (testing (str lang " is good enough to be worth shipping")
          ;; Floor, not a target: catches a provider that has degraded into
          ;; garbage without failing on stylistic drift. The unit is the
          ;; language's own chance level, so one rule serves every script.
          (let [chance (:chance (scorecard lang))
                ratio  (if (pos? chance) (/ (mean per-cue) chance) 0.0)
                floor  (ship-floor english reference)]
            (is (pos? chance)
                (str lang ": chance level could not be measured from the reference"))
            (is (> ratio floor)
                (str lang ": mean per-cue chrF " (mean per-cue) " is only " ratio
                     "x the " chance " chance level for this language, below the "
                     floor "x floor"))))))))
