(use '[clojure.string :only (split)])
(def sourcefile (nth *command-line-args* 2))
(def targetfile (nth *command-line-args* 3))
(if (not= (count *command-line-args*) 4)
  (println "Need 4 arguments in total: cmd -- sourcefile targetfile")
  (do
    (println "Creating uncorrelated matches from" sourcefile "to" targetfile)


    (def runs (drop 1 (split (slurp sourcefile) #"\n?#Run [0-9]+\n")))
    (def perrun
      (filter
       #(= 512 (count %))
       (map
        (fn [run]
          (filter seq
                  (map
                   #(if (= (first %) \#)
                      []
                      (map
                       (fn [number] (Integer/parseInt number))
                       (split % #" ")))
                   (split run #"\n"))))
        runs)))

    (doseq [run perrun]
      (let [sum (apply + (apply concat run))
            total (count (apply concat run))
            avg (/ sum total)]
        (printf "Sum %8d total %8d avg %7.2f\n" sum total (float avg))))

    (flush)
    
    (def lines (apply concat perrun))

    (defn merge-lines [h l]
      (let [key {:size (first l) :surroundings (second l)}
            data (drop 2 l)]
        (assoc h key (concat (get h key) data))))
    
    (def per-size-and-surrounding
      (reduce merge-lines (cons {} lines)))

    (def cummulative-info
      (apply merge {}
             (pmap
              (fn [[map-key data]]
                (let [freqs (frequencies data)]
                  [map-key
                   (vec
                    (take (* 32 (:size map-key))
                          (concat 
                           (loop [result (vec (repeat (apply min (keys freqs)) 1))]
                             (if (> (peek result) 0)
                               (recur
                                (conj result
                                      (let [i (count result)]
                                        (-
                                         (if (zero? i) 1 (result (dec i)))
                                         (/
                                          (if-let [c (get freqs i)] c 0)
                                          (count data))))))
                               (butlast result)))
                           (repeat 0))))]))
              per-size-and-surrounding)))

    (def result (str "
(ns tv.zapps.zippo.uncorrelated-matching-data)

(declare DATA)

(defn get-uncorrelated-results [size surroundings]
  (DATA {:size size :surroundings surroundings :run 0}))

(def DATA {\n"
(apply str
       (sort
        (map
         (fn [[key data]]
           (str (format "           {:size %3d, :surroundings %4d}" (:size key) (:surroundings key)) " " data ",\n"))
         cummulative-info)))
       "           })
"))

(spit targetfile result)


(println "done")))
