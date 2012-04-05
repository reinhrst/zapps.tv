(ns tv.zapps.zippo.purkinje-connection
  (:require [clojure.tools.logging :as log])
  (:require [tv.zapps.zippo.tools :as tools])
  (:import java.net.Socket)
  (:import java.io.DataInputStream)
  (:gen-class))

(def FINGERPRINTS_TRIM_LENGTH 100000)
(def FINGERPRINTS_TRIM_TO_LENGTH 80000)

(defmacro assoc-apply [v k f] ; probably this already exists, I just don't know where...
  "applies f to the value of v[k], and stores the result"
  `(assoc ~v ~k (~f (~v ~k))))

(defn- read-fingerprint-to-connection-data [input-stream connection-data]
    (let [fingerprint (.readInt input-stream)]
      (swap! connection-data (fn [old-data]
                               (-> old-data
                                   (assoc-apply :latest-frame-timestamp inc)
                                   (assoc-apply :fingerprints #(conj % fingerprint)))))))

(defn- trim-connection-data-if-too-long [connection-data]
  (when (> (count (:fingerprints @connection-data)) FINGERPRINTS_TRIM_LENGTH)
    (when (or (not (:initial-trim @connection-data)) (> (count (:fingerprints @connection-data)) (:initial-trim @connection-data)))
      (log/infof "%d: Purging fingerprints for %s" (:id @connection-data) (:name @connection-data))
      (swap! connection-data (fn [old-data]
                               (-> old-data
                                   (assoc-apply :fingerprints #(vec (drop (- (count %) FINGERPRINTS_TRIM_TO_LENGTH) %)))
                                   (dissoc :initial-trim))))
      (log/infof "%d: Done purging fingerprints for %s" (:id @connection-data) (:name @connection-data)))))
  

(defn- handle-purkinje-protocol-1 [input-stream connection-data]
  (let [fingerprint-nr (dec (.readLong input-stream))] ;this reads the timestamp of the first fingerprint. Since we didn't receieve any fingerprint yet, we're one earlier
    (swap! connection-data (fn [old-data]
                             (-> old-data
                                 (assoc :latest-frame-timestamp fingerprint-nr)))))
  (loop []
    (read-fingerprint-to-connection-data input-stream connection-data)
    (trim-connection-data-if-too-long connection-data)
    (recur)))

(defn connection [host port channel-name id]
  "Returns an atom that will contain among other things the fingerprint data for this connection."
  (log/infof "%d: Creating connection for %s (%s:%d)" id channel-name host port)
  (let [socket (Socket. host port)
        input-stream (DataInputStream. (.getInputStream socket))
        connection-data (atom {:id id :name channel-name, :host host, :port port, :socket socket, :latest-frame-timestamp 0, :fingerprints []})]
    (.start (Thread. (fn []
                       (let [protocol-version (.readLong input-stream)]
                         (log/infof "%d: Connected to %s (%s:%d), with protocol version %d" id channel-name host port protocol-version)
                         (case protocol-version
                           1 (handle-purkinje-protocol-1 input-stream connection-data)
                           (log/errorf "%d: Failure connecting to %s, protocol version %d unkown" id channel-name protocol-version))))))
    connection-data))


(defn distribute-initial-trim-to-avoid-temporal-hotspot [connection-datas]
  (doall
   (map
    #(swap! %1 assoc :initial-trim (+
                                    FINGERPRINTS_TRIM_LENGTH
                                    (/
                                     (*
                                      (-
                                       FINGERPRINTS_TRIM_LENGTH
                                      FINGERPRINTS_TRIM_TO_LENGTH)
                                      %2)
                                     (count connection-datas))))
    connection-datas
    (range))))
