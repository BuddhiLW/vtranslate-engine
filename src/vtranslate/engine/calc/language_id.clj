(ns vtranslate.engine.calc.language-id
  "Pure: name the language a piece of transcribed TEXT is written in, over the
   engine's closed source-language registry. Script decides the non-Latin
   languages; function-word evidence decides between the Latin ones. Quoted
   passages are set aside first, because speech quotes other languages.
   Returns a registry tag or nil — nil means the evidence does not decide, never
   a guess."
  (:require [clojure.string :as str]
            [vtranslate.engine.shared :as shared]))

(def function-words
  "Latin-script languages -> high-frequency function words. Only words that are
   rare in the other listed languages carry evidence."
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
          "bisa" "karena"}})

(def ^:private letter-hints
  "Characters that belong to exactly one of the Latin-script languages."
  {"de" #"[äöüß]" "pt" #"[ãõ]" "es" #"[ñ¿¡]" "fr" #"[èêëœùû]"
   "pl" #"[ąęłńśźż]" "tr" #"[ğış]"})

(def ^:private quoted-re
  #"\"[^\"]*\"|“[^”]*”|„[^“”]*[“”]|«[^»]*»|‘[^’]*’")

(defn strip-quotes
  "`text` with every quoted passage removed; `text` itself when nothing but the
   quotation would remain."
  [text]
  (let [t (str text)
        stripped (str/trim (str/replace t quoted-re " "))]
    (if (str/blank? stripped) t stripped)))

(defn- script-language
  "The registry language a non-Latin script alone identifies, or nil."
  [text]
  (cond
    (re-find #"[\p{IsHiragana}\p{IsKatakana}]" text) "ja"
    (re-find #"\p{IsHangul}" text)                    "ko"
    (re-find #"\p{IsHan}" text)                       "zh"
    (re-find #"[іїєґІЇЄҐ]" text)                        "uk"
    (re-find #"\p{IsCyrillic}" text)                  "ru"
    (re-find #"\p{IsDevanagari}" text)                "hi"
    (re-find #"\p{IsHebrew}" text)                    "he"
    (re-find #"[پچژگکی]" text)                        "fa"
    (re-find #"\p{IsArabic}" text)                    "ar"))

(defn scores
  "Evidence per Latin-script language for `text`: one point per function word of
   that language, plus one per word spelled with a letter only it uses.
   => {tag score} over the keys of `function-words`."
  [text]
  (let [tokens (re-seq #"[\p{L}']+" (str/lower-case (str text)))]
    (into {}
          (map (fn [[tag words]]
                 [tag (+ (count (filter words tokens))
                         (if-let [re (letter-hints tag)]
                           (count (filter #(re-find re %) tokens))
                           0))]))
          function-words)))

(defn identify
  "The source-registry tag `text` is written in, or nil when the evidence is
   absent or tied. Quoted passages are ignored while anything else remains."
  [text]
  (let [t (strip-quotes text)]
    (or (script-language t)
        (let [ranked (sort-by (comp - val) (scores t))
              [[best top] [_ runner-up]] ranked]
          (when (and best (pos? top) (> top runner-up)
                     (contains? shared/source-languages best))
            best)))))
