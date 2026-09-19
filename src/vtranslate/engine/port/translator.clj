(ns vtranslate.engine.port.translator
  "Port (ISP barrier) for machine translation. An adapter (M3) implements
   ITranslator; the engine depends only on this protocol (DIP).")

(defprotocol ITranslator
  "Batch machine translation. Order- and count-preserving: the result vector
   aligns 1:1 with the input `texts`."
  (translate-batch [this texts source-language target-language opts]
    "=> (r/ok [translated-string ...])  (same length + order as `texts`)
        | (r/err :error/translation-failed {:segment-id s :reason s})."))

(defn translate
  "Call `translator`'s translate-batch through a var. An AOT-compiled addon calls
   this rather than the protocol fn: the engine ships as source, so a direct
   protocol call compiled into an addon jar names this protocol's interface and
   throws NoClassDefFoundError in the worker."
  [translator texts source-language target-language opts]
  (translate-batch translator texts source-language target-language opts))

(defn translator
  "An ITranslator whose translate-batch is `f`
   ([texts source-language target-language opts] => Result). Built in engine
   source, so an AOT-compiled addon can make a translator without naming this
   protocol's interface."
  [f]
  (reify ITranslator
    (translate-batch [_ texts source-language target-language opts]
      (f texts source-language target-language opts))))
