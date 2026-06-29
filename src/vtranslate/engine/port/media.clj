(ns vtranslate.engine.port.media
  "Collect-layer ports (ISP barriers) for media IO. IMediaProbe reads container
   facts; IAudioExtractor demuxes an audio source for ASR. The M5 adapter uses
   bytedeco JavaCV (FFmpegFrameGrabber) BEHIND these protocols — no bytedeco type
   crosses this barrier; the engine depends only on the protocols (DIP).")

(defprotocol IMediaProbe
  "Read container/stream facts from a media URI."
  (probe [this uri]
    "=> (r/ok ingestion/ProbeInfo)
        | (r/err :error/source-unreadable {:source-uri s}
                 | :error/probe-failed {:reason s}).
     Domain-shaped: the adapter projects probe facts onto ProbeInfo and remaps
     :collect/* errors onto the TranslationError ADT before crossing this barrier."))

(defprotocol IAudioExtractor
  "Demux/decode an audio source suitable for ASR."
  (extract-audio [this uri opts]
    "=> (r/ok audio-source)
        | (r/err :error/source-unreadable {:source-uri s}
                 | :error/audio-extract-failed {:reason s}).
     `audio-source` is opaque to the engine and consumed only by ITranscriber."))
