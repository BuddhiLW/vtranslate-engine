(ns vtranslate.engine.adapters.transcriber.stub-test
  "The stub is the ITranscriber Liskov reference: it must pass the SAME
   check-transcriber the port contract runs, and it must make Ingress A runnable
   (always >=1 segment so the Transcript can complete). It must NOT be reachable
   by the router's fallback chain — only by explicit selection."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.contract.ports-contract :as ct]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.providers.transcriber-registry :as reg]
            [vtranslate.engine.providers.router :as router]
            [vtranslate.engine.adapters.transcriber.stub :as stub]))

(deftest satisfies-port-contract
  (testing "check-transcriber passes against the stub on both audio-source shapes"
    (ct/check-transcriber (stub/make-transcriber 1000) {:path "/tmp/x.wav" :duration-ms 3500} "en")
    (ct/check-transcriber (stub/make-transcriber 1000) "/no/such.wav" "pt")))  ; string path -> default dur

(deftest tiles-duration-non-empty
  (let [res (p.asr/transcribe (stub/make-transcriber 1000) {:path "/tmp/x.wav" :duration-ms 3500} "en" {})
        segs (:segments (:ok res))]
    (is (r/ok? res))
    (is (= 4 (count segs)) "3500ms / 1000ms window -> 4 spans (last clamped)")
    (is (= 3500 (:end-ms (last segs))) "final span clamps to duration")
    (is (= "[en segment 1]" (:text (first segs))) "deterministic language-keyed text")))

(deftest resolves-only-explicitly
  (testing ":stub resolves when asked"
    (is (r/ok? (reg/resolve-transcriber :stub {}))))
  (testing "the stub is NOT in the router fallback chain (no silent fake transcript)"
    (is (not (some #{:stub} router/transcriber-priority))
        "ASR must never silently fall back to the stub")
    (let [res (router/resolve-active-transcriber {:transcriber nil} {})]
      (is (some? res) "resolver never returns nil")
      (when (r/ok? res)
        (is (not (instance? vtranslate.engine.adapters.transcriber.stub.StubTranscriber (:ok res)))
            "fallback may find a real ASR provider, but never the stub")))))
