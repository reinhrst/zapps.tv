(ns tv.zapps.zippo.core
  (:require [clj-yaml.core :as yaml])
  (:require [clojure.tools.logging :as log])
  (:require [tv.zapps.zippo.purkinje-connection :as purkinje-connection])
  (:require [tv.zapps.zippo.matcher :as matcher])
  (:require [tv.zapps.zippo.tools :as tools])
  (:require [nl.claude.tools.net :as net])
  (:require [cheshire.core :as cheshire])
  (:import java.io.IOException)
  (:gen-class))

(def CHANNEL_CONFIG_FILENAME "/etc/zapps/channels.yaml")
(def FINGERPRINTS_IN_MEGAPRINT 256)


(defn- handle-send-channel-list-v1 [outputstream] ;todo: what exactly do we want to send? Right now we're just sending the config file....
  (let [channels (-> CHANNEL_CONFIG_FILENAME slurp yaml/parse-string)
        response (-> channels cheshire/generate-string (.getBytes "UTF-8"))]
    (tools/write-long outputstream 2)
    (tools/write-int outputstream (count response))
    (.write outputstream response)))

(defn- handle-match-stream-v1 [inputstream continuation-after-megaprint-done]
  (let [start-timestamp (tools/read-long inputstream)
        run-until-timestamp (+ start-timestamp FINGERPRINTS_IN_MEGAPRINT -1)]
    (letfn [(myloop [timestamp]
              (lazy-seq
                (cons
                 {:fingerprint (tools/read-int inputstream) :timestamp timestamp}
                 (if (= timestamp run-until-timestamp)
                   (continuation-after-megaprint-done)
                   (myloop (inc timestamp))))))]
      (myloop start-timestamp))))

(defn- handle-zippo-protocol-v1 [socket inputstream connection-datas]
  (let [handset-id (tools/read-handset-id inputstream)
        app-version (tools/read-int inputstream)
        outputstream (.getOutputStream socket)]
    (log/infof "%s: Connection from %s, with protocol version %d, app-version %d" handset-id (.getInetAddress socket) 1 app-version)
    (letfn [(read-protocol-provide-fingerprint-sequence []
              (lazy-seq
                (let [method-nr (tools/read-long inputstream)]
                  (case method-nr
                    1 (do
                        (log/debugf "%s: Matching stream v1" handset-id)
                        (handle-match-stream-v1 inputstream #(read-protocol-provide-fingerprint-sequence)))
                    2 (do
                        (log/debugf "%s: Sending channel list v1" handset-id)
                        (handle-send-channel-list-v1 outputstream)
                        (read-protocol-provide-fingerprint-sequence))
                    (do
                      (log/errorf "%s: Unkown method number 0x%x, closing connection" handset-id method-nr)
                      (.close socket))))))]
      (try
        (doseq [score-list (matcher/matcher (read-protocol-provide-fingerprint-sequence) connection-datas)]
          (assert (< (count score-list) 0xFF) "We can't have more than 127 channels for score")
          (log/debugf "%s: Sending score: %s" handset-id (seq score-list))
          (tools/write-long outputstream 1); method-number
          (tools/write-byte outputstream (count score-list))
          (doseq [score score-list]
            (tools/write-long outputstream (:id score))
            (tools/write-byte outputstream (int (* (:score score) 0xFF)))
            (tools/write-long outputstream (:timeshift score))))
        (catch IOException e)
        (finally
          (.close socket)
          (log/infof "%s: connection closed" handset-id))))))
                                              
    

(defn- accept-connection [socket connection-datas]
  (.start (Thread. (fn []
                     (let [inputstream (.getInputStream socket)
                           protocol-version (tools/read-long inputstream)]
                       (log/debugf "<unknown>: Connected from %s, with protocol version %d" (.getInetAddress socket) protocol-version)
                       (case protocol-version
                         1 (handle-zippo-protocol-v1 socket inputstream connection-datas)
                         (do
                           (log/errorf "<unknown>: Failure connecting, protocol version %d unkown" protocol-version)
                           (.close socket))))))))
                       

(defn -main [& args]
  (if (= (count args) 1)
    (let [zippo-port (Integer/parseInt (first args))
          channels (-> CHANNEL_CONFIG_FILENAME slurp yaml/parse-string)
          connection-datas (atom
                            (vec
                             (keep-indexed (fn [id channel-info]
                                             (when (:active channel-info)
                                               (purkinje-connection/connection
                                                (:purkinje_hostname channel-info)
                                                (:purkinje_port channel-info)
                                                (:name channel-info)
                                                id)))
                                           channels)))]
      (purkinje-connection/distribute-initial-trim-to-avoid-temporal-hotspot @connection-datas)
      (log/info "Now wait for clients to connect and see what they want to do!")
      (doseq [socket (net/new-connections-sequence zippo-port)]
        (accept-connection socket connection-datas)))
    (log/fatalf "Please start with a port number as first and only argument")))


    