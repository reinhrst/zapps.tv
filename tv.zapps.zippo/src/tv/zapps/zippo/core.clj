(ns tv.zapps.zippo.core
  (:use [clj-yaml :as yaml])
  (:use [clojure.tools.logging :as log])
  (:use [nl.claude.tools.conversion :as conversion])
  (:import java.net.Socket)
  (:gen-class))

(def CHANNEL_CONFIG_FILENAME "/etc/zapps/channel.yaml")

(def FINGERPRINTS_TRIM_LENGTH 100000)
(def FINGERPRINTS_TRIM_TO_LENGTH 80000)

(defn- read-number [inputstream nrbits]
  (byte-array-be-to-number (repeatedly nrbits #(.read inputstream))))

(defmacro assoc-apply [v k f] ; probably this already exists, I just don't know where...
  "applies f to the value of v[k], and stores the result"
  `(assoc ~v ~k (~f (~v ~k))))

(defmacro read-long [inputstream]
  `(read-number ~inputstream 8))

(defmacro read-int [inputstream]
  `(read-number ~inputstream 4))

(defn- handle-purkinje-protocol-1 [inputstream connection-data]
  (let [fingerprint-nr (dec (read-long inputstream))] ;this reads the timestamp of the first fingerprint. Since we didn't receieve any fingerprint yet, we're one earlier
    (swap! connection-data (fn [old-data]
                             (-> old-data
                                 (assoc :lastet-frame-timestamp fingerprint-nr)))))
  (loop []
    (let [fingerprint (read-int inputstream)]
      (swap! connection-data (fn [old-data]
                               (-> old-data
                                   (assoc-apply :lastet-frame-timestamp inc)
                                   (assoc-apply :fingerprints #(conj % fingerprint))))))
    (when (> (count (:fingerprints @connection-data)) FINGERPRINTS_TRIM_LENGTH)
      (log/infof "Purging fingerprints for %s" (:name @connection-data)) ;
      (swap! connection-data assoc-apply new-data :fingerprints #(vec (drop (- (count %) FINGERPRINTS_TRIM_TO_LENGTH) %))))
    (recur)))
                                   
                                 
  

(defn connection [host port channel-name]
  "Returns an atom that will contain among other things the fingerprint data for this connection."
  (let [socket (Socket. host port)
        inputstream (.getInputstream socket)
        connection-data (atom {:name channel-name, :host host, :port port, :socket socket, :latest-frame-timestamp 0, :fingerprints []})]
    (.start (Thread. (fn []
                       (let [protocol-version (read-long inputstream)]
                         (log/infof "Connected to %s (%s:%d), with protocol version %d" channel-name host port protocol-version)
                         (case protocol-nr
                           1 (handle-purkinje-protocol-1 inputstream connection-data)
                           (log/errorf "Failure connecting to %s, protocol version %d unkown" channel-name protocol-version))))))
    connection-data))


(defn -main [& args]
  (let [channels (-> CHANNEL_CONFIG_FILENAME slurp yaml/parse-string)
        fingerprints (map (fn [channel-info]
                            (