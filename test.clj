(defn provider []
  (lazy-seq
    (do
      (Thread/sleep 100)
      (cons (rand) (provider)))))

(def printer (agent nil))
(defn log [& line]
  (send-off printer (fn [x] (apply println line))))

(def promises (atom (repeatedly promise)))

(defn client-connected-thread [x input]
  (log "Client connection " x " is connected with the provider and just received" @(first input))
  (recur x (rest input)))

(.start (Thread. (fn []
                   (loop [stream (provider)]
                     (when-let [item (first stream)]
                       (log "I received " item", will share now")
                       (deliver (first @promises) item)
                       (swap! promises rest))
                       (recur (rest stream))))))


(Thread/sleep 300)
(.start (Thread. #(client-connected-thread 1 @promises)))
(Thread/sleep 100)
(.start (Thread. #(client-connected-thread 2 @promises)))
(Thread/sleep 50)
(.start (Thread. #(client-connected-thread 3 @promises)))

