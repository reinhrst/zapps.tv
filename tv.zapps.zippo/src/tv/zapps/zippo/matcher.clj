(ns tv.zapps.zippo.matcher
  (:use [clojure.tools.logging :as log]))

(def FINGERPRINTS_BEFORE_FIRST_MATCH 50)
(def MATCH_EACH_X_FINGERPRINTS 20)
(def MAXIMUM_FINGERPRINT_BACKLOG 1000)
(def MAXIMUM_LOOKBACK_FRAMES 200)

(defn- extract-channel-data [connection-datas]
  (map
   #(let [channel-data (deref %1)]
      {:id (channel-data :id)
       :fingerprints (channel-data :fingerprints)
       :latest-frame-timestamp (channel-data :latest-frame-timestamp)})
   connection-datas))

(defn- match-clean-single-channel[backlog channel-fingerprints]
  (if (< (count channel-fingerprints) (+ MAXIMUM_LOOKBACK_FRAMES MAXIMUM_FINGERPRINT_BACKLOG))
    (do
      (log/error "Trying to match before enough channel fingerprints have been received")
      {:score 0 :offset 0})
    (loop [fingerprints channel-fingerprints, offset 0, best-match {:score 0 :offset 0}]
      (if (< offset MAXIMUM_LOOKBACK_FRAMES)
        (let [noise-diff (* 32 (count backlog) 1/2); when there is no correlation, we expect half of all bits to be different
              diff (apply + (map #(Long/bitCount (bit-xor %1 %2))
                                 (reverse backlog)
                                 (reverse fingerprints)))
              score (int (* (- noise-diff diff) (/ (- MAXIMUM_LOOKBACK_FRAMES (/ offset 2)) MAXIMUM_LOOKBACK_FRAMES)))]
;          (log/debug offset (- noise-diff diff) (/ (- MAXIMUM_LOOKBACK_FRAMES (/ offset 2)) MAXIMUM_LOOKBACK_FRAMES))
          (recur (pop fingerprints) (inc offset) (if (> score (:score best-match)) {:score score :offset offset} best-match)))
        best-match))))
          

(defn- match-clean [backlog channel-data]
  "Match without worrying about previous scores"
  (let [match-results (map
                       (fn [{id :id fingerprints :fingerprints latest-frame-timestamp :latest-frame-timestamp}]
                         (let [result (match-clean-single-channel backlog fingerprints)]
                           {:id id
                            :match-end-timestamp (- latest-frame-timestamp (:offset result))
                            :score (:score result)}))
                       channel-data)]
    (sort-by :score > match-results)))
     


(defn- matchme [last-scores frames-since-last-match backlog channel-data]
  "This function produces new scores on the basis of old ones and new information
   - last-scores: sequence of maps (:id, :score, :match-end-timestamp)
   - frames-since-last-match: number of frames received since last time function was called (since the last-scores)
   - backlog: stored fingerprints that need to be matched
   - channel-data: map with {:id, :fingerprints, :latest-frame-timestamp}"
  (if (empty? last-scores)
    (match-clean backlog channel-data)
    (do
      (log/error "Going into uncharted waters: dumping prevous scores, using clean match")
      (match-clean backlog channel-data))))

(defn taker [backlog latest-backlog-timestamp input-sequence]
  "Reads enough from the backlog. The minimum size after reading must be FINGERPRINTS_BEFORE_FIRST_MATCH, and each time at least MATCH_EACH_X_FINGERPRINTS items must be read. If, however, there is a gap in timestamps, the whole backlog is discarded, and a FINGERPRINTS_BEFORE_FIRST_MATCH must be read in the new timestamps
Returns [backlog latest-backlog-timestamp input-sequence]"
  (let [nrtotake (if (zero? (count backlog)) FINGERPRINTS_BEFORE_FIRST_MATCH MATCH_EACH_X_FINGERPRINTS)
        taken-items (take nrtotake input-sequence)
        rest-sequence (drop nrtotake input-sequence)]
    (loop [new-backlog backlog
           expected-timestamp (if (pos? latest-backlog-timestamp) (inc latest-backlog-timestamp) (:timestamp (first taken-items)))
           loop-items taken-items]
      (if (seq loop-items)
        (if (= (:timestamp (first loop-items)) expected-timestamp)
          (recur (conj new-backlog (:fingerprint (first loop-items))) (inc expected-timestamp) (rest loop-items))
          (taker [] 0 (concat loop-items rest-sequence)))
        [(if (> (count new-backlog) MAXIMUM_FINGERPRINT_BACKLOG) (vec (drop (- (count new-backlog) MAXIMUM_FINGERPRINT_BACKLOG) new-backlog)) new-backlog)
         (dec expected-timestamp)
         rest-sequence]))))


(defn matcher [input-sequence connection-datas]
  "This function tries to match a live fingerprinted stream, to a set of other live streams

   - input-sequence is a lazy sequence of {:fingerprint, :timestamp}. This is expected to be live data, so new fingerprints coming available only after a while.
   - connection-datas is an atom, pointing to a collection of atoms, each one pointing to a map with at least the keys
     :id
     :fingerprints
     :latest-frame-timestamp

   It will return a lazy-sequence (that won't end until the input-sequence runs out), each element a collection containing
     :id
     :score
     :timeshift
   This will mean that a certain channel (:id) has a certain matching score, with a certain timeshift. The collection will be ordered by score, meaning that the first one being the best match. The lazy sequence may contain empty collections. An empty collection means that no match to any channel could be made."
  (letfn [(myloop [input-sequence backlog latest-backlog-timestamp last-scores]
            (lazy-seq
              (let [[new-backlog new-latest-backlog-timestamp new-input-sequence] (taker backlog latest-backlog-timestamp input-sequence)
                    fingerprints (extract-channel-data @connection-datas)
                    scores (matchme last-scores 0 new-backlog fingerprints)]
                (cons
                 (map #(-> % (assoc :timeshift (- new-latest-backlog-timestamp (:match-end-timestamp %))) (dissoc :match-end-timestamp)) scores)
                 (myloop new-input-sequence new-backlog new-latest-backlog-timestamp scores)))))]
    (myloop input-sequence[] 0 [])))
