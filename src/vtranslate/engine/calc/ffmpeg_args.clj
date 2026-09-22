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
  (^String [plan ass-path] (video-filter plan ass-path nil))
  (^String [{:keys [width height rescaled?]} ass-path suffix]
   (cond-> (str "subtitles=filename=" (filter-escape ass-path))
     rescaled?             (str ",scale=" (long width) ":" (long height))
     (not (str/blank? suffix)) (str "," suffix))))

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
  (^String [plan ass-path position] (watermark-graph plan ass-path position nil))
  (^String [plan ass-path [x-expr y-expr] suffix]
   (str "[0:v]" (video-filter plan ass-path) "[subbed];"
        "[subbed][1:v]overlay=" x-expr ":" y-expr
        ;; The suffix lands AFTER the overlay, not on the subtitled stage:
        ;; an encoder's hwupload must be the last thing the frame meets, and
        ;; `overlay` cannot read a hardware surface anyway.
        (when-not (str/blank? suffix) (str "," suffix))
        "[" watermark-out-label "]")))

(def default-preset
  "x264 speed/size trade. veryfast halves the encode time of medium at a
   small bitrate cost; ultrafast is faster still but visibly softer at the
   same bitrate. Overridable per deployment through :composer-opts :preset."
  "veryfast")

(def built-in-encoders
  "The H.264 encoders this release ships knowledge of, as data. Every row is
   a DEFAULT: `encoders-from` merges these UNDER whatever :composer-opts
   :encoders supplies, so a deployment can describe hardware the engine has
   never heard of without a release.

   Row keys:
     :codec          the -c:v name, and the row the -encoders listing must
                     carry.
     :preset-key     the :composer-opts key overriding :default-preset. The
                     keys differ per encoder because the preset vocabularies
                     do: x264's `veryfast` makes h264_nvenc refuse to open,
                     so a deployment's :preset must never reach it.
     :default-preset absent means the encoder takes NO -preset at all, and
                     burn-args emits none. h264_vaapi rejects -preset as an
                     unrecognised option and dies at argv parse.
     :pix-fmt        the -pix_fmt to force; absent means emit none, which is
                     what a hardware encoder consuming its own surfaces
                     needs.
     :device-args    argv emitted BEFORE -i, to open the device.
     :filter-suffix  appended to the filter graph, after any scale and any
                     overlay, to hand the frame to the device.
     :hardware?      marks an encoder whose listing row proves nothing. The
                     distro ffmpeg lists h264_nvenc on a host with no GPU and
                     no driver, then fails at open with `Cannot load
                     libcuda.so.1`; only an encode (`encoder-probe-args`)
                     tells.

   p4 is NVENC's middle preset. Measured 2026-09-15 on an RTX 4070 Laptop
   (driver 610.43, ffmpeg 6.1.1): a 60 s 1080p subtitled burn at 6 Mb/s took
   7.4 s against 31.5 s for x264 veryfast on 3 cores, and 12 concurrent burns
   held ~15x realtime in aggregate with no session refused.

   The VAAPI row is measured capability, not a guess: a vainfo probe on the
   RX 580 (polaris10) node reported VAProfileH264Main and VAProfileH264High
   with VAEntrypointEncSlice on 2026-09-21."
  {:libx264    {:codec "libx264"    :preset-key :preset       :default-preset default-preset
                :pix-fmt "yuv420p"}
   :h264-nvenc {:codec "h264_nvenc" :preset-key :nvenc-preset :default-preset "p4"
                :pix-fmt "yuv420p" :hardware? true}
   :h264-vaapi {:codec "h264_vaapi" :hardware? true
                :device-args ["-vaapi_device" "/dev/dri/renderD128"]
                :filter-suffix "format=nv12,hwupload"}})

(def encoders
  "The built-in rows under their own keys. Kept as a var because callers and
   tests read it; a deployment's additions arrive through `encoders-from`."
  built-in-encoders)

(def default-encoder :libx264)

(defn encoders-from
  "The encoder table `opts` (:composer-opts) describe: the built-in rows
   merged UNDER :encoders from the opts, per row, so a deployment may add a
   whole encoder or override one field of a known one and keep the rest."
  [opts]
  (merge-with merge built-in-encoders (get opts :encoders)))

(defn encoder-spec
  "The encoder row for `encoder`.

   1-arity reads the built-in table; 2-arity reads a table the caller built
   with `encoders-from`. An UNKNOWN key throws: silently substituting
   libx264 meant a deployment naming hardware got a slow CPU burn, a correct
   looking file and no log line, which is precisely the failure this
   signature removes."
  ([encoder] (encoder-spec encoders encoder))
  ([table encoder]
   (or (get table encoder)
       (throw (ex-info (str "unknown encoder " (pr-str encoder)
                            "; known: " (pr-str (vec (sort-by str (keys table)))))
                       {:error :error/unknown-encoder
                        :encoder encoder
                        :known (vec (sort-by str (keys table)))})))))

(defn encoder-preset
  "The preset `opts` (:composer-opts merged with the job's style) give
   `encoder`, read from that encoder's own key, else its default. nil for an
   encoder that takes no preset, which is what burn-args wants to see."
  [encoder opts]
  (let [{:keys [preset-key default-preset]} (encoder-spec (encoders-from opts) encoder)]
    (when-let [p (or (when preset-key (get opts preset-key)) default-preset)]
      (str p))))

(defn burn-args
  "ffmpeg argv that burns `ass-path` into `source` and writes `out`.
   Video is re-encoded with `encoder` (an `encoders` key, default libx264)
   at the plan's bitrate and dimensions;
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
  [{:keys [bin source out ass-path plan encoder encoders preset threads audio? watermark]
    :or   {bin "ffmpeg" encoder default-encoder threads 1 audio? true}}]
  (let [{:keys [png position]} watermark
        table (or encoders built-in-encoders)
        {:keys [codec device-args filter-suffix pix-fmt] :as spec}
        (encoder-spec table encoder)
        marked? (boolean png)
        ;; nil preset is not "use the default": a row with no :default-preset
        ;; is an encoder that takes none, and h264_vaapi dies on the flag.
        preset  (or preset (:default-preset spec))]
    (-> ["-y" "-nostdin" "-hide_banner" "-loglevel" "error"]
        (into device-args)
        (into ["-i" (str source)])
        (into (when marked? ["-i" (str png)]))
        (into (if marked?
                ["-filter_complex" (watermark-graph plan ass-path position filter-suffix)
                 "-map" (str "[" watermark-out-label "]")]
                ["-vf" (video-filter plan ass-path filter-suffix)]))
        (into (when (and marked? audio?) ["-map" "0:a"]))
        (into ["-c:v" codec])
        (into (when preset ["-preset" (str preset)]))
        (into ["-b:v" (str (long (:video-bitrate plan)))])
        (into (when pix-fmt ["-pix_fmt" (str pix-fmt)]))
        (into ["-threads" (str (long threads))])
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

(defn capabilities-for
  "What a burn with `encoder` needs from an ffmpeg build: libass behind the
   subtitles filter and that encoder's row. The listing each is read from is
   the key."
  ([encoder] (capabilities-for built-in-encoders encoder))
  ([table encoder]
   {:filters ["subtitles"] :encoders [(:codec (encoder-spec table encoder))]}))

(def required-capabilities
  "The default (libx264) burn's capabilities."
  (capabilities-for default-encoder))

(defn capable?
  "Whether `listings` ({:filters text :encoders text}) carries every
   capability `encoder` (default libx264) needs. A nil listing (the binary
   did not answer) fails it."
  ([listings] (capable? listings default-encoder))
  ([listings encoder] (capable? listings built-in-encoders encoder))
  ([listings table encoder]
   (every? (fn [[listing names]]
             (let [text (get listings listing)]
               (and text (every? #(lists-name? text %) names))))
           (capabilities-for table encoder))))

(defn encoder-probe-args
  "ffmpeg argv that opens `encoder` for real: one black 320x240 frame from
   the lavfi source, encoded and thrown away. Exit 0 is the only evidence a
   hardware encoder can start here; its listing row is not (see `encoders`).
   320x240 clears NVENC's minimum frame size."
  [{:keys [bin encoder encoders] :or {bin "ffmpeg"}}]
  (let [{:keys [codec device-args filter-suffix]}
        (encoder-spec (or encoders built-in-encoders) encoder)]
    (-> [(str bin) "-hide_banner" "-loglevel" "error" "-nostdin"]
        (into device-args)
        (into ["-f" "lavfi" "-i" "color=black:size=320x240:duration=0.1"])
        ;; A device encoder cannot read a system-memory frame: the probe has
        ;; to upload the same way a burn does, or it fails for a reason that
        ;; says nothing about the hardware.
        (into (when-not (str/blank? filter-suffix) ["-vf" filter-suffix]))
        (into ["-frames:v" "1" "-c:v" codec "-f" "null" "-"]))))

(def hardware-open-failures
  "Fragments of the lines ffmpeg prints when a hardware encoder cannot be
   OPENED: no driver library, no device, a driver older than the API, or no
   free encode session. `Cannot load libcuda.so.1` is the one observed on a
   GPU-less host.

   The VAAPI fragments come from libavutil/hwcontext_vaapi.c and the vaapi
   encoder wrapper: a pod with no /dev/dri node, a render node it cannot
   open, or a card whose profiles do not include H.264 encode. A polaris10
   card missing VAEntrypointEncSlice is the same class of failure as a
   missing NVENC session and deserves the same CPU rerun."
  ["Cannot load libcuda"
   "Cannot load libnvidia-encode"
   "No capable devices found"
   "No NVENC capable devices found"
   "OpenEncodeSessionEx failed"
   "CUDA_ERROR_"
   "Driver does not support the required nvenc API version"
   "minimum required Nvidia driver for nvenc"
   "Failed to initialise VAAPI connection"
   "Failed to open VAAPI device"
   "No VA display found"
   "Device creation failed"
   "No usable encoding profile found"
   "vaInitialize failed"])

(defn hardware-unavailable?
  "Whether a failed burn's stderr says the hardware encoder could not be
   opened, as opposed to the media or the filtergraph being at fault. Only
   the former is worth re-running on the CPU: a bad source fails libx264
   identically, and burning it twice doubles the cost of a failure."
  [stderr]
  (let [s (str stderr)]
    (boolean (some #(str/includes? s %) hardware-open-failures))))

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
