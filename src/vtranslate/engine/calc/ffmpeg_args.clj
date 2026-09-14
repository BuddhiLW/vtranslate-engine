(ns vtranslate.engine.calc.ffmpeg-args
  "Pure command lines for the system ffmpeg/ffprobe binaries: argv vectors
   in, nothing run. The Collect boundary (collect.ffmpeg-cli) executes them."
  (:require [clojure.string :as str]))

(defn filter-escape
  "A path as a filtergraph option value. Two escaping levels apply: the
   option parser reads backslash and quote, then the graph parser reads
   colon, comma, semicolon and brackets. Escaping each once with a backslash
   survives both."
  ^String [s]
  (str/replace (str s) #"([\\:'\[\],;])" "\\\\$1"))

(defn video-filter
  "The -vf chain: libass renders `ass-path` onto every frame, then the frame
   is scaled when the plan asks for a smaller output. Scaling after the
   subtitles keeps the script's PlayRes equal to the source frame, which is
   the space its font size and margins were computed in."
  ^String [{:keys [width height rescaled?]} ass-path]
  (cond-> (str "subtitles=filename=" (filter-escape ass-path))
    rescaled? (str ",scale=" (long width) ":" (long height))))

(def watermark-out-label
  "The filtergraph label `watermark-graph` leaves the finished video on, and
   the one `burn-args` maps. Named once, because a graph whose last label is
   not the mapped one fails with `Output with label 'x' does not exist`."
  "vid")

(defn watermark-graph
  "The same chain as `video-filter`, followed by the mark overlaid from a
   SECOND input. => a -filter_complex value whose result is on
   `watermark-out-label`.

   An overlaid PNG and not `drawtext`: drawtext needs ffmpeg built with
   libfreetype AND a font fontconfig can resolve, and neither is something
   this code can check from here. When either is missing ffmpeg fails the
   whole graph, so a badge nobody asked to be load-bearing takes the burn down
   with it. `overlay` is a core filter that is always present.

   The mark goes on AFTER any scale, so it is composited at the pixel size it
   will be seen at instead of being resampled with the frame.

   The intermediate label is NOT the output one: a label is consumed by the
   filter that reads it, so naming the subtitled stage `vid` and then feeding
   it to the overlay leaves nothing called `vid` for -map to find."
  ^String [plan ass-path [x-expr y-expr]]
  (str "[0:v]" (video-filter plan ass-path) "[subbed];"
       "[subbed][1:v]overlay=" x-expr ":" y-expr
       "[" watermark-out-label "]"))

(def default-preset
  "x264 speed/size trade. veryfast halves the encode time of medium at a
   small bitrate cost; ultrafast is faster still but visibly softer at the
   same bitrate. Overridable per deployment through :composer-opts :preset."
  "veryfast")

(defn burn-args
  "ffmpeg argv that burns `ass-path` into `source` and writes `out`.
   Video is re-encoded with libx264 at the plan's bitrate and dimensions;
   audio is re-encoded to AAC at the plan's rate when the source has any,
   and dropped otherwise (-an), because mapping an absent stream fails the
   run. -nostdin keeps a stalled worker from waiting on a terminal. The
   container is named (-f mp4) rather than guessed: `out` is the composer's
   temp path, which carries no extension.

   `watermark` (nil, or {:png path :position [x-expr y-expr]}) adds the
   VTranslate mark as a second input composited over the frame. It is an
   argument and not a deployment setting because whether a video carries the
   mark is a property of the PLAN the job was run under, which only the caller
   knows.

   With a mark the graph needs -filter_complex and explicit -maps: once a
   second input exists, ffmpeg's default stream selection is free to take the
   audio from whichever input it prefers, and the PNG has none."
  [{:keys [bin source out ass-path plan preset threads audio? watermark]
    :or   {bin "ffmpeg" preset default-preset threads 1 audio? true}}]
  (let [{:keys [png position]} watermark
        marked? (boolean png)]
    (-> ["-y" "-nostdin" "-hide_banner" "-loglevel" "error"
         "-i" (str source)]
        (into (when marked? ["-i" (str png)]))
        (into (if marked?
                ["-filter_complex" (watermark-graph plan ass-path position)
                 "-map" (str "[" watermark-out-label "]")]
                ["-vf" (video-filter plan ass-path)]))
        (into (when (and marked? audio?) ["-map" "0:a"]))
        (into ["-c:v" "libx264"
               "-preset" (str preset)
               "-b:v" (str (long (:video-bitrate plan)))
               "-pix_fmt" "yuv420p"
               "-threads" (str (long threads))])
        (into (if audio?
                ["-c:a" "aac" "-b:a" (str (long (:audio-bitrate plan)))]
                ["-an"]))
        (into ["-movflags" "+faststart" "-f" "mp4" (str out)])
        (->> (into [(str bin)])))))

(defn probe-args
  "ffprobe argv for the first stream of `kind` (:video | :audio) in `source`,
   as one CSV line. ffprobe prints the fields in ITS section order, not the
   order they were asked for, so the parsers below read that order: video =>
   width,height,r_frame_rate,bit_rate; audio => channels,bit_rate. A stream
   that is absent prints nothing."
  [{:keys [bin source kind] :or {bin "ffprobe"}}]
  (let [[selector entries] (case kind
                             :video ["v:0" "stream=width,height,r_frame_rate,bit_rate"]
                             :audio ["a:0" "stream=channels,bit_rate"])]
    [(str bin) "-v" "error" "-select_streams" selector
     "-show_entries" entries "-of" "csv=p=0" (str source)]))

(defn- parse-long*
  "A CSV field as a long, or nil for N/A and anything else unreadable."
  [s]
  (try (Long/parseLong (str/trim (str s))) (catch Exception _ nil)))

(defn parse-frame-rate
  "ffprobe's r_frame_rate is a ratio string, \"30000/1001\"; a zero
   denominator or garbage reads as nil."
  [s]
  (let [[n d] (str/split (str/trim (str s)) #"/")
        n (parse-long* n)
        d (or (parse-long* d) 1)]
    (when (and n (pos? d)) (/ (double n) (double d)))))

(defn parse-video-probe
  "The video CSV line as {:width :height :frame-rate :video-bitrate}, or nil
   when ffprobe printed nothing (no video stream) or the dimensions are not
   readable. A missing bitrate is nil; calc.encoding then estimates one."
  [line]
  (let [[w h fr br] (str/split (str/trim (str line)) #",")
        width  (parse-long* w)
        height (parse-long* h)]
    (when (and width height (pos? width) (pos? height))
      {:width width
       :height height
       :frame-rate (parse-frame-rate fr)
       :video-bitrate (parse-long* br)})))

(defn parse-audio-probe
  "The audio CSV line (channels,bit_rate) as {:audio-bitrate :channels}, or
   nil when the source has no audio stream."
  [line]
  (let [[ch br] (str/split (str/trim (str line)) #",")
        channels (parse-long* ch)]
    (when (and channels (pos? channels))
      {:audio-bitrate (parse-long* br)
       :channels channels})))

(defn listing-args
  "ffmpeg argv that prints one of its capability listings: :filters or
   :encoders."
  [{:keys [bin listing] :or {bin "ffmpeg"}}]
  [(str bin) "-hide_banner" (case listing
                              :filters "-filters"
                              :encoders "-encoders")])

(defn lists-name?
  "Whether a capability `listing` (the text ffmpeg -filters or -encoders
   prints) has a row for `name`, as a whole word in the name column. A build
   without the feature simply omits the row, so this is the capability
   test; a substring match would take `subtitles_extra` for `subtitles`."
  [listing name]
  (boolean (re-find (re-pattern (str "(?m)^\\s*\\S+\\s+" (java.util.regex.Pattern/quote (str name)) "\\s"))
                    (str listing))))

(def required-capabilities
  "What the burn needs from an ffmpeg build: libass behind the subtitles
   filter and the libx264 encoder. The listing each is read from is the key."
  {:filters ["subtitles"] :encoders ["libx264"]})

(defn capable?
  "Whether `listings` ({:filters text :encoders text}) carries every
   required capability. A nil listing (the binary did not answer) fails it."
  [listings]
  (every? (fn [[listing names]]
            (let [text (get listings listing)]
              (and text (every? #(lists-name? text %) names))))
          required-capabilities))

(defn probe-binary
  "The ffprobe that ships beside `ffmpeg-bin`: same directory, same suffix,
   so a pinned /usr/bin/ffmpeg probes with /usr/bin/ffprobe and a bare
   \"ffmpeg\" probes with a bare \"ffprobe\" from PATH."
  ^String [ffmpeg-bin]
  (let [s (str ffmpeg-bin)
        i (.lastIndexOf s "ffmpeg")]
    (if (neg? i)
      "ffprobe"
      (str (subs s 0 i) "ffprobe" (subs s (+ i 6))))))
