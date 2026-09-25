(ns vtranslate.engine.shared
  "Shared kernel — value objects reused by every bounded context: Language
   (BCP-47), Timecode, TimeRange. Pure data + smart constructors; no effects,
   no engine deps. Bottom of the stratified stack: everything may depend here,
   it depends on nothing above."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]))

;; --- SourceRef (locator value object) --------------------------------------

(defrecord SourceRef [uri])         ; uri : non-blank locator (fs path or URI)

(defn make-source-ref
  "Validate a source locator (a non-blank string — fs path or URI). The single
   value object both MediaAsset and the job-orchestration layer use to name the
   artifact, instead of passing a raw string around.
   => (r/ok SourceRef) | (r/err :error/invalid-source {:source uri})."
  [uri]
  (if (and (string? uri) (not (str/blank? uri)))
    (r/ok (->SourceRef uri))
    (r/err :error/invalid-source {:source uri})))

;; --- Language (BCP-47 tag, closed registry) --------------------------------

(def source-languages
  "Tags a TRANSCRIBER accepts as the spoken language. Narrow: gated by ASR
   capability, and Whisper rejects a regional variant outright rather than
   falling back to its base. Keep in sync with the ASR adapter capability table.
   \"und\" is the undetermined-source sentinel."
  #{"und" "en" "pt" "es" "fr" "de" "ru" "zh" "ja" "ar" "he" "fa"
    "uk" "it" "ko" "tr" "pl" "nl" "hi" "id"})

(def target-languages
  "Tags a TRANSLATOR will render into. Wider than the source set and gated by a
   different thing: an LLM translates into far more languages than an ASR model
   transcribes from, and a regional variant is meaningful here because it picks
   a dialect. Keep in sync with the translator capability table AND with the
   worker image's font coverage, since a burned subtitle in a script no
   installed face covers renders as tofu.

   \"und\" is in BOTH sets: it is the undetermined sentinel rather than a
   language, and a transcription-only job records it as its target."
  #{"und" "en" "en-us" "pt" "pt-BR" "es" "es-419" "fr" "de" "ru"
    "zh" "zh-hans" "zh-cn" "zh-tw" "ja" "ar" "he" "fa"
    "uk" "it" "ko" "tr" "pl" "nl" "hi" "id"
    "ca" "cs" "da" "el" "fi" "hu" "ms" "nb" "ro" "sv" "vi" "bg" "sk" "hr"
    "bn" "ur"})

(def function-words
  "Latin-script registry tags -> high-frequency function words of that language.
   Only words that are rare in the OTHER listed languages carry evidence, so a
   token in NONE of these sets is a content word rather than a language marker.

   A TARGET-ONLY language's set shares no word with a source language's set
   unless English lists that word too, so it can never outvote a source
   language on that language's own words. The English words it does share are
   ordinary words of that language (Swedish \"i\", Czech \"to\", Finnish \"on\"),
   so a translation into it that keeps them has not leaked English."
  {"en" #{"the" "and" "is" "are" "was" "were" "of" "to" "in" "that" "it" "with"
          "for" "this" "they" "we" "you" "i" "my" "our" "not" "be" "have" "has"
          "what" "which" "who" "will" "would" "there" "their" "may" "can" "as" "an"
          "at" "on" "by" "from" "all" "but" "or" "if" "his" "her" "he" "she" "him"
          "them" "when" "where" "wherever" "because" "these" "those"}
   "de" #{"der" "die" "das" "und" "ist" "sind" "war" "nicht" "ich" "ein" "eine"
          "einer" "mit" "für" "auf" "dem" "den" "des" "sich" "auch" "es" "wir"
          "sie" "hat" "haben" "wie" "aber" "nur" "noch" "zu" "von" "bin" "denn"}
   "pt" #{"o" "os" "as" "um" "uma" "não" "é" "são" "foi" "com" "para" "que"
          "do" "da" "dos" "das" "em" "no" "na" "nos" "eu" "ele" "ela" "isso"
          "mas" "muito" "também" "você" "seu" "sua"}
   "es" #{"el" "los" "las" "un" "una" "y" "es" "son" "fue" "con" "para" "que"
          "del" "al" "en" "por" "no" "yo" "él" "ella" "pero" "muy" "también"
          "usted" "su" "lo" "está"}
   "fr" #{"le" "la" "les" "un" "une" "et" "est" "sont" "était" "avec" "pour"
          "que" "du" "des" "au" "aux" "dans" "par" "ne" "pas" "je" "il" "elle"
          "nous" "vous" "mais" "très" "aussi" "ce" "cette" "qui"}
   "it" #{"il" "lo" "gli" "della" "delle" "degli" "nel" "nella" "sono" "non"
          "che" "per" "con" "una" "questo" "questa" "anche" "molto" "io" "noi"
          "voi" "ma" "perché" "è" "essere" "stato"}
   "nl" #{"de" "het" "een" "en" "van" "is" "niet" "ik" "wij" "zijn" "was" "met"
          "voor" "op" "dat" "die" "maar" "ook" "naar" "bij" "hij" "zij" "u" "je"
          "wat" "er" "nog" "om"}
   "pl" #{"i" "w" "na" "nie" "jest" "się" "że" "to" "z" "do" "jak" "ale" "tak"
          "ja" "my" "oni" "co" "czy" "są" "był" "było" "przez" "dla" "od"}
   "tr" #{"ve" "bir" "bu" "da" "de" "için" "ile" "ben" "biz" "siz" "o" "ne"
          "değil" "var" "yok" "gibi" "çok" "daha" "ama" "mi" "mı"}
   "id" #{"dan" "yang" "di" "ke" "dari" "ini" "itu" "tidak" "adalah" "saya"
          "kami" "kita" "mereka" "dengan" "untuk" "akan" "sudah" "juga" "ada"
          "bisa" "karena"}
   ;; Target-only languages from here on.
   "ca" #{"i" "on" "amb" "però" "molt" "aquest" "aquesta" "això" "també" "perquè"
          "doncs" "els" "dels" "pels" "jo" "nosaltres" "vosaltres" "ells" "elles"
          "és" "són" "era" "hi" "ho" "seva" "quan" "ara" "aquí"}
   "cs" #{"a" "to" "on" "se" "v" "s" "ze" "jsem" "jsou" "jsme" "byl" "bylo"
          "když" "nebo" "také" "už" "není" "ten" "ta" "pro" "jako" "však" "jen"
          "být"}
   "sk" #{"a" "to" "on" "sa" "v" "vo" "s" "so" "zo" "som" "sú" "sme" "bol" "bolo"
          "keď" "alebo" "tiež" "už" "ten" "pre" "ako" "len" "však" "byť"}
   "hr" #{"i" "to" "a" "se" "sam" "smo" "ali" "kao" "što" "ili" "za" "iz"
          "bio" "bila" "biti" "samo" "već" "kad" "jer" "ovo" "taj" "ovaj"}
   "da" #{"i" "at" "for" "her" "og" "det" "til" "på" "som" "med" "har" "ikke"
          "jeg" "vi" "han" "hun" "af" "men" "kan" "også" "skal" "meget" "når"
          "hvad" "jo"}
   "nb" #{"i" "at" "for" "her" "og" "det" "til" "på" "som" "med" "har" "ikke"
          "jeg" "vi" "han" "hun" "av" "men" "kan" "også" "skal" "veldig" "når"
          "hva" "meg" "seg"}
   "sv" #{"i" "och" "att" "det" "är" "som" "på" "för" "med" "har" "inte" "jag"
          "vi" "han" "hon" "av" "men" "kan" "också" "ska" "mycket" "när" "vad"
          "till" "så" "här"}
   "fi" #{"on" "he" "ei" "se" "että" "oli" "olen" "ovat" "ole" "mutta" "kun"
          "niin" "myös" "minä" "sinä" "hän" "me" "te" "tämä" "joka" "kuin" "jos"
          "vain" "nyt" "sitten" "mitä"}
   "hu" #{"is" "a" "az" "és" "hogy" "nem" "egy" "meg" "csak" "már" "ez" "azt"
          "mint" "vagy" "még" "volt" "kell" "én" "ő" "mert" "akkor" "itt"
          "nagyon" "lesz" "sem"}
   "ro" #{"și" "şi" "în" "nu" "este" "sunt" "cu" "pe" "din" "care" "mai" "dar"
          "sau" "ca" "ea" "acest" "această" "pentru" "fost" "foarte" "asta"
          "doar" "aici" "când" "unde" "fi"}
   "ms" #{"boleh" "kerana" "mahu" "sahaja" "bahawa" "awak" "nak" "betul"
          "hendak" "ialah" "iaitu"}
   "vi" #{"và" "là" "của" "có" "không" "tôi" "này" "được" "cho" "với" "những"
          "các" "một" "người" "đã" "sẽ" "đó" "thì" "mà" "cũng" "như" "bạn"
          "chúng" "khi" "ở" "vì" "nhưng" "rất"}})

(def supported-languages
  "Every tag the engine accepts anywhere. The union, kept so a caller that does
   not care which side it is on still has one set to ask."
  (into source-languages target-languages))

(defn canonical-tag
  "The member of `registry` equal to `tag` ignoring case (BCP-47 tags are
   case-insensitive), or nil. \"zh-Hans\" -> \"zh-hans\", \"PT-br\" -> \"pt-BR\"."
  [registry tag]
  (when (string? tag)
    (if (contains? registry tag)
      tag
      (let [lower (str/lower-case tag)]
        (some #(when (= lower (str/lower-case %)) %) registry)))))

(defn make-language
  "Validate a BCP-47 tag against the union registry. Tags compare
   case-insensitively (BCP-47); the registry's own spelling is returned.
   => (r/ok tag) | (r/err :error/unsupported-language {:language tag})."
  [tag]
  (if-let [canonical (canonical-tag supported-languages tag)]
    (r/ok canonical)
    (r/err :error/unsupported-language {:language tag})))

(defn make-source-language
  "Validate a tag a transcriber will be asked to hear, case-insensitively; the
   registry's own spelling is returned.
   => (r/ok tag) | (r/err :error/unsupported-language {:language tag :side :source})."
  [tag]
  (if-let [canonical (canonical-tag source-languages tag)]
    (r/ok canonical)
    (r/err :error/unsupported-language {:language tag :side :source})))

(defn make-target-language
  "Validate a tag a translator will be asked to render into, case-insensitively;
   the registry's own spelling is returned.
   => (r/ok tag) | (r/err :error/unsupported-language {:language tag :side :target})."
  [tag]
  (if-let [canonical (canonical-tag target-languages tag)]
    (r/ok canonical)
    (r/err :error/unsupported-language {:language tag :side :target})))

;; --- Time (value objects) --------------------------------------------------

(defrecord Timecode [ms])           ; ms : non-negative integer milliseconds
(defrecord TimeRange [start end])   ; start/end : Timecode, invariant start <= end

(defn make-timecode
  "=> (r/ok Timecode) for a non-negative integer ms, else (r/err ...)."
  [ms]
  (if (nat-int? ms)
    (r/ok (->Timecode ms))
    (r/err :error/invalid-timecode {:ms ms})))

(defn make-time-range
  "=> (r/ok TimeRange) when 0 <= start <= end, else (r/err ...)."
  [start-ms end-ms]
  (r/let-ok [start (make-timecode start-ms)
             end   (make-timecode end-ms)]
            (if (<= (:ms start) (:ms end))
              (r/ok (->TimeRange start end))
              (r/err :error/inverted-time-range {:start start-ms :end end-ms}))))

(defn duration-ms
  "Length of a TimeRange in milliseconds (a pure calculation)."
  [{:keys [start end]}]
  (- (:ms end) (:ms start)))

(defn range-ms
  "TimeRange -> [start-ms end-ms]."
  [{:keys [start end]}]
  [(:ms start) (:ms end)])

(defn guard-transition
  "Shared aggregate-lifecycle guard: (r/ok agg) when (:adt/variant (status-of agg)) is in `allowed`, else (r/err :error/illegal-transition {:from variant})."
  [agg status-of allowed]
  (let [from (:adt/variant (status-of agg))]
    (if (contains? allowed from)
      (r/ok agg)
      (r/err :error/illegal-transition {:from from}))))

(defn annotations
  "The namespaced keys of `data`: annotations an adapter or addon attached (e.g.
   :segment/verbatim?, :segment/omit?). They ride along unchanged, on a Segment
   and on the TranslationUnit made from it."
  [data]
  (into {} (filter (comp qualified-keyword? key)) data))
