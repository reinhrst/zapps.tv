(ns zappo.core)

(import 'javax.sound.sampled.AudioFormat 'javax.sound.sampled.AudioSystem 'java.net.URL 'com.sun.media.sound.WaveFileReader)
(use 'nl.claude.tools.dct)

; constants
(def SAMPLE_FREQUENCY 11025/2)
(def FINGERPRINT_INTERVAL 64)
(def FRAME_LENGTH 2048)
(def LOWEST_FREQUENCY 318) ;frequencies we care about for matching
(def HIGEST_FREQUENCY 2000)
(def FREQUENCY_BUCKETS 33)


;calculated constants (and helper functions)
(defn hann-window-at [n]
  "calculates the value for the Hann window at a certain n; assumes a window width of 1"
  (* 0.5 (- 1 (Math/cos (* 2 Math/PI n)))))

(def HANN_WINDOW
  (loop [result []] 
    (let [l (count result)]
      (if (<= l FRAME_LENGTH)
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


;actual code
(defn get-resampled-audioinputstream [urlstring]
  "creates an audioinputstream at 5500Hz/1ch/16bit, from the url mentioned"
  (AudioSystem/getAudioInputStream ;have to do this in 2 steps, it won't downsample and mono-ize in one conversion
    (new AudioFormat (float SAMPLE_FREQUENCY), 16, 1, true, false)
    (AudioSystem/getAudioInputStream
      (new AudioFormat (float SAMPLE_FREQUENCY), 16, 2, true, false)
      (.getAudioInputStream (new com.sun.media.sound.WaveFileReader) (new URL urlstring)))))

(defn getsample [stream]
  "get a single sample from the (AudioInputStream) stream. Alters the stream, probably not thread-safe"
  (let [x (byte-array 2)]
    (if (> (.available stream) 1)
      (do
        (.read stream x)
        (let [x (apply vector x)]
          (+ (x 0) (* 256 (x 1)))))
      nil)))

(defn initial-buffer [stream]
  "Initializes the circular buffer"
    (vec (repeatedly (inc FRAME_LENGTH) (fn [] (getsample stream)))))

(defn progress-buffer [buffer stream]
  (let [newbuf (apply conj (subvec buffer FINGERPRINT_INTERVAL) (repeatedly FINGERPRINT_INTERVAL (fn [] (getsample stream))))]
    (when (-> newbuf peek nil? not) newbuf)))


(defn buffer-sequence 
  ([stream]
   "Provides a sequence where each next item is a new 337ms long buffer, 11.6ms further down the road"
   (let [buffer (initial-buffer stream)]
         (cons buffer (buffer-sequence buffer stream))))
  ([buffer stream]
   (lazy-seq
     (let [buffer (progress-buffer buffer stream)]
       (when buffer
         (cons buffer (buffer-sequence buffer stream)))))))

(defn windowize [pcm-data window]
  (map * pcm-data window))

(defn calculate-energy [pcm-data]
  "Expects a FRAME_LENGTH long sequence of PCM data, returns the energy per frequency bucket"
  (let [dcted-data (vec (dct (windowize pcm-data HANN_WINDOW)))]
    (vec (map
      (fn [bucket-ordinals]
        (/
          (apply +
                 (map (fn [x] (* x x)) (subvec dcted-data (bucket-ordinals 0) (bucket-ordinals 1))))
          1))
        FREQUENCY_BUCKET_EDGES_BY_DCT_ORDINAL))))

(defn calculate-signature [bufseq] ;todo make this a propper lazy sequence
  (let [last-energy (calculate-energy (first bufseq))]
    (loop [bufseq (next bufseq), last-energy last-energy,  result []]
      (if bufseq
        (let [energy (calculate-energy (first bufseq))
              value (apply +
              (map
                     (fn [n]
                       (let [value (-
                                     (- (energy n) (energy (inc n)))
                                     (- (last-energy n) (last-energy (inc n))))]
                         (if (pos? value) (bit-shift-left 1 n) 0)))
                     (range (dec FREQUENCY_BUCKETS))))]
          (recur (next bufseq) energy (conj result value)))
        result))))


(defn difference [sig1 sig2]
  (apply +
         (map #(Long/bitCount (bit-xor %1 %2)) sig1 sig2)))

(let [sig1 (calculate-signature (buffer-sequence (get-resampled-audioinputstream "file:///tmp/test.wav")))
      sig2 (calculate-signature (subvec (vec (take 1512 (buffer-sequence (get-resampled-audioinputstream "file:///tmp/test2.wav")))) 1256))]
  (println (str (count sig1) " " (count sig2)))
  (loop [sig1 sig1 shift 0]
    (when (>= (count sig1) (count sig2)) ;todo: counting won't work when sig is a lazy sequence
      (let [hamming-distance (difference sig1 sig2)]
        (println (str hamming-distance " distance for shift " shift))
        (recur (next sig1) (inc shift))))))

