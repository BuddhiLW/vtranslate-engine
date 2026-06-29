(ns vtranslate.engine.collect.protocols
  "Collect-layer ports — the DIP anchors for media decoding.

   Everything in the Collect layer depends on THESE protocols, never on a
   concrete bytedeco/JavaCV type. The concrete backend (vtranslate.engine.collect.ffmpeg)
   is loaded behind the :ffmpeg deps alias and injected; swap it for a test
   double without touching a caller.

   ISP — probing container facts and extracting audio are SEPARATE capabilities.
   A backend opts into each independently (a probe-only or extract-only adapter
   is legal). No god interface.

   Contract every implementation MUST honour:
   - Native time units (ffmpeg counts in MICROSECONDS) are converted to integer
     MILLISECONDS inside the impl, at this boundary — no µs ever escapes upward.
   - Methods may THROW on backend failure (thin, throw-native interop).
     collect.audio wraps an escaping Exception into a Result (try-effect*); native
     Errors propagate to main/run's Throwable guard (=> :error/uncaught).")

(defprotocol IMediaProbe
  "Read container/stream facts from a media source without producing output."
  (probe [this source-uri]
    "source-uri: filesystem path or URL string.
     => map {:container String, :duration-ms long, :has-audio? boolean,
             :audio-codec String, :sample-rate long, :channels long}.
     Durations already in integer milliseconds."))

(defprotocol IAudioExtractor
  "Decode + resample a source's audio track into a PCM/WAV artifact for ASR."
  (extract-audio [this source-uri out-path opts]
    "out-path: caller-owned destination path (the boundary owns temp policy).
     opts: {:sample-rate long, :channels long} — target PCM format.
     Side effect: writes the audio file at out-path. => out-path on success."))
