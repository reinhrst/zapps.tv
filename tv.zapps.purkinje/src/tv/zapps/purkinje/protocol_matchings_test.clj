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
  (println "Reading" filename)
  (let [input-stream (clojure.java.io/input-stream filename)]
    (read-bytes input-stream 8); protocol nr
    (read-bytes input-stream 8); timestamp
    (loop [fingerprints []]
      (if (and (> (.available input-stream) 4) (< (count fingerprints) 100000))
        (recur (conj fingerprints (-> input-stream (read-bytes 4) byte-array-be-to-number)))
        (do
          (.close input-stream)
          fingerprints)))))

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
  (let [fingerprint-sequences (time (doall (pmap fingerprint-sequence args)))
        results (apply merge
                       (for [run (range 5)
                             size (range 88 257)
                             surroundings [0 1 2 1000]]
                         (let [all-fpss (shuffle fingerprint-sequences)
                               source-sequence (first all-fpss)
                               matchto-sequences (rest all-fpss)
                               fingerprints (choose-fingerprint source-sequence size)]
                           (printf "Size %3d, surroundings %4d run %1d;  " size, surroundings, run)
                           (flush)
                           (let [stat-data (doaverage matchto-sequences fingerprints surroundings)
                                 mean (/ (apply + stat-data) (count stat-data))
                                 var (/ (apply + (map #(* (- mean %) (- mean %)) stat-data)) (count stat-data))
                                 dist (for [x (range (int (- mean (Math/sqrt var))) (int (- mean (* 8 (Math/sqrt var)))) -1)]
                                        [x (/ (count (filter (partial < x) stat-data)) (count stat-data))])]
                             (let [myresult {{:size size, :surroundings surroundings, :run run}
                                             {:p (float (/ mean (* 32 size)))
                                              :n (count stat-data)
                                              :var (float var)
                                              :0.90 (first (first (drop-while #(> 0.9 (second %)) dist)))
                                              :0.95 (first (first (drop-while #(> 0.95 (second %)) dist)))
                                              :0.99 (first (first (drop-while #(> 0.99 (second %)) dist)))
                                              :0.995 (first (first (drop-while #(> 0.995 (second %)) dist)))
                                              :0.999 (first (first (drop-while #(> 0.999 (second %)) dist)))}}]
                               (println myresult)
                                                myresult)))))]
    (doseq [k (sort-by #(+ (* 100000 (:size %)) (*  10 (:surroundings %)) (:run %)) (keys results))]
      (println k (results k)))))
