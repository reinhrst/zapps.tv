(ns tv.zapps.zippo.matcher
  (:use [clojure.tools.logging :as log])
  (:require [tv.zapps.zippo.uncorrelated-matching-data]))

(def MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION 20)
(def MAXIMUM_LOOKBACK_FRAMES 3000)
(def MAXIMUM_FINGERPRINTS_FOR_MATCH 128)
(def POSITIVE_CORRELATION_CUTOFF 0.98)
(def SURROUNDINGS_FOR_ON_LOCATION_MATCH 2)
(def DEGRADATION_PER_TIMESTEP (Math/pow 1.02 1/80)) ; about 80 timestamps in a second, this means degradation of this factor per second

(def INT_ARRAY_CLASS (Class/forName "[I"))
(def LONG_ARRAY_CLASS (Class/forName "[J"))


(defrecord Scoring
    [channel-id
     size
     diff
     offset ; the ammount that the matched timestamp is ahead of the source-timestamp
     certainty
     latest-timestamp])

(defrecord Differences
    [diff
     offset])

(defn- valid-data? [data]
  (and
   (instance? INT_ARRAY_CLASS (:fingerprints data))
   (instance? LONG_ARRAY_CLASS (:timestamps data))
   (= (alength (:fingerprints data)) (alength (:timestamps data)))))

(defn- valid-channel-data? [data]
  (and
   (valid-data? data)
   (let [timestamps (:timestamps data)]
     (for [i (range (dec (alength timestamps)))]
       (<= 0 (- (aget timestamps i) (aget timestamps (inc i))) 2))))) ; should normally be 1, but may be 0 or 2 every now and then to correct for clock skew

(defn- valid-to-match-data? [data]
  (and
   (valid-data? data)
   (let [timestamps (:timestamps data)]
     (for [i (range (dec (alength timestamps)))]
       (< (aget timestamps i) (aget timestamps (inc i)))))))

(defn- vector-of-type?
  ([data type] (vector-of-type? data type [] []))
  ([data type true-keys] (vector-of-type? data type true-keys []))
  ([data type true-keys false-keys]
     (and
      (vector? data)
      (every? #(= (class %) type) data)
      (every? #(every? (partial get %) true-keys) data)
      (every? #(not-any? (partial get %) false-keys) data))))
      
(defn- min-key-extra [key & args]
  (if (zero? (count args))
    nil
    (apply min-key key args)))

(defn datalength [data]
  (alength (:fingerprints data)))

(defn- calculate-differences [received-data-to-match, channel-data]
  {:pre [(valid-to-match-data? received-data-to-match)
         (valid-channel-data? channel-data)]
   :post [(vector-of-type? % Differences [:diff :offset])]}
  "(do (doall (map  println (calculate-differences
       [{:timestamp 1, :fingerprint 24} {:timestamp 5, :fingerprint 28}]
       (vec (map #(identity {:timestamp %, :fingerprint %}) (range 30)))))) nil)"

  (let [nr-timestamps-to-match (datalength received-data-to-match)
        first-timestamp-to-match (aget (:timestamps received-data-to-match) 0)
        to-match-timestamp-diff (amap  (:timestamps received-data-to-match) i ret (- (aget ^longs ret i) first-timestamp-to-match))
        max-timestamp-difference (aget to-match-timestamp-diff (dec (alength to-match-timestamp-diff)))
        fingerprints-to-match (:fingerprints received-data-to-match)
        fingerprints-in-channel (:fingerprints channel-data)]
    
    (loop [stream-offset 0, differences []]
      (if (> (alength ^ints fingerprints-in-channel) (+ stream-offset max-timestamp-difference))
        (recur
         (inc stream-offset)
         (conj
          differences
          (map->Differences
           {:offset (- first-timestamp-to-match (aget ^longs (:timestamps channel-data) stream-offset)) 
            :diff (loop [i 0 total-diff 0]
                    (if (< i nr-timestamps-to-match)
                      (recur
                       (unchecked-inc i)
                       (+
                        total-diff
                        (Integer/bitCount
                         (bit-xor
                          (aget ^ints fingerprints-to-match i)
                          (aget ^ints fingerprints-in-channel (+ stream-offset (aget ^longs to-match-timestamp-diff i)))
                          ))))
                      total-diff))})))
        differences))))

(defn- get-certainty [size surroundings diff]
  (nth (tv.zapps.zippo.uncorrelated-matching-data/get-uncorrelated-results size (if (> surroundings 1000) 1000 surroundings)) diff))

(defn- calculate-certainty [old-certainty new-certainty old-last-timestamp new-last-timestamp]
  "calculates the certainty, basically by multiplying the uncertainties and applying some time degradation -- I'm sure this can be done better!"
  (let [timestamp-factor (Math/pow DEGRADATION_PER_TIMESTEP (- new-last-timestamp old-last-timestamp))
        timestamp-adjusted-old-certainty (/ old-certainty timestamp-factor)
        old-uncertainty (- 2 (* 2 timestamp-adjusted-old-certainty))
        new-uncertainty (- 2 (* 2 new-certainty))
        calculated-uncertainty (* old-uncertainty new-uncertainty)]
    (/ (- 2 calculated-uncertainty) 2)))

(defn asubintarray [source-array start length]
  (let [atemp (int-array length)]
    (System/arraycopy source-array start atemp 0 length)
    atemp))

(defn asublongarray [source-array start length]
  (let [atemp (long-array length)]
    (System/arraycopy source-array start atemp 0 length)
    atemp))

(defn subdata [data start length]
  {:fingerprints (asubintarray (:fingerprints data) start length)
   :timestamps (asublongarray (:timestamps data) start length)})

(defn match-on-location-single-channel [received-data-to-match channel-data channel-id scoring]
  {:pre [(valid-to-match-data? received-data-to-match)
         (valid-channel-data? channel-data)
         (vector-of-type? (vector scoring) Scoring [:size :diff :offset :certainty :latest-timestamp :channel-id])]
   :post [(or (nil? %) (vector-of-type? (vector %) Scoring [:size :diff :offset :certainty :channel-id]))]}
  (let [channel-to-matching-timestamp-correction (:offset scoring)
        matching-to-channel-timestamp-correction (- channel-to-matching-timestamp-correction)
        channel-start-timestamp (aget (:timestamps channel-data) 0)
        channel-end-timestamp (aget (:timestamps channel-data) (dec (datalength channel-data)))
        lowest-possible-timestamp-to-start-matching (max
                                                     (+ channel-start-timestamp channel-to-matching-timestamp-correction)
                                                     (inc (:latest-timestamp scoring)))
        highest-possible-timestamp-to-match (+ channel-end-timestamp channel-to-matching-timestamp-correction)
        timestamps-received-data-to-match (:timestamps received-data-to-match)
        data-to-match-start-index (loop [i 0]
                                    (if (< (aget timestamps-received-data-to-match i) lowest-possible-timestamp-to-start-matching)
                                      (recur (unchecked-inc i))
                                      i)) ;will actually crash when lowest-possible-timestamp-to-start-matching > max-timestamp in data-to-match; let's just hope that never happens (and else put an index-out-of-bounds-exception-ect around this all
        data-to-match-length (loop [i (dec (alength timestamps-received-data-to-match))]
                               (if (> (aget timestamps-received-data-to-match i) highest-possible-timestamp-to-match)
                                 (recur (unchecked-dec i))
                                 (- i data-to-match-start-index)))
        data-to-match (subdata received-data-to-match data-to-match-start-index data-to-match-length)]
    (if (empty? data-to-match)          ;actually I don't think this will be reached ever.. More likely an index-out-of-bounds-exception will have occured by now....
      nil                               ;one theory might be that we should return the same scoring since we couldn't match anything, but most likely this is because the data to match rolled out of the window. Better start over again
      (let [to-match-start-timestamp (aget (:timestamps data-to-match) 0)
            to-match-end-timestamp (aget (:timestamps data-to-match) (dec (datalength data-to-match)))
            channel-data-timestamps (:timestamps channel-data)
            first-channel-timestamp-to-consider (+ to-match-start-timestamp matching-to-channel-timestamp-correction -1)
            channel-data-to-consider-start-index (loop [i 0]
                                                   (if (< (aget channel-data-timestamps i) first-channel-timestamp-to-consider)
                                                     (recur (unchecked-inc i))
                                                     i))
            channel-data-to-consider-length (+ (- to-match-end-timestamp to-match-start-timestamp)
                                              2 ; we take the window one wider on each side
                                              1) ; off-by-one: if start-timestamp == end-timestamp than we still need one sample, so always + 1

            channel-data-to-consider (subdata channel-data channel-data-to-consider-start-index channel-data-to-consider-length)
            differences (calculate-differences data-to-match channel-data-to-consider)
            best-match (apply min-key-extra :diff differences)
            certainty (calculate-certainty
                       (:certainty scoring) 
                       (get-certainty (datalength data-to-match) SURROUNDINGS_FOR_ON_LOCATION_MATCH (:diff best-match))
                       (:latest-timestamp scoring)
                       to-match-end-timestamp)]
        (when (> certainty POSITIVE_CORRELATION_CUTOFF)
          (map->Scoring
           {:size (datalength data-to-match)
            :diff  (:diff best-match)
            :offset (:offset best-match)
            :certainty (float certainty)
            :channel-id channel-id
            :latest-timestamp to-match-end-timestamp}))))))

(defn match-no-history-single-channel [data-to-match, channel-data, channel-id]
  {:pre [(valid-to-match-data? data-to-match)
         (<= (datalength data-to-match) MAXIMUM_FINGERPRINTS_FOR_MATCH)
         (valid-channel-data? channel-data)]
   :post [(or (nil? %) (vector-of-type? (vector %) Scoring [:size :diff :offset :certainty :channel-id :latest-timestamp]))]}
  (let [differences (calculate-differences data-to-match channel-data)
        best-match (apply min-key-extra :diff differences)
        certainty (get-certainty (datalength data-to-match) MAXIMUM_LOOKBACK_FRAMES (:diff best-match))]
    (when (> certainty POSITIVE_CORRELATION_CUTOFF)
      (map->Scoring
       {:size (datalength data-to-match)
        :diff (:diff best-match)
        :offset (:offset best-match)
        :certainty (float certainty)
        :channel-id channel-id
        :latest-timestamp (aget (:timestamps data-to-match) (dec (datalength data-to-match)))}))))

(defmacro lazy-or [& block]
  (case (count block)
     0 nil
     1 (first block)
     `(if-let [result# ~(first block)]
         result#
         (lazy-or ~@(rest block)))))

(defmacro pmap-truth [& block]
  `(filter identity (pmap ~@block)))

(defn match [data-to-match, channels-data-and-ids, scoring]
  {:pre [(<= (datalength data-to-match) MAXIMUM_FINGERPRINTS_FOR_MATCH)]
   :post [(or (nil? %) (= (class %) Scoring))]}
  (if scoring
    (let [channel-id (:channel-id scoring)
          match-as-new-data #(match data-to-match channels-data-and-ids nil)]
      (if-let [channel-data (:data (first (filter #(= (:id %) channel-id) channels-data-and-ids)))]
        (if-let [new-scoring (match-on-location-single-channel data-to-match channel-data channel-id scoring)]
          new-scoring
          (do
            (log/debug "matching with previous score failed")
            (match-as-new-data)))
        (do
          (log/errorf "Can't find back previously matched channel %d, ignoring score" channel-id)
          (match-as-new-data))))
    (do (log/infof "matching %d samples " (datalength data-to-match))
        (when (>= (datalength data-to-match) MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION)
          (lazy-or
           (apply
            min-key-extra
            :diff
            (pmap-truth
             #(match-no-history-single-channel data-to-match (:data %) (:id %))
             channels-data-and-ids))
           (when (>= (datalength data-to-match) (+ 10 MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION)) ; map just the newest data
             (apply
              min-key-extra
              :diff
              (pmap-truth
               #(match-no-history-single-channel (subdata data-to-match (- (datalength data-to-match) MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION) MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION) (:data %) (:id %))
               channels-data-and-ids)))
           (when (>= (datalength data-to-match) (+ 10 MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION)) ; map just the oldest data
             (apply
              min-key-extra
              :diff
              (pmap-truth
               #(match-no-history-single-channel (subdata data-to-match 0 MINIMUM_FINGERPRINTS_FOR_MATCH_SUGGESTION) (:data %) (:id %))
               channels-data-and-ids))))))))
      
    
    
