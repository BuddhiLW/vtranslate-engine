(ns vtranslate.engine.adapters.codec.registry-test
  "Format->codec registry — resolves built-ins, rejects unknown formats (the
   :error/unsupported-format railway), and extends purely via `register`."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.codec.registry :as reg]
            [vtranslate.engine.port.subtitle :as p.sub]))

(deftest resolves-built-in-formats
  (is (r/ok? (reg/codec-for reg/default-registry :format/srt)))
  (is (r/ok? (reg/codec-for reg/default-registry :format/vtt))))

(deftest rejects-unknown-format
  (is (r/err? (reg/codec-for reg/default-registry :format/ass))))

(deftest register-extends-purely
  (let [stub     (reify p.sub/ISubtitleRenderer (render-bytes [_ _] (r/ok "")))
        extended (reg/register reg/default-registry :format/ass stub)]
    (is (= stub (:ok (reg/codec-for extended :format/ass))) "new format resolves")
    (is (r/err? (reg/codec-for reg/default-registry :format/ass))
        "original registry is unchanged (immutability)")))
