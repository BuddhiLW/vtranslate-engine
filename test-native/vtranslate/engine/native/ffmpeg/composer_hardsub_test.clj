(ns vtranslate.engine.native.ffmpeg.composer-hardsub-test
  "Native contract for composer.hardsub (make-composer + HardsubComposer) over the
   in-process JavaCV burner. Run under :ffmpeg (see tests-native.edn).

   No GPU and no system ffmpeg is used: every composing case pins the JavaCV
   backend, either explicitly or by pointing :auto at a binary that does not
   exist (the fallback calc.burn guarantees).

   Relational property over {:opts :cues :output?}:
   - wiring: a registered :burn-backend is honoured verbatim, an unregistered one
     is refused as :error/unknown-burn-backend, :auto without a capable binary
     falls back to :javacv;
   - composing: the result names the requested output (or the source's
     .subbed.mp4 sibling), no .part-* temp is left behind, and the picture
     changes on exactly the frames a cue covers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.composer.hardsub :as hardsub]
            [vtranslate.engine.native.ffmpeg.generators :as g]
            [vtranslate.engine.native.media-fixture :as fx]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.providers.burner-registry :as burners]
            [vtranslate.engine.providers.composer-registry :as reg]
            [vtranslate.engine.schema.native-media :as nm]
            [vtranslate.engine.schema.port-values :as pv]))

(def ^:private no-binary "/nonexistent/vt-native/ffmpeg")

(defn- strip [dir x]
  (if (string? x) (str/replace x dir "<tmp>") x))

(defn hardsub-observation
  "make-composer over `opts`, then (when wiring succeeded) compose `cues` onto a
   fresh fixture, to an explicit output when `output?`, else to the default
   sibling. Temp paths are rewritten to <tmp>."
  [{:keys [opts cues output?] :as in}]
  (fx/with-temp-dir [dir]
    (let [made (hardsub/make-composer {:composer-opts opts})
          make (if (:ok made)
                 {:ok {:backend (:backend (:ok made))}}
                 (select-keys made [:error :burn-backend]))]
      {:in  in
       :out (if-let [composer (:ok made)]
              (let [src    (fx/synth-source! dir)
                    track  (fx/rendered-track cues "en")
                    out    (if output? (str dir "/chosen.mp4") (str dir "/source.subbed.mp4"))
                    result (p.comp/compose composer src track
                                           (cond-> {} output? (assoc :output-uri out)))]
                {:make     make
                 :result   (update-in result [:ok :output-uri] #(strip dir %))
                 :partials (fx/partials dir)
                 :frames   (fx/frame-observations src out track)})
              {:make make})})))

(defn- expected-backend [opts]
  (let [b (:burn-backend opts)]
    (cond (or (nil? b) (= :auto b)) :javacv
          (some #{b} (burners/known)) b
          :else nil)))

(defn- holds? [{{:keys [opts cues output?] :as in} :in
                {:keys [make result partials frames] :as out} :out}]
  (and (m/validate nm/CueSpecs cues)
       (m/validate nm/HardsubObservation out)
       (if-let [want (expected-backend opts)]
         (and (= want (get-in make [:ok :backend]))
              (m/validate pv/ComposeResult result)
              (= (if output? "<tmp>/chosen.mp4" "<tmp>/source.subbed.mp4")
                 (get-in result [:ok :output-uri]))
              (= [] partials)
              (= (range 0 1000 fx/frame-ms) (map :t-ms frames))
              (every? #(= (:cue-active? %) (:changed? %)) frames))
         (and (= :error/unknown-burn-backend (:error make))
              (= (:burn-backend opts) (:burn-backend make))
              (not (contains? out :result))))
       (map? in)))

(def ^:private opts-gen
  "JavaCV-pinned wirings plus refused ones; never a GPU or system-ffmpeg backend."
  (gen/let [style   (gen/elements [{} {:font-size 18} {:font-size 30 :plate-opacity 0.0}])
            backend (gen/elements [:javacv :auto nil :no-such-backend])]
    (cond-> (assoc style :ffmpeg-bin no-binary)
      backend (assoc :burn-backend backend))))

(deftrifecta make-composer hardsub-observation
  {:golden-path "test/golden/native-composer-hardsub.edn"
   :cases {:explicit-javacv {:opts {:burn-backend :javacv :font-size 24}
                             :cues [{:start-ms 100 :end-ms 400 :lines ["hello"]}]
                             :output? true}
           :auto-fallback   {:opts {:ffmpeg-bin no-binary}
                             :cues [{:start-ms 0 :end-ms 200 :lines ["a"]}
                                    {:start-ms 600 :end-ms 1000 :lines ["b" "c"]}]
                             :output? false}
           :unknown-backend {:opts {:burn-backend :no-such-backend}
                             :cues [{:start-ms 0 :end-ms 100 :lines ["x"]}]
                             :output? true}}
   :gen (gen/hash-map :opts opts-gen :cues g/cues-gen :output? gen/boolean)
   :num-tests 12
   :pred holds?})

(deftest hard-is-registered-and-explicit-backends-resolve-without-probing
  (testing ":hard resolves through the composer registry"
    (is (= :javacv (:backend (:ok (reg/resolve-composer :hard {:composer-opts {:burn-backend :javacv}}))))))
  (testing "every registered backend key wires (no probe, no encode)"
    (doseq [b (burners/known)]
      (is (= b (:backend (:ok (hardsub/make-composer {:composer-opts {:burn-backend b}}))))
          (str b))))
  (testing "a missing source is a compose-failed Result and leaves no output or temp"
    (fx/with-temp-dir [dir]
      (let [c (:ok (hardsub/make-composer {:composer-opts {:burn-backend :javacv}}))
            r (p.comp/compose c (str dir "/missing.mp4")
                              (fx/rendered-track [{:start-ms 0 :end-ms 100 :lines ["x"]}] "en")
                              {:output-uri (str dir "/out.mp4")})]
        (is (m/validate pv/ComposeResult r))
        (is (= :error/compose-failed (:error r)))
        (is (= [] (fx/partials dir)))
        (is (not (.exists (java.io.File. (str dir "/out.mp4")))))))))
