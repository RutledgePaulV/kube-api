(ns kube-api.controllers.utils)


(defn deleted? [[type object]]
  (= "DELETED" type))

(defn modified? [[type object]]
  (= "MODIFIED" type))

(defn added? [[type object]]
  (= "ADDED" type))

(defn sync? [[type object]]
  (= "SYNC" type))

(defn resource-version [object]
  (get-in object [:metadata :resourceVersion]))

(defn parse-number [s]
  (if (number? s)
    s
    (try
      (Long/parseLong s)
      (catch NumberFormatException e
        (Double/parseDouble s)))))

(defn version-descending [resources]
  (sort-by (comp parse-number resource-version) #(compare %2 %1) resources))

(defn backoff-seq
  "Returns an infinite seq of exponential back-off timeouts with random jitter."
  [max]
  (->>
    (lazy-cat
      (->> (cons 0 (iterate (partial * 2) 1000))
           (take-while #(< % max)))
      (repeat max))
    (map (fn [x] (+ x (rand-int 1000))))))