(ns tv.zapps.zippo.test.matcher
  (:use [tv.zapps.zippo.matcher])
  (:use [tv.zapps.zippo.test.fp-data])
  (:use [clojure.test]))




(deftest test-matching
  (let [connection-datas (atom
                         [(atom {
                                 :id 0
                                 :name "Nederland 1"
                                 :fingerprints ned1-fp
                                 :latest-frame-timestamp 1000300
                                 })
                          (atom {
                                 :id 1
                                 :name "Nederland 2"
                                 :fingerprints ned2-fp
                                 :latest-frame-timestamp 1000305
                                 })])
        input-sequence (map #(hash-map :fingerprint %1 :timestamp %2) tomatch-fp (range 1000000 9999999))
        match-result (matcher input-sequence connection-datas)]
    (println (seq (take 5 match-result)))))
