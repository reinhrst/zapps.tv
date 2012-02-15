(defn cos [n] (Math/cos n))
(def PI (Math/PI))
(defn sqrt [n] (Math/sqrt n))


(defn scaled-dct-II [data]
  "Does one-dimensional DCT-II transform, with a scaling factor. For more info see http://en.wikipedia.org/wiki/Discrete_cosine_transform#DCT-II"
  (let [N (count data)]
    (map
      (fn [k]
        (*
          (apply
            +
            (map
              (fn [n xn]
                (* xn (cos (/ (* PI (+ n 1/2) k) N))))
              (range)
              data))
          (sqrt (/ (if (zero? k) 1 2) N)))) ;scaling factor; for X0 scale by sqrt(1/N), for others sqrt(2/N)
      (range N))))

(defn scaled-dct-III [data]
  "One dimensional inverse DCT (DCT-III). As far as I could see, the function on http://en.wikipedia.org/wiki/Discrete_cosine_transform#DCT-III contains an error, so the implemented function is from http://planetmath.org/encyclopedia/DCTIII.html"
  (let [N (count data), scaled-x0 (/ (first data) (sqrt 2))]
    (map
      (fn [k]
        (*
            (apply
              +
              (map
                (fn [n xn]
                  (* 
                    (if (zero? n) scaled-x0 xn)
                    (cos (/ (* PI n (+ k 1/2)) N))))
                (range)
                data))
          (sqrt (/ 2 N)))) ;scaling factor;
      (range N))))

