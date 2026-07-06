(ns vtranslate.engine.port.composer
  "Port (ISP barrier) for video composition — mux a rendered subtitle track back
   into the source video. An adapter (M3) implements IVideoComposer; the engine
   depends only on this protocol (DIP).")

(defprotocol IVideoComposer
  "Compose a source video with a rendered SubtitleTrack into an output video:
   soft-mux (embed a selectable subtitle stream, stream-copy) or hardsub (burn
   the cues into the picture, re-encode)."
  (compose [this video-source subtitle-track opts]
    "=> (r/ok {:output-uri s}) | (r/err :error/compose-failed {:reason s}).
     `video-source` is the source video URI; `subtitle-track` is a domain
     SubtitleTrack (ordered Cues over time ranges); `opts` may carry
     :output-uri (boundary-owned sink path)."))
