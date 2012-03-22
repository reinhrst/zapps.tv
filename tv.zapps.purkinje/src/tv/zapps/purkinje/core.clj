(ns tv.zapps.purkinje.core
  (:require tv.zapps.purkinje.fingerprinter tv.zapps.purkinje.provider tv.zapps.purkinje.constants nl.claude.tools.conversion nl.claude.tools.net)
  (:require [clojure.tools.logging :as log])
  (:import java.util.Date)
  (:gen-class)) ; needed else java won't start from it

(def PROTOCOL_VERSION 1)

(defn calculate-timestamp [date]
  "Takes a date object and returns the timestamp such as required in the protocol specification"
  (-> date .getTime (* tv.zapps.purkinje.constants/SAMPLE_FREQUENCY) (/ tv.zapps.purkinje.constants/FINGERPRINT_INTERVAL 1000) long)) ;probably we would want a better resolution at one point but for now this is fine

(def connection-number (range))
(def connections (atom []))

(defn- accept-connection [id socket]
  (log/infof "%d: New connection to %s:%d"  id (-> socket .getInetAddress .toString) (.getPort socket))
  (let [connection (agent {:id id, :socket socket, :in (.getInputStream socket), :out (.getOutputStream socket), :bytes-sent 0})]
    (swap! connections conj connection)))

(defn- close-connection [connection]
  (log/infof "%d: Connection closed, %d bytes were sent" (:id connection) (:bytes-sent connection))
  (when (-> connection :socket .isClosed not)
    (-> connection :socket .close))
  (swap! connections (fn [conns] (doall (remove #(identical? connection %) conns)))))

(defn- send-bytes [ag byte-a]
  (send-off ag (fn [connection]
                 (when connection ;else the connection has already been closed, and we're just receiving the last items because of race conditions
                   (try
                     (do
                       (.write (:out connection) byte-a)
                       (assoc connection :bytes-sent (+ (connection :bytes-sent) (count byte-a))))
                     (catch Exception e
                       (close-connection connection) nil))))))

(defn- send-header [ag timestamp]
  (send-bytes ag (nl.claude.tools.conversion/long-to-byte-array-be PROTOCOL_VERSION))
  (send-bytes ag (nl.claude.tools.conversion/long-to-byte-array-be timestamp)))

(defn- dispatch-bytes [message-bytes timestamp]
  (doall
   (map
    (fn [ag]
      (try
        (when (zero? (:bytes-sent @ag))
          (send-header ag timestamp))
        (send-bytes ag message-bytes)
        (catch NullPointerException e "this may happen in race condition if connection is closed but agent not yet removed from connections; ignore")))
    @connections)))

(defn dispatch-int [message-int timestamp]
  (dispatch-bytes
   (nl.claude.tools.conversion/int-to-byte-array-be message-int); note: message int is actually long, but that's, will work as uint
   timestamp))

(defn- generator-and-dispatcher [my-sequence]
 (doall (take 1000 my-sequence)) ; check that the sequence actually works, and make sure the timestamp is not influenced by startup delay
  (let [start-timestamp (calculate-timestamp (Date.))]
    (log/infof "Generator started, at frame-timestamp %d, sequence looks healthy" start-timestamp)
    (loop [timestamped-sequence (map vector (drop 1000 my-sequence) (range start-timestamp Double/POSITIVE_INFINITY))]
      (when-let [frst (first timestamped-sequence)]
        (when (zero? (bit-and (second frst) 0xFFFF))
          (log/infof "Stream timestamp %d, wall clock timestamp %d, offset %d" (second frst) (calculate-timestamp (Date.)) (- (calculate-timestamp (Date.)) (second frst))))
        (apply dispatch-int frst)
        (recur (rest timestamped-sequence))))))

(defn start-server [port device frequency-mhz]
  "Starts accepting connections, does not return"
  (.start (Thread. #(generator-and-dispatcher
                     (tv.zapps.purkinje.fingerprinter/fingerprint-sequence
                      (tv.zapps.purkinje.provider/sequence-from-device device frequency-mhz)))))
  (dorun
   (map
    accept-connection
    connection-number
    (nl.claude.tools.net/new-connections-sequence port))))

(defn -main [& args]
  (if (not= (count args) 3)
    (log/fatal "Start Purkinje with 3 arguments: purkinje #server-port# #device# #frequency-mhz#")
    (let [port (Integer/parseInt (nth args 0))
          device (nth args 1)
          frequency-mhz (Integer/parseInt (nth args 2))]
      (log/infof "Starting Purkinje server on port %d for %s on frequency %d" port device frequency-mhz)
      (start-server port device frequency-mhz))))
