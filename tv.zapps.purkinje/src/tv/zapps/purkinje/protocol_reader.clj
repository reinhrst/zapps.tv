"small standalone protocol reader. Connect this with nc to the server, and it will decode what it's getting"

(ns tv.zapps.purkinje.protocol-reader
  (:use nl.claude.tools.conversion))

(defn read-bytes [input-stream nrbytes]
  (while (> nrbytes (.available input-stream))
    (Thread/sleep 10))
  (let [my-bytes (byte-array nrbytes)]
    (.read input-stream my-bytes)
    my-bytes))

(println "Protocol version:" (-> System/in (read-bytes 8) byte-array-be-to-number))
(println "Frame timestamp: " (-> System/in (read-bytes 8) byte-array-be-to-number))
(while true
  (let [item (-> System/in (read-bytes 4) byte-array-be-to-number)]
    (println (apply str
                    (map
                     #(if (zero? (bit-and item (bit-shift-left 1 %))) " " "X")
                     (range 32))))))
  
