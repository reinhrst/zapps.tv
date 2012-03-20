(ns tv.zapps.purkinje.constants)

(def SAMPLE_FREQUENCY 5500)
(def FINGERPRINT_INTERVAL 64)
(def FRAME_LENGTH 2048)
(def LOWEST_FREQUENCY 318) ;frequencies we care about for matching
(def HIGEST_FREQUENCY 2000)
(def FREQUENCY_BUCKETS 33)


;calculated constants (and helper functions)
(defn- hann-window-at [n]
  "calculates the value for the Hann window at a certain n; assumes a window width of 1"
  (* 0.5 (- 1 (Math/cos (* 2 Math/PI n)))))

(def HANN_WINDOW
  (loop [result []] 
    (let [l (count result)]
      (if (< l FRAME_LENGTH)
        (recur (conj result (hann-window-at (/ l FRAME_LENGTH))))
        result))))

(def LOG_LOWEST_FREQENCY (Math/log LOWEST_FREQUENCY))
(def LOG_HIGEST_FREQENCY (Math/log HIGEST_FREQUENCY))
(def LOG_BUCKET_SIZE (/ (- LOG_HIGEST_FREQENCY LOG_LOWEST_FREQENCY) FREQUENCY_BUCKETS))

(def FREQUENCY_BUCKET_EDGES_BY_FREQUENCY
  (map
    (fn [bucketnr]
      [
       (Math/pow Math/E (+ LOG_LOWEST_FREQENCY (* bucketnr LOG_BUCKET_SIZE)))
       (Math/pow Math/E (+ LOG_LOWEST_FREQENCY (* (inc bucketnr) LOG_BUCKET_SIZE)))
      ])
    (range FREQUENCY_BUCKETS)))

(def FREQUENCY_BUCKET_EDGES_BY_DCT_ORDINAL ;is an vector of vectors, each one specifying the first (inclusive) and the last (not inclusive) dct coefficient to take
  (map
    (fn [freqs]
      [
       (Math/round (/ (* (freqs 0) 2 FRAME_LENGTH) SAMPLE_FREQUENCY))
       (Math/round (/ (* (freqs 1) 2 FRAME_LENGTH) SAMPLE_FREQUENCY))
       ])
    FREQUENCY_BUCKET_EDGES_BY_FREQUENCY))


(def VLC_BIN "cvlc")

(defn VLC_COMMAND [url-string]
  [
   VLC_BIN
   "-q"
   url-string
   "vlc://quit"
   "--sout"
   (str "#transcode{acodec=s16l,channels=1,samplerate=" (float SAMPLE_FREQUENCY) "}:es{access-audio=file,mux-audio=raw,dst-audio=-,access-video=none}")
   ])