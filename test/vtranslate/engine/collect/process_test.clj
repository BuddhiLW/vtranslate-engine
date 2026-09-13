(ns vtranslate.engine.collect.process-test
  "The real process runner against a shell script: exit codes, which stream
   each call keeps, and how a missing executable is reported. Needs /bin/sh."
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.collect.process :as sut])
  (:import [java.io File IOException]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- script! ^File [body]
  (let [dir (.toFile (Files/createTempDirectory "vt-process" (into-array FileAttribute [])))
        f   (File. dir "tool")]
    (spit f (str "#!/bin/sh\n" body))
    (.setExecutable f true)
    f))

(deftest exec-keeps-stderr-and-the-exit-code
  (let [tool (script! "echo out; echo err >&2; exit 3")]
    (is (= {:exit 3 :stderr "err\n"} (sut/exec! sut/system-runner [(.getPath tool)])))))

(deftest capture-keeps-stdout-and-the-exit-code
  (let [tool (script! "echo \"$1\"; echo err >&2; exit 0")]
    (is (= {:exit 0 :out "hello\n"} (sut/capture! sut/system-runner [(.getPath tool) "hello"])))))

(deftest a-missing-executable-throws-from-the-runner-and-is-false-or-nil-from-the-probes
  (is (thrown? IOException (sut/exec! sut/system-runner ["/nonexistent/tool"])))
  (is (false? (sut/starts? sut/system-runner ["/nonexistent/tool"])))
  (is (nil? (sut/output-of sut/system-runner ["/nonexistent/tool"]))))

(deftest starts-and-output-of-read-a-zero-exit-only
  (let [ok   (script! "echo listing; exit 0")
        fail (script! "echo partial; exit 1")]
    (is (true? (sut/starts? sut/system-runner [(.getPath ok)])))
    (is (false? (sut/starts? sut/system-runner [(.getPath fail)])))
    (is (= "listing\n" (sut/output-of sut/system-runner [(.getPath ok)])))
    (is (nil? (sut/output-of sut/system-runner [(.getPath fail)]))
        "a non-zero exit is no answer, not a partial one")))
