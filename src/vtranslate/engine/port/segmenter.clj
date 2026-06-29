(ns vtranslate.engine.port.segmenter
  "Port (ISP barrier) for cutting phase A — splitting an extracted audio source
   into coarse speech TimeSpans before ASR. An adapter implements ISegmenter via
   VAD or ASR-native timestamps; the engine depends only on this protocol (DIP).
   The method returns a hive-dsl Result of plain boundary DATA — a vector of span
   maps — that steers the transcriber. No transport type crosses this barrier;
   the pure calc layer consumes the spans.")

(defprotocol ISegmenter
  "Cut an extracted audio source into ordered speech spans."
  (segment [this audio-source opts]
    "=> (r/ok {:spans [{:start-ms n :end-ms n} ...]})
        | (r/err :error/segmentation-failed {:reason s}).
     `audio-source` is the opaque value returned by IAudioExtractor; spans are
     ordered, non-overlapping, each with start-ms <= end-ms. `opts` is an adapter
     map and MAY carry :duration-ms (probed media length) to bound the cut."))
