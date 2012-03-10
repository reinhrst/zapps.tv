(ns tv.zapps.zippo.core
  (:use [clj-yaml.core :as yaml])
  (:use [clojure.tools.logging :as log])
  (:use [nl.claude.tools.conversion :as conversion])
  (:import java.net.Socket)
  (:gen-class))

(def CHANNEL_CONFIG_FILENAME "/etc/zapps/channels.yaml")

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

(defn- read-fingerprint-to-connection-data [inputstream connection-data]
    (let [fingerprint (read-int inputstream)]
      (swap! connection-data (fn [old-data]
                               (-> old-data
                                   (assoc-apply :latest-frame-timestamp inc)
                                   (assoc-apply :fingerprints #(conj % fingerprint)))))))

(defn- trim-connection-data-if-too-long [connection-data]
  (when (> (count (:fingerprints @connection-data)) FINGERPRINTS_TRIM_LENGTH)
    (when (or (not (:initial-trim @connection-data)) (> (count (:fingerprints @connection-data)) (:initial-trim @connection-data)))
      (log/infof "Purging fingerprints for %s" (:name @connection-data))
      (swap! connection-data (fn [old-data]
                               (-> old-data
                                   (assoc-apply :fingerprints #(vec (drop (- (count %) FINGERPRINTS_TRIM_TO_LENGTH) %)))
                                   (dissoc :initial-trim))))
      (log/infof "Done purging fingerprints for %s" (:name @connection-data)))))
  

(defn- handle-purkinje-protocol-1 [inputstream connection-data]
  (let [fingerprint-nr (dec (read-long inputstream))] ;this reads the timestamp of the first fingerprint. Since we didn't receieve any fingerprint yet, we're one earlier
    (swap! connection-data (fn [old-data]
                             (-> old-data
                                 (assoc :latest-frame-timestamp fingerprint-nr)))))
  (loop []
    (read-fingerprint-to-connection-data inputstream connection-data)
    (trim-connection-data-if-too-long connection-data)
    (recur)))

(defn connection [host port channel-name]
  "Returns an atom that will contain among other things the fingerprint data for this connection."
  (log/infof "Creating connection for %s (%s:%d)" channel-name host port)
  (let [socket (Socket. host port)
        inputstream (.getInputStream socket)
        connection-data (atom {:name channel-name, :host host, :port port, :socket socket, :latest-frame-timestamp 0, :fingerprints []})]
    (.start (Thread. (fn []
                       (let [protocol-version (read-long inputstream)]
                         (log/infof "Connected to %s (%s:%d), with protocol version %d" channel-name host port protocol-version)
                         (case protocol-version
                           1 (handle-purkinje-protocol-1 inputstream connection-data)
                           (log/errorf "Failure connecting to %s, protocol version %d unkown" channel-name protocol-version))))))
    connection-data))


(defn- distribute-initial-trim-to-avoid-temporal-hotspot [connection-datas]
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

(defn -main [& args]
  (let [channels (-> CHANNEL_CONFIG_FILENAME slurp yaml/parse-string)
        connection-datas (vec
                          (filter identity
                                  (map (fn [channel-info]
                                         (when (:active channel-info)
                                           (connection
                                            (:purkinje_hostname channel-info)
                                            (:purkinje_port channel-info)
                                            (:name channel-info))))
                                       channels)))]
    (distribute-initial-trim-to-avoid-temporal-hotspot connection-datas)
    (log/info "Now wait for clients to connect and see what they want to do!")))
    