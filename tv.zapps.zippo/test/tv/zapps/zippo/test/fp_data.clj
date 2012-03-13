(ns tv.zapps.zippo.test.fp-data
  (:require [clojure.string :as string]))

(def ned1-fp (vec (map #(Long/parseLong %) (string/split (slurp "test/tv/zapps/zippo/test/ned1.fp") #" "))))
(def ned2-fp (vec (map #(Long/parseLong %) (string/split (slurp "test/tv/zapps/zippo/test/ned2.fp") #" "))))
(def tomatch-fp (vec (map #(Long/parseLong %) (string/split (slurp "test/tv/zapps/zippo/test/tomatch.fp") #" "))))
(def match-result '(({:timeshift 199, :id 0, :score 1.0} {:timeshift -248, :id 1, :score 0.6238722081228256}) ({:timeshift 199, :id 0, :score 1.0}) ({:timeshift 199, :id 0, :score 1.0}) ({:timeshift 199, :id 0, :score 1.0}) ({:timeshift 199, :id 0, :score 1.0})))

