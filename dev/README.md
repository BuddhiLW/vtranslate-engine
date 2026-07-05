# dev/ — reproducible engine workflows

Helpers + scripts for driving the native (`:ffmpeg`) Collect layer without
hand-typing eval forms. The `:dev` alias puts `dev/` on the classpath.

## Native REPL

```bash
bash dev/repl-ffmpeg.sh        # nREPL on :7941 with bytedeco + cider + dev/
bash dev/repl-ffmpeg.sh 7950   # …on a custom port
```

Connect your editor, then:

```clojure
(require 'vtranslate.engine.dev)
(in-ns 'vtranslate.engine.dev)

(smoke!)                       ; hermetic probe+extract round-trip on a generated WAV
(probe! "/any/file.mp4")       ; probe any media file through the real JavaCv port
(extract! "/any/file.mp4")     ; extract its audio to a temp 16k mono WAV
(corpus-probe! "speech/pt.mp3"); probe a corpus file; nil if no corpus present
```

`(vtranslate.engine.dev/throwing-port)` builds a port over a stub backend whose
calls throw — for exercising the anti-corruption error remap without natives.

> First native class-load floods stdout with JavaCPP loader noise; the eval
> result still returns cleanly on a dedicated REPL like this one.

## Tests

```bash
clojure -M:test                    # unit suite — native-free, fast (the default)
clojure -M:test:itest:ffmpeg       # + native integration suite (test-int/)
```

The integration suite (`test-int/`) is path-isolated: `clojure -M:test` never
puts it on the classpath, so the unit run never loads bytedeco.

## Corpus (location-agnostic)

Corpus-backed checks resolve the corpus dir via, in order:
`$VT_CORPUS`, `./corpus`, `../corpus`, `../vtranslate-cli/corpus`.
They **skip** (not fail) when no corpus is found, so the suite stays
reproducible without it. Point at any location:

```bash
VT_CORPUS=/path/to/corpus clojure -M:test:itest:ffmpeg
```

## One-shot scripts (legacy, out-of-process)

`smoke_ffmpeg.clj`, `contract_collect_port.clj`, `ffmpeg_worker.clj`,
`m5_audio.clj` run via `clojure -M:ffmpeg -i dev/<file>.clj` (each `System/exit`s).
Prefer the REPL helpers above; these remain for stdout-isolated native runs.
