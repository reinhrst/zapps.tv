(ns zappo.core)

(import 'javax.sound.sampled.AudioFormat 'javax.sound.sampled.AudioSystem 'java.net.URL 'com.sun.media.sound.WaveFileReader)
(use '[clojure.math.numeric-tower])

(defn sin [n] (Math/sin n))
(defn cos [n] (Math/cos n))
(def PI (Math/PI))

(defn hann_window_at [n]
  "calculates the value for the Hann window at a certain n; assumes a window width of 1"
  (* 0.5 (- 1 (cos (* 2 PI n)))))

(def SAMPLE_FREQUENCY 11025/2)
(def FINGERPRINT_INTERVAL 64)
(def FRAME_LENGTH 2048)
(def HANN_WINDOW
  (loop [result []] 
    (let [l (count result)]
      (if (<= l FRAME_LENGTH)
        (recur (conj result (hann_window_at (/ l FRAME_LENGTH))))
        result))))

(defn get_resampled_audioinputstream [urlstring]
  "creates an audioinputstream at 5500Hz/1ch/16bit, from the url mentioned"
  (AudioSystem/getAudioInputStream ;have to do this in 2 steps, it won't downsample and mono-ize in one conversion
    (new AudioFormat (float SAMPLE_FREQUENCY), 16, 1, true, false)
    (AudioSystem/getAudioInputStream
      (new AudioFormat (float SAMPLE_FREQUENCY), 16, 2, true, false)
      (.getAudioInputStream (new com.sun.media.sound.WaveFileReader) (new URL urlstring)))))

(defn getsample [stream]
  "get a single sample from the (AudioInputStream) stream. Alters the stream, probably not thread-safe"
  (let [x (byte-array 2)]
    (.read stream x)
    (let [x (apply vector x)]
      (+ (x 0) (* 256 (x 1))))))

(defn samples [stream]
  "lazy list of all samples in this stream. Each sample is a signed long (16-bit resolution)"
  (lazy-seq
    (when (> (.available stream) 1)
      (cons (getsample stream) (samples stream)))))

(defn initialize_buffer [stream]
  "Initializes the circular buffer"

  )

(println (take 500000 (samples (get_resampled_audioinputstream "file:///tmp/test.wav"))))
