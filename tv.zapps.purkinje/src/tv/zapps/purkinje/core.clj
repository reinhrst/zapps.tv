(ns tv.zapps.purkinje.core
  (:require tv.zapps.purkinje.fingerprinter tv.zapps.purkinje.provider tv.zapps.purkinje.constants)
  (:import java.util.Date))

(def PROTOCOL_VERSION 1)

(defn- calculate-timestamp [date]
  "Takes a date object and returns the timestamp such as required in the protocol specification"
  (-> date .toTime (* tv.zapps.purkinje.constants/SAMPLE_FREQUENCY) (/ tv.zapps.purkinje.constants/FINGERPRINT_INTERVAL) int)) ;probably we would want a better resolution at one point but for now this is fine

(defn fingerprint-sequence-from-url-string [url-string]
  "Takes a url-string, returns a sequence of fingerprints"
  (tv.zapps.purkinje.fingerprinter/fingerprint-sequence
   (tv.zapps.purkinje.provider/real-timed-sequence-from-url url-string)))