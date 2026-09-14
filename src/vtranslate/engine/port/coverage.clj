(ns vtranslate.engine.port.coverage
  "Port: what a segmenter's spans are ALLOWED to leave out.

   A voice-activity segmenter is a filter with no error channel — audio it
   rejects never reaches ASR, the job still completes, and a missed passage of
   dialogue is indistinguishable from a passage with no dialogue in it. The
   policy behind this port is the place that decision is made explicit, so it
   can be chosen per deployment instead of being an accident of the VAD's
   training set.

   Who may add the next member is the whole reason this is a protocol and not a
   `case`: grid-fill and pass-through are the two built in here, and a
   deployment with an energy detector, a music classifier or a coverage ratio of
   its own registers a third from outside this repo (OCP)."
  (:require [hive-dsl.result :as r]))

(defprotocol ICoveragePolicy
  "Decide the final span set from what a segmenter returned."
  (cover [this spans duration-ms]
    "`spans` are the inner segmenter's ordered spans, `duration-ms` the probed
     media length (0 when unknown — a policy MUST then pass spans through, since
     it cannot know what is uncovered).
     => (r/ok [{:start-ms n :end-ms n} ...]) | (r/err :error/segmentation-failed ...)."))

(defn covered
  "The ok-spans of a `cover` call, or the err Result unchanged — sugar for the
   decorator, which has nothing to add to a policy failure."
  [policy spans duration-ms]
  (r/let-ok [out (cover policy spans duration-ms)]
    (r/ok out)))
