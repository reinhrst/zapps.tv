(ns tv.zapps.zippo.phoenix-connection-handler
  (:require [clojure.tools.logging :as log])
  (:require [tv.zapps.zippo.matcher :as matcher])
  (:import [java.io DataInputStream DataOutputStream EOFException IOException]))

(defrecord State
    [scoring
     received-data-seqs-to-match])

(defn create-channels-data-and-ids [connections-data]
  (map
   (fn [connection-data-atom]
     (let [connection-data @connection-data-atom
           latest-fingerprints (take-last (+ matcher/MAXIMUM_LOOKBACK_FRAMES matcher/MAXIMUM_FINGERPRINTS_FOR_MATCH) (:fingerprints connection-data))
           start-timestamp (- (:latest-frame-timestamp connection-data) (count latest-fingerprints))]
       {:id (connection-data :id)
        :data {:fingerprints (int-array latest-fingerprints)
               :timestamps (long-array (range start-timestamp (+ start-timestamp (count latest-fingerprints))))}}))
   connections-data))

(defn- handle-match-fingerprints-v1 [connection-name input-stream output-stream channel-connections-data-atom state]
  (let [channels-data-and-ids (create-channels-data-and-ids @channel-connections-data-atom)
        number-of-fingerprints (.readUnsignedByte input-stream)
        timestamp-first-fingerprint (.readLong input-stream)
        fingerprints (doall (repeatedly number-of-fingerprints #(.readInt input-stream)))
        start-time (System/nanoTime)
        data-seqs-to-match {:fingerprints (take-last matcher/MAXIMUM_FINGERPRINTS_FOR_MATCH
                                                     (concat
                                                      (:fingerprints (:received-data-seqs-to-match state))
                                                      fingerprints))
                            :timestamps (take-last matcher/MAXIMUM_FINGERPRINTS_FOR_MATCH
                                                     (concat
                                                      (:timestamps (:received-data-seqs-to-match state))
                                                      (range timestamp-first-fingerprint (+ timestamp-first-fingerprint number-of-fingerprints))))}
        data-to-match {:fingerprints (int-array (:fingerprints data-seqs-to-match))
                       :timestamps (long-array (:timestamps data-seqs-to-match))}
        scoring (matcher/match data-to-match channels-data-and-ids (:scoring state))]
    (log/debugf "%s: read %d fingerprints (ts %d), scoring %s in %7.3f ms" connection-name number-of-fingerprints timestamp-first-fingerprint (pr-str (when scoring (assoc scoring :certainty (float (:certainty scoring))))) (float (/ (- (System/nanoTime) start-time) 1000000)))
    (if scoring
      (doto output-stream
        (.writeByte 1)
        (.writeLong (:channel-id scoring))
        (.writeByte (int (* 255 (:certainty scoring))))
        (.writeLong (:offset scoring)))
      (.writeByte output-stream 0))
    (merge state {:scoring scoring, :received-data-seqs-to-match data-to-match})))

(defn- handle-get-channel-list-v1 [connection-name input-stream output-stream channel-connections-data-atom state])

(def initial-state (map->State {:scoring nil, :received-data-seqs-to-match []}))

(def protocol-v2-mapping
                                        ; mapping looks like method-number -> method
                                        ; each method will be called with [connection-name input-stream output-stream channel-connections-data-atom state] and is expected to return a new state. Note that no part of the new state may be lazy!
  {1 handle-match-fingerprints-v1
   2 handle-get-channel-list-v1})

(defn- prepare-mapping-functions [mapping]
  (apply merge {}
         (map
          (fn [[key value]] {key (fn [& args] {:post [(= (class %) State)]} (apply value args))})
          mapping)))
            

(defn- handle-protocol-v2 [connection-name input-stream output-stream channel-connections-data-atom]
  (let [mapping (prepare-mapping-functions protocol-v2-mapping)
        device-id (apply str (map (partial format "%02x") (repeatedly 32 #(.readUnsignedByte input-stream))))
        app-version (.readInt input-stream)]
    (log/infof "%s: New client connected %s, protocol v2, app v%d" connection-name device-id app-version)
    (loop [state initial-state]
      (let [method-number (.readInt input-stream)
            handler-function (get mapping method-number)
            new-state (handler-function connection-name input-stream output-stream channel-connections-data-atom state)]
        (recur new-state)))))

(defn- handle-connection [socket channel-connections-data-atom]
  (let [connection-name (format "%s:%d" (.getInetAddress socket) (.getPort socket))]
    (try
      (try
        (let [input-stream (-> socket .getInputStream DataInputStream.)
              output-stream (-> socket .getOutputStream DataOutputStream.)
              protocol-version (.readLong input-stream)]
          (if (= protocol-version 2)
            (handle-protocol-v2 connection-name input-stream output-stream channel-connections-data-atom)
            (log/errorf "%s: Unknown protocol version %d" connection-name protocol-version)))
        (catch Exception e
          (if (= (class e) RuntimeException)
            (throw (.getCause e))
            (throw e))))
      (catch EOFException e (log/errorf "%s: Received EOFException" connection-name))
      (catch IOException e (log/errorf "%s: Received IOException" connection-name))
      (finally
        (.close socket)
        (log/infof "%s: Closing connection" connection-name)))))
      
(defn new-phoenix-connection [socket channel-connections-data-atom]
  (log/info "hi")
  (.start (Thread. #(handle-connection socket channel-connections-data-atom))))

                   
  
  