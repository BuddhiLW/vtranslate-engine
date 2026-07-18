(ns vtranslate.engine.schema.typed
  "Typed Clojure type syntax (as DATA) derived from the engine value-object
   schemas via hive-spi.schema.typed. One source (the malli schema) drives the
   runtime validator, the m/=> contract, AND the static type — they cannot drift.

   NON-RUNTIME: loaded under :schemas / :test / :typed."
  (:require [hive-spi.schema.typed :as t]
            [vtranslate.engine.schema :as s]))

(def value-object-types
  "{value-object-key -> Typed Clojure validator-type (as data)}."
  {:language       (t/schema->type s/Language)
   :timecode       (t/schema->type s/Timecode)
   :time-range     (t/schema->type s/TimeRange)
   :confidence     (t/schema->type s/Confidence)
   :probe-info     (t/schema->type s/ProbeInfo)
   :segment        (t/schema->type s/Segment)
   :span           (t/schema->type s/Span)
   :window         (t/schema->type s/Window)
   :cue-data       (t/schema->type s/CueData)
   :cue            (t/schema->type s/Cue)
   :routing-config (t/schema->type s/RoutingConfig)})

(def defalias-forms
  "`(t/defalias Name Type)` forms — one per value object, ready to splice into a
   typed.clj.checker namespace."
  (mapv (fn [[k _]]
          (t/defalias-form (symbol (name k)) (get s/schemas (keyword "vtranslate.schema" (name k)))))
        value-object-types))
