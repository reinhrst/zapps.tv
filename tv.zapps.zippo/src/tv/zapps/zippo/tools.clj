(ns tv.zapps.zippo.tools
  (:require [nl.claude.tools.conversion :as conversion]))

(defn read-number [inputstream nrbits]
  (conversion/byte-array-be-to-number (repeatedly nrbits #(.read inputstream))))

(defmacro read-long [inputstream]
  `(read-number ~inputstream 8))

(defmacro read-int [inputstream]
  `(read-number ~inputstream 4))

(defmacro read-handset-id [inputstream]
  `(apply str (repeatedly 32 #(format "%02x" (.read ~inputstream)))))

(defn write-int [outputstream value]
  (.write outputstream (conversion/int-to-byte-array-be value)))

(defn write-long [outputstream value]
  (.write outputstream (conversion/long-to-byte-array-be value)))

(defn write-byte [outputstream value]
  (.write outputstream (int value))) ; all functions writing to sockets want bytes, except writing a single byte which wants an int :(

