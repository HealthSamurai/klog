(ns klog.to-json
  (:import
    (java.io
      Writer)
    (java.text
      SimpleDateFormat)
    (java.util
      Date
      TimeZone)))


(def fmt
  (let [tz (TimeZone/getTimeZone "UTC")
        df (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss'Z'")]
    (.setTimeZone df tz)
    df))


(set! *warn-on-reflection* true)


(defn format-date
  [^Date x]
  (str (.format ^SimpleDateFormat fmt x)))


(defn write-escaped-string
  [^Writer w ^CharSequence s]
  (.write w "\"")
  (loop [index (int 0)]
    (when-not (= (.length s) index)
      (let [ch (.charAt s index)]
        (cond
          (or (= ch \\)
              (= ch \"))
          (do
            (.append w \\)
            (.append w ch))

          (= ch \backspace)
          (.write w "\\b")

          (= ch \tab)
          (.write w "\\t")

          (= ch \newline)
          (.write w "\\n")

          (= ch \formfeed)
          (.write w "\\f")

          (= ch \return)
          (.write w "\\r")

          :else
          (.append w ch))
        (recur (inc index)))))
  (.write w "\""))


(defn generate
  [^Writer w x]
  (cond
    (string? x)  (write-escaped-string w x)
    (keyword? x) (write-escaped-string w (if-let [n (namespace x)]
                                           (str n "/" (name x))
                                           (name x)))
    (number? x)  (.write w (.toString ^Number x))
    (map? x)
    (do
      (.write w "{")
      (loop [[[k _] & pairs] x]
        (generate w k)
        (.write w ": ")
        (generate w (get x k))
        (when-not (empty? pairs)
          (.write w ", ")
          (recur pairs)))
      (.write w "}"))

    (coll? x)
    (do
      (.write w "[")
      (loop [[v & vs] x]
        (generate w v)
        (when-not (empty? vs)
          (.write w ", ")
          (recur vs)))
      (.write w "]"))

    (nil? x)
    (.write w "null")

    (boolean? x)
    (.write w (str x))

    (inst? x)
    (.write w ^String (format-date ^Date x))

    :else
    (write-escaped-string w (str x))))
