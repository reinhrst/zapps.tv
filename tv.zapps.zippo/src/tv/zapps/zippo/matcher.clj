(ns tv.zapps.zippo.matcher
  (:use [clojure.tools.logging :as log])
  (:require [tv.zapps.zippo.uncorrelated-matching-data]))

(def MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION 20)
(def MAXIMUM_LOOKBACK_FRAMES 1000)
(def MAXIMUM_FINGERPRINTS_IN_AGENT 1000)
(def MAXIMUM_FINGERPRINTS_FOR_MATCH 128)
(def POSITIVE_CORRELATION_CUTOFF 0.9)
(def SURROUNDINGS_FOR_ON_LOCATION_MATCH 2)

(defrecord Scoring
    [channel-id
     size
     diff
     offset ; the ammount that the matched timestamp is ahead of the source-timestamp
     certainty])

(defrecord Differences
    [diff
     offset])

(defn- valid-to-match-data? [data]
  (and
   (vector? data)
   (every? identity
           (map
            #(and
              (:fingerprint %1)
              (:timestamp %1)
              (:fingerprint %2)
              (:timestamp %2)
              (> (:timestamp %2) (:timestamp %1)))
            data
            (rest data)))))

(defn- valid-channel-data? [data]
  (and
   (vector? data)
   (every? identity
           (map
            #(and
              (:fingerprint %1)
              (:timestamp %1)
              (:fingerprint %2)
              (:timestamp %2)
              (<= 0 (- (:timestamp %2) (:timestamp %1)) 2)) ; should normally be 1, but may be 0 or 2 every now and then to correct for clock skew
            data
            (rest data)))))

(defn- vector-of-type?
  ([data type] (vector-of-type? data type [] []))
  ([data type true-keys] (vector-of-type? data type true-keys []))
  ([data type true-keys false-keys]
     (and
      (vector? data)
      (every? #(= (class %) type) data)
      (every? #(every? (partial get %) true-keys) data)
      (every? #(not-any? (partial get %) false-keys) data))))
      
  

(defn- calculate-differences [received-data-to-match, channel-data]
  {:pre [(valid-to-match-data? received-data-to-match)
         (valid-channel-data? channel-data)]
   :post [(vector-of-type? % Differences [:diff :offset])]}
  "(do (doall (map  println (calculate-differences
       [{:timestamp 1, :fingerprint 24} {:timestamp 5, :fingerprint 28}]
       (vec (map #(identity {:timestamp %, :fingerprint %}) (range 30)))))) nil)"

   (let [to-match-timestamp-diff (map #(- (:timestamp %) (:timestamp (first received-data-to-match))) received-data-to-match)
        max-timestamp-difference (last to-match-timestamp-diff)]
    (loop [stream-offset 0, differences []]
      (if (> (count channel-data) (+ stream-offset max-timestamp-difference))
        (recur
         (inc stream-offset)
         (conj
          differences
          (map->Differences
           {:offset (- (:timestamp (first received-data-to-match)) (:timestamp (nth channel-data stream-offset))) 
            :diff (apply +
                         (map (fn [{fingerprint-to-match :fingerprint} timestamp-diff-with-first-sample]
                                (Long/bitCount (bit-xor
                                                fingerprint-to-match
                                                (:fingerprint (nth channel-data (+ stream-offset timestamp-diff-with-first-sample))))))
                              received-data-to-match
                              to-match-timestamp-diff))})))
        differences))))

(defn- get-certainty [size surroundings diff]
  (nth (tv.zapps.zippo.uncorrelated-matching-data/get-uncorrelated-results size surroundings) diff))

(defn- find-best-element [data comperator]
  (apply (fn helper
           ([] nil)
           ([p1] p1)
           ([p1 p2] (if (pos? (comperator p1 p2)) p1 p2))
           ([p1 p2 & args] (apply (partial helper (helper p1 p2)) args)))
         data))

(defn match-on-location-single-channel [received-data-to-match channel-data channel-id scoring]
  {:pre [(valid-to-match-data? received-data-to-match)
         (valid-channel-data? channel-data)
         (vector-of-type? (vector scoring) Scoring [:size :diff :offset])]
   :post [(vector-of-type? (vector %) Scoring [:size :diff :offset :certainty :channel-id])]}
  (let [channel-to-matching-timestamp-correction (:offset scoring)
        matching-to-channel-timestamp-correction (- channel-to-matching-timestamp-correction)
        channel-start-timestamp (:timestamp (first channel-data))
        channel-end-timestamp (:timestamp (last channel-data))
        data-to-match (vec
                       (filter
                        #(<=
                          (+ channel-start-timestamp channel-to-matching-timestamp-correction)
                          (:timestamp %)
                          (+ channel-end-timestamp channel-to-matching-timestamp-correction)) received-data-to-match))
        to-match-start-timestamp (:timestamp (first data-to-match))
        to-match-end-timestamp (:timestamp (last data-to-match))
        channel-data-to-consider (vec
                                  (take (+ (- to-match-end-timestamp to-match-start-timestamp) 2) ; we take the window one wider on each side
                                        (filter
                                         #(<= (+ to-match-start-timestamp matching-to-channel-timestamp-correction -1) (:timestamp %))
                                         channel-data)))
        differences (calculate-differences data-to-match channel-data-to-consider)
        best-match (find-best-element differences #(compare (:diff %1) (:diff %2)))
        certainty (get-certainty (count data-to-match) SURROUNDINGS_FOR_ON_LOCATION_MATCH (:diff best-match))]
    (when (> certainty POSITIVE_CORRELATION_CUTOFF)
      (map->Scoring
       {:size (count data-to-match)
        :diff (:diff best-match)
        :offset (:offset best-match)
        :certainty certainty
        :channel-id channel-id}))))

(defn match-no-history-single-channel [data-to-match, channel-data, channel-id]
  {:pre [(valid-to-match-data? data-to-match)
         (<= (count data-to-match) MAXIMUM_FINGERPRINTS_FOR_MATCH)
         (valid-channel-data? channel-data)]
   :post [(or (nil? %) (vector-of-type? (vector %) Scoring [:size :diff :offset :certainty :channel-id]))]}
  (let [channel-data-to-consider (subvec channel-data (max 0 (- (count channel-data) (count data-to-match) MAXIMUM_LOOKBACK_FRAMES)))
        differences (calculate-differences data-to-match channel-data-to-consider)
        best-match (find-best-element differences #(compare (:diff %2) (:diff %1)))
        certainty (get-certainty (count data-to-match) MAXIMUM_LOOKBACK_FRAMES (:diff best-match))]
    (when (> certainty POSITIVE_CORRELATION_CUTOFF)
      (map->Scoring
       {:size (count data-to-match)
        :diff (:diff best-match)
        :offset (:offset best-match)
        :certainty certainty
        :channel-id channel-id}))))

(defmacro lazy-or [& block]
  (case (count block)
     0 nil
     1 (first block)
     `(if-let [result# ~(first block)]
         result#
         (lazy-or ~@(rest block)))))

(defmacro map-truth [& block]
  `(filter identity (map ~@block)))

(defn match [data-to-match, channels-data-and-ids, scoring]
  {:pre [(<= (count data-to-match) MAXIMUM_FINGERPRINTS_FOR_MATCH)]
   :post [(or (nil? %) (= (class %) Scoring))]}
  (if scoring
    (let [channel-id (:channel-id scoring)
          match-as-new-data #(match data-to-match channels-data-and-ids nil)]
      (if-let [channel-data (:data (some #(= (:id %) channel-id) channels-data-and-ids))]
        (if-let [new-scoring (match-on-location-single-channel data-to-match channel-data channel-id scoring)]
          new-scoring
          (do
            (log/debug "matching with previous score failed")
            (match-as-new-data)))
        (do
          (log/errorf "Can't find back previously matched channel %d, ignoring score" channel-id)
          (match-as-new-data))))
    (when (>= (count data-to-match) MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION)
      (lazy-or
       (find-best-element
        (map-truth
         #(match-no-history-single-channel data-to-match (:data %) (:id %))
         channels-data-and-ids)
        #(compare (:diff %2) (:diff %1)))
       (when (>= (count data-to-match) (+ 10 MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION)) ; map just the newest data
         (find-best-element
          (map-truth
           #(match-no-history-single-channel (subvec data-to-match (- (count data-to-match) MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION)) (:data %) (:id %))
           channels-data-and-ids)
          #(compare (:diff %2) (:diff %1))))
       (when (>= (count data-to-match) (+ 10 MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION)) ; map just the oldest data
         (find-best-element
          (map-truth
           #(match-no-history-single-channel (subvec data-to-match 0 MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION) (:data %) (:id %))
           channels-data-and-ids)
          #(compare (:diff %2) (:diff %1))))))))
      
    
    
