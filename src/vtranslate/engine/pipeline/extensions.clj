(ns vtranslate.engine.pipeline.extensions
  "OCP extension seam for the translation pipeline. Addons contribute middleware —
   fns (fn [resources ctx] => (r/ok ctx') | (r/err ...)) — at a named phase; the
   engine folds them over the pipeline context at that phase and never names what
   they do. With no addon loaded every phase resolves to no middleware, so the
   pipeline runs unchanged.")

(defmulti middleware
  "Registered middleware for `phase`, as an ordered seq of
   (fn [resources ctx] => Result<ctx>). Addons add one defmethod per phase they
   extend; the default is none."
  (fn [phase _resources] phase))

(defmethod middleware :default [_ _] [])
