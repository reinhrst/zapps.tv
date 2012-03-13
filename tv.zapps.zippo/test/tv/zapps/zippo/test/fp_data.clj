(ns tv.zapps.zippo.test.fp-data
  (:require [clojure.string :as string]))

(def ned1-fp (vec (map #(Long/parseLong %) (string/split (slurp "test/tv/zapps/zippo/test/ned1.fp") #" "))))
(def ned2-fp (vec (map #(Long/parseLong %) (string/split (slurp "test/tv/zapps/zippo/test/ned2.fp") #" "))))
(def tomatch-fp (vec (map #(Long/parseLong %) (string/split (slurp "test/tv/zapps/zippo/test/tomatch.fp") #" "))))


