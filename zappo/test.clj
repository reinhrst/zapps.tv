(import 'javax.sound.sampled.AudioFormat 'javax.sound.sampled.AudioSystem 'java.net.URL 'com.sun.media.sound.WaveFileReader)

(defn get_resampled_audioinputstream [urlstring]
  "creates an audioinputstream at 5000Hz/1ch/16bit, from the url mentioned"
  (AudioSystem/getAudioInputStream ;have to do this in 2 steps, it won't downsample and mono-ize in one conversion
    (new AudioFormat 5000.0, 16, 1, true, false)
    (AudioSystem/getAudioInputStream
      (new AudioFormat 5000.0, 16, 2, true, false)
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
  "Initializes the circular buffer")

(println (take 500000 (samples (get_resampled_audioinputstream "file:///tmp/test.wav"))))
