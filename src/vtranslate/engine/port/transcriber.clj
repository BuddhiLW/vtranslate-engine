(ns vtranslate.engine.port.transcriber
  "Port (ISP barrier) for speech-to-text. An adapter (M3, e.g. whisper) implements
   ITranscriber; the engine depends only on this protocol (DIP). The method returns
   a hive-dsl Result of plain boundary DATA — a vector of segment maps — which the
   pure calc layer (calc.transcription) promotes into a Transcript aggregate.
   Effects-as-data: the adapter does the IO; the domain never sees the transport.")

(defprotocol ITranscriber
  "Speech-to-text over an extracted audio source."
  (transcribe [this audio-source language opts]
    "=> (r/ok {:segments [{:start-ms n :end-ms n :text s :confidence f} ...]})
        | (r/err :error/asr-failed {:reason s}).
     `audio-source` is the opaque value returned by IAudioExtractor;
     `language` is a BCP-47 source-language tag; `opts` is an adapter map."))
