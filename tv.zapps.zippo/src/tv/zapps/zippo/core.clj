(ns tv.zapps.zippo.core
  (:require [clj-yaml.core :as yaml])
  (:require [clojure.tools.logging :as log])
  (:require [tv.zapps.zippo.purkinje-connection :as purkinje-connection])
  (:require [tv.zapps.zippo.phoenix-connection-handler :as phoenix-connection-handler])
  (:require [tv.zapps.zippo.tools :as tools])
  (:require [nl.claude.tools.net :as net])
  (:require [cheshire.core :as cheshire])
  (:import java.io.IOException)
  (:gen-class))

(def CHANNEL_CONFIG_FILENAME "/etc/zapps/channels.yaml")

(defn- accept-connection [socket connection-datas]
  (phoenix-connection-handler/new-phoenix-connection socket connection-datas))

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


    