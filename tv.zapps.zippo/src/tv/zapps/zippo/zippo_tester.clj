(ns tv.zapps.zippo.zippo-tester
  (:require [clojure.tools.logging :as log])
  (:import java.net.Socket java.io.DataOutputStream java.io.DataInputStream))



(def BYTES_TO_WRITE_PER_CALL 20)

(defn -main [& args]
  (if (not= (count args) 4)
    (log/errorf "Needs 4 arguments: zippo-server zippo-port purkinje-server purkinje-port")
    (let [zippo-server (nth args 0)
          zippo-port (Integer/parseInt (nth args 1))
          purkinje-server (nth args 2)
          purkinje-port (Integer/parseInt (nth args 3))]
      (log/infof "Now testing the Zippo server on %s:%d, with data from %s %d" zippo-server zippo-port purkinje-server purkinje-port)

      (let [zippo-socket (Socket. zippo-server zippo-port)
            zippo-input-stream (-> zippo-socket .getInputStream DataInputStream.)
            zippo-output-stream (-> zippo-socket .getOutputStream DataOutputStream.)
            purkinje-socket (Socket. purkinje-server purkinje-port)
            purkinje-input-stream (-> purkinje-socket .getInputStream DataInputStream.)
            purkinje-output-stream (-> purkinje-socket .getOutputStream DataOutputStream.)
            purkinje-protocol-version (.readLong purkinje-input-stream)
            timestamp (.readLong purkinje-input-stream)]
        (Thread/sleep 1000)
        (doto zippo-output-stream
          (.writeLong  2) ; protocol nr
          (.write (byte-array (repeat 32 (.byteValue 0x0F))) 0 32) ;handset id
          (.writeInt 1)) ; app version
        (dotimes [runnr 1]
               (doto zippo-output-stream
                 (.writeInt 1) ; method nr
                 (.writeByte BYTES_TO_WRITE_PER_CALL) ; number of fingerprints
                 (.writeLong (+ timestamp (* runnr BYTES_TO_WRITE_PER_CALL))))
               (dotimes [_ BYTES_TO_WRITE_PER_CALL]
                 (.writeInt zippo-output-stream (.readInt purkinje-input-stream)))
               (let [number-of-scorings (.readUnsignedByte zippo-input-stream)]
                 (if (zero? number-of-scorings)
                   (log/info "Scoring received: no match")
                   (dotimes [n number-of-scorings]
                     (log/infof "%d: Scoring channel %d, probability %d, offset %d"
                                n
                                (.readLong zippo-input-stream)
                                (.readUnsignedByte zippo-input-stream)
                                (.readLong zippo-input-stream))))))))))

;(-main "localhost" "7000" "eeyore" "9001")  