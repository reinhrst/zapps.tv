(ns tv.zapps.purkinje.provider
  (:use tv.zapps.purkinje.constants nl.claude.tools.timed-sequence))

(defn- get-sample [inputstream]
  "gets an audio sample from the stream. Note that this modifies the stream"
  (let [ls-byte (.read inputstream)]
    (when (not= ls-byte -1)
      (let [ms-byte (.read inputstream)]
        (when (not= ms-byte -1)
          (bit-or ls-byte (bit-shift-left ms-byte 8)))))))
      
(defn- lazy-sequence-from-stream [stream]
  (lazy-seq
    (when-let [sample (get-sample stream)]
      (cons sample (lazy-sequence-from-stream stream)))))

(defn sequence-from-url [url-string]
  "Takes a url (which may be a file, http, rtsp, or basically anything that VLC understands), and returns a lazy resampled PCM sequence from this"
  (->>
   url-string
   VLC_COMMAND ;already makes the stream mono 16 bit at the right sample-rate
   (into-array String)
   (.exec (Runtime/getRuntime))
   .getInputStream
   lazy-sequence-from-stream))
   
(defn real-timed-sequence-from-url [url-string]
  "See sequence-from-url, but makes the stream come available real-time (i.e. at playback speed). This is mainly for testing purposes with pre-recorded streams. Obviously live-streams already come available real-time"
  (timed-sequence (sequence-from-url url-string) (/ SAMPLE_FREQUENCY)))
