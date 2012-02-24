(ns tv.zapps.purkinje.provider
  (:import javax.sound.sampled.AudioFormat javax.sound.sampled.AudioSystem com.sun.media.sound.WaveFileReader)
  (:use tv.zapps.purkinje.constants))


(defn resample-audio-stream [audiostream]
  "creates an audioinputstream at 5500Hz/1ch/16bit, from the url mentioned"
  (AudioSystem/getAudioInputStream ;have to do this in 2 steps, it won't downsample and mono-ize in one conversion
    (new AudioFormat (float SAMPLE_FREQUENCY), 16, 1, true, false)
    (AudioSystem/getAudioInputStream
      (new AudioFormat (float SAMPLE_FREQUENCY), 16, 2, true, false) audiostream)))

(defn lazy-sequence-from-stream [stream]
  (let [get-sample (fn [] 
                     (let [x (byte-array 2)]
                       (if (> (.available stream) 1)
                         (do
                           (.read stream x)
                           (let [x (apply vector x)]
                             (+ (x 0) (* 256 (x 1)))))
                         nil)))]
    (lazy-seq
      (when-let [sample get-sample]
        (cons sample (lazy-sequence-from-stream stream))))))



