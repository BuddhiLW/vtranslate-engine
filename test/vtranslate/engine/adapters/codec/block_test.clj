(ns vtranslate.engine.adapters.codec.block-test
  "Shared SRT/WebVTT block skeleton — golden wire shape + a mutation battery over
   cue->block (sep-parameterized) and split-blocks (normalize + split). This is
   the rendering logic both codecs delegate to, so it carries the mutation proof."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [vtranslate.engine.adapters.codec.timecode :as tc]
            [vtranslate.engine.adapters.codec.block :as sut]))

(def ^:private cue
  {:index 2 :range {:start {:ms 1500} :end {:ms 3200}} :lines ["two" "lines"]})

;; =============================================================================
;; GOLDEN — the sep-parameterized block + a normalize/split round
;; =============================================================================

(deftest-golden block-golden
  "test/golden/codec-block.edn"
  {:srt   (sut/cue->block "," cue)
   :vtt   (sut/cue->block "." cue)
   :split (sut/split-blocks "﻿1\r\n00:00:00,000 --> 00:00:01,000\nhi\r\n\r\n2\n00:00:01,000 --> 00:00:02,000\nbye\n")})

;; =============================================================================
;; UNIT (shared mut-check body)
;; =============================================================================

(defn- mut-check []
  (is (= "2\n00:00:01,500 --> 00:00:03,200\ntwo\nlines" (sut/cue->block "," cue)))
  (is (= "2\n00:00:01.500 --> 00:00:03.200\ntwo\nlines" (sut/cue->block "." cue)))
  (is (= ["a" "b"] (sut/split-blocks "a\n\nb")))
  (is (= ["x"]     (sut/split-blocks "﻿ x \r\n"))))

(deftest block-shape (mut-check))

;; =============================================================================
;; MUTATION — break the skeleton, prove the assertions catch each
;; =============================================================================

(deftest-mutations cue->block-mutations-caught
  vtranslate.engine.adapters.codec.block/cue->block
  [["ignore-sep"                                             ;; always SRT sep
    (fn [_sep {:keys [index range lines]}]
      (str index "\n" (tc/ms->clock (get-in range [:start :ms]) ",") " --> "
           (tc/ms->clock (get-in range [:end :ms]) ",") "\n" (str/join "\n" lines)))]
   ["drop-index"                                             ;; no index line
    (fn [sep {:keys [range lines]}]
      (str (tc/ms->clock (get-in range [:start :ms]) sep) " --> "
           (tc/ms->clock (get-in range [:end :ms]) sep) "\n" (str/join "\n" lines)))]
   ["space-join-lines"                                       ;; lines on one row
    (fn [sep {:keys [index range lines]}]
      (str index "\n" (tc/ms->clock (get-in range [:start :ms]) sep) " --> "
           (tc/ms->clock (get-in range [:end :ms]) sep) "\n" (str/join " " lines)))]]
  mut-check)

(deftest-mutations split-blocks-mutations-caught
  vtranslate.engine.adapters.codec.block/split-blocks
  [["no-bom-strip" (fn [text] (-> text (str/replace "\r\n" "\n") str/trim (str/split #"\n[ \t]*\n")))]
   ["no-split"     (fn [text] [text])]]
  mut-check)
