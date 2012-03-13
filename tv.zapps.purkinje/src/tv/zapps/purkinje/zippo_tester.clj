(ns tv.zapps.purkinje.zippo-tester
  (:require [tv.zapps.purkinje.core :as core])
  (:require [nl.claude.tools.conversion :as conversion])
  (:require [clojure.tools.logging :as log])
  (:import java.net.Socket java.util.Date))




(defn -main [& args]
  "Alternative entry-point into the program. This will open a connection to a zippo server, and send test data"
  (let [zippo-server (nth args 0)
        zippo-port (Integer/parseInt (nth args 1))
        url-string (nth args 2)]
    (log/infof "Now testing the Zippo server on %s:%d, with data from %s" zippo-server zippo-port url-string)

    (let [sequence (core/fingerprint-sequence-from-url-string url-string)
          _ (take 2 sequence) ; syncing sequence
          start-timestamp (core/calculate-timestamp (Date.))
          socket (Socket. zippo-server zippo-port)
          inputstream (.getInputStream socket)
          outputstream (.getOutputStream socket)
          read-bytes (fn [nr] (doall (repeatedly nr #(.read inputstream))))]
      (.start (Thread. (fn []
                         (loop []
                           (let [reply-nr (conversion/byte-array-be-to-number (read-bytes 8))]
                             (case reply-nr
                               2 (let [response-length (-> (read-bytes 4) conversion/byte-array-be-to-number)
                                       response (String. (byte-array (map  #(.byteValue %)
                                                                           (read-bytes response-length))) "UTF-8")]
                                   (log/infof "Received channel info: %s" response))
                               1 (let [nrresults (first (read-bytes 1))]
                                   (log/infof "Receiving matching score")
                                   (doall (repeatedly nrresults
                                                      #(log/infof "   Channel %d score %d delay %d"
                                                                  (conversion/byte-array-be-to-number (read-bytes 8))
                                                                  (first (read-bytes 1))
                                                                  (conversion/byte-array-be-to-number (read-bytes 8))))))
                               (log/errorf "Unknonwn reply number %s" reply-nr)))
                           (recur)))))
      (log/info "Connected, sending headers")
      (.write outputstream (conversion/long-to-byte-array-be 1)) ;protocol version
      (.write outputstream (byte-array (repeat 32 (.byteValue 0x0F)))) ;handset id
      (.write outputstream (conversion/int-to-byte-array-be 1)) ;app version
      (log/info "Sending request for channel info")
      (.write outputstream (conversion/long-to-byte-array-be 2))
      (Thread/sleep 4000)
      (log/info "Sending fingerprint, should have a delay of about a second")
      (.write outputstream (conversion/long-to-byte-array-be 1))
      (.write outputstream (conversion/long-to-byte-array-be start-timestamp))
      (doseq [fingerprint (take 256 (drop 2 sequence))]
        (.write outputstream (conversion/int-to-byte-array-be fingerprint)))
      (log/info "Done sending my fingerprint")
      )))
              

