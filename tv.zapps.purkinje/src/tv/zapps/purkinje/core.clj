(ns tv.zapps.purkinje.core
  (:require tv.zapps.purkinje.fingerprinter tv.zapps.purkinje.provider tv.zapps.purkinje.constants nl.claude.tools.conversion)
  (:use [clojure.tools.logging :as log])
  (:import java.util.Date java.net.ServerSocket)
  (:gen-class)) ; needed else java won't start from it

(def PROTOCOL_VERSION 1)

(defn calculate-timestamp [date]
  "Takes a date object and returns the timestamp such as required in the protocol specification"
  (-> date .getTime (* tv.zapps.purkinje.constants/SAMPLE_FREQUENCY) (/ tv.zapps.purkinje.constants/FINGERPRINT_INTERVAL 1000) long)) ;probably we would want a better resolution at one point but for now this is fine

(defn fingerprint-sequence-from-url-string-for-test [url-string]
  "Takes a url-string, returns a sequence of fingerprints. NOTE: this takes a real-timed stream, only use this for testing with local files"
  (tv.zapps.purkinje.fingerprinter/fingerprint-sequence
   (tv.zapps.purkinje.provider/real-timed-sequence-from-url url-string)))

(defn fingerprint-sequence-from-url-string [url-string]
  "Takes a url-string, returns a sequence of fingerprints"
  (tv.zapps.purkinje.fingerprinter/fingerprint-sequence
   (tv.zapps.purkinje.provider/sequence-from-url url-string)))

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
 (doall (take 2 my-sequence)) ; check that the sequence actually works, and make sure the timestamp is not influenced by startup delay
  (let [start-timestamp (calculate-timestamp (Date.))]
    (log/infof "Gerenator started, at frame-timestamp %d, sequence looks healthy" start-timestamp)
    (loop [timestamped-sequence (map vector (drop 2 my-sequence) (range start-timestamp Double/POSITIVE_INFINITY))]
      (when-let [frst (first timestamped-sequence)]
        (apply dispatch-int frst)
        (recur (rest timestamped-sequence))))))

(defn new-connections-sequence [port]
  "Opens the specified port. For each connection that is made, returns a Java.net.Socket"
  (let [server-socket (java.net.ServerSocket. port)]
    (letfn [(accept-loop []
             (lazy-seq
              (cons (.accept server-socket) (accept-loop))))]
      (accept-loop))))

(defn start-server
  "Starts accepting connections, does not return"
  ([port url-string] (start-server port url-string fingerprint-sequence-from-url-string))
  ([port url-string sequence-function]
     (.start (Thread. #(generator-and-dispatcher (sequence-function url-string))))
     (dorun
      (map
       accept-connection
       connection-number
       (new-connections-sequence port)))))

(defn -main [& args]
  (let [port (Integer/parseInt (nth args 0))
        url-string (nth args 1)]
    (log/infof "Starting Purkinje server on port %d for stream url %s" port url-string)
    (start-server port url-string)))