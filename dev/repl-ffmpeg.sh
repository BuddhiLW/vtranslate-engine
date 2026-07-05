#!/usr/bin/env bash
# Reproducible native (:ffmpeg) nREPL for the engine.collect.* layer.
# Loads bytedeco JavaCV (linux-x86_64 natives) + cider middleware + dev/ helpers.
# Connect your editor to the printed port, then:
#   (require 'vtranslate.engine.dev)
#   (in-ns 'vtranslate.engine.dev)
#   (smoke!)                      ;; hermetic probe+extract round-trip
#   (probe! "/any/file.mp4")
#   (corpus-probe! "speech/pt.mp3")   ;; nil unless $VT_CORPUS / corpus present
#
# First native class-load prints JavaCPP loader noise on stdout — the eval
# RESULT still returns cleanly on a dedicated REPL like this one.
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${1:-7941}"
exec clojure -M:dev:ffmpeg -m nrepl.cmdline \
  --middleware "[cider.nrepl/cider-middleware]" \
  --port "$PORT"
