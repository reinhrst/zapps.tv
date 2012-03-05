(ns tv.zapps.purkinje.fingerprinter
  "Has the functions to transform a sequence of samples into fingerprints"
  (:use tv.zapps.purkinje.constants nl.claude.tools.dct))

(defn- windowize [samples window]
  (map * samples window))

(defn- calculate-energy-diff [samples]
  "Will calculate the energy per frequency-bucket for the windowized samples"
  (let [dcted-data (vec (dct (windowize samples HANN_WINDOW)))
        energy (map
                (fn [bucket-ordinals]
                  (/
                   (apply +
                          (map (fn [x] (* x x)) (subvec dcted-data (bucket-ordinals 0) (bucket-ordinals 1))))
                   1))
                FREQUENCY_BUCKET_EDGES_BY_DCT_ORDINAL)]
    (map - energy (next energy))))

(defn calculate-fingerprint [new-energy-diff old-energy-diff]
  (apply +
         (map-indexed
          #(if (pos? %2) (bit-shift-left 1 %1) 0)
          (map - new-energy-diff old-energy-diff))))

(defn fingerprint-sequence [sample-sequence]
  "Creates a lazy sequence of fingerprints (long, 32-bit; java doesn't have unsigned int) from the input sequence
   The input sequence is expected to be 16 bit SAMPLE_FREQUENCY mono PCM."
  (letfn [(worker [buffer input-sequence old-energy-diff]
            (lazy-seq
              (let [new-buffer (concat (drop FINGERPRINT_INTERVAL buffer) (take FINGERPRINT_INTERVAL input-sequence))
                    new-input-sequence (drop FINGERPRINT_INTERVAL input-sequence)]
                (when (= (count new-buffer) (inc FRAME_LENGTH)) ;else we ran out of buffer, basically the stream ended
                  (let [new-energy-diff (calculate-energy-diff new-buffer)
                        fingerprint (calculate-fingerprint new-energy-diff old-energy-diff)]
                    (cons fingerprint (worker new-buffer new-input-sequence new-energy-diff)))))))]
    (let [buffer-size (inc FRAME_LENGTH) ; we need one more for dct to work
          initial-buffer (take buffer-size sample-sequence)
          working-sequence (drop buffer-size sample-sequence)]
      (worker initial-buffer working-sequence (calculate-energy-diff initial-buffer)))))