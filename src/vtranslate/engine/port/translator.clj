(ns vtranslate.engine.port.translator
  "Port (ISP barrier) for machine translation. An adapter (M3) implements
   ITranslator; the engine depends only on this protocol (DIP).")

(defprotocol ITranslator
  "Batch machine translation. Order- and count-preserving: the result vector
   aligns 1:1 with the input `texts`."
  (translate-batch [this texts source-language target-language opts]
    "=> (r/ok [translated-string ...])  (same length + order as `texts`)
        | (r/err :error/translation-failed {:segment-id s :reason s})."))
