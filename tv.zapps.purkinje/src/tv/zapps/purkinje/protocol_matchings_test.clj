(ns tv.zapps.purkinje.protocol-matchings-test
  (:use nl.claude.tools.conversion)
  (:use tv.zapps.purkinje.constants)
  (:require clojure.java.io)
  (import java.io.IOException)
  (import java.util.concurrent.Executors))

(defn read-bytes [input-stream nrbytes]
  (if (pos? (.available input-stream))
    (let [my-bytes (byte-array nrbytes)]
      (.read input-stream my-bytes)
      my-bytes)
    [nil nil nil nil]))

(defn choose-fingerprint [my-sequence size]
  (let [length (count my-sequence)
        read-pos (int (* (rand) (- length size)))]
    (take size (drop read-pos my-sequence))))

(defn fingerprint-sequence [filename]
  (println "#Reading" filename)
  (let [totake 200000
        fps (let [input-stream (clojure.java.io/input-stream filename)]
              (read-bytes input-stream 8); protocol nr
              (read-bytes input-stream 8); timestamp
              (loop [fingerprints []]
                (if (> (.available input-stream) 4)
                  (recur (conj fingerprints (-> input-stream (read-bytes 4) byte-array-be-to-number)))
                  (do
                    (.close input-stream)
                    fingerprints))))
        size (count fps)
        start (int (* (rand) (- size totake)))]
    (vec (take totake (drop start fps)))))
    
    

(defn doaverage [matchto-sequences fingerprints surroundings]
  (let [pool (Executors/newFixedThreadPool (+ 2 (.. Runtime getRuntime availableProcessors)))
        results (mapcat
                 (fn [matchto-sequence]
                   (doall
                    (map
                     (fn [matchto-subsequence]
                       (let [p (promise)]
                         (.start
                          (Thread.
                           (fn []
                             (loop [mysamples (take (+ surroundings (count fingerprints)) matchto-subsequence), leastdiff (* 32 (count fingerprints))]
                               (if (>= (count mysamples) (count fingerprints))
                                 (let [diff (apply + (map #(Long/bitCount (bit-xor %1 %2)) mysamples fingerprints))]
                                   (recur (rest mysamples) (Math/min diff leastdiff)))
                                 (deliver p leastdiff))))))
                         p))
                     (partition (+ 1000 (count fingerprints)) matchto-sequence))))
                 matchto-sequences)]
    (map deref results)))



(defn -main [& args]
  (doseq [run (range 10)]
    (println "#Run" run)
    (let [fingerprint-sequences (time (doall (pmap fingerprint-sequence args)))
          all-fpss (shuffle fingerprint-sequences)
          source-sequence (first all-fpss)
          matchto-sequences (rest all-fpss)
          fingerprints (choose-fingerprint source-sequence 256)]
      (doseq [size (range 1 257)
              surroundings [2 1000]]
        (printf "%d %d " size, surroundings)
        (flush)
        (let [stat-data (doaverage matchto-sequences (take size fingerprints) surroundings)]
          (println (apply str (interpose " " stat-data))))))))
