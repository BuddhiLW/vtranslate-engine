(ns vtranslate.engine.port.burner
  "Port (ISP barrier) for the one effect a hardsub needs: burn a subtitle
   track into a video file. The hardsub composer depends on this protocol
   only (DIP); which encoder does the work is an adapter's business.")

(defprotocol IHardsubBurner
  "Burn `track` (a rendered domain SubtitleTrack) into `video-source`,
   writing an H.264/AAC mp4 at `out-path` in `style` (calc.captions keys plus
   `:quality`). Returns `out-path`; throws on failure, and the composer
   boundary maps the throw to :error/compose-failed. The caller owns
   `out-path`'s atomic rename, so an implementation writes exactly there."
  (burn! [burner video-source out-path track style]))
