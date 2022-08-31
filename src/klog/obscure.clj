(ns klog.obscure
  (:require
    [clojure.string :as str]
    [taoensso.nippy :as nippy])
  (:import
    (java.net
      InetSocketAddress)
    (java.nio
      ByteBuffer)
    (java.nio.channels
      SocketChannel)))


(set! *warn-on-reflection* true)


(defn client
  [^String host ^Integer port]
  (println "Connecting to obscure! " host ":" port)
  (let [cl-addr (InetSocketAddress. host port)
        cl (SocketChannel/open cl-addr)]
    (println "Connected to obscure!")
    cl))


(defn client-send
  [^SocketChannel cl data]
  (let [^ByteBuffer buf (ByteBuffer/wrap (nippy/freeze data))]
    (.write cl buf)))


(def retry (atom nil))


(defn reconnect
  [cla]
  (try
    (let [state @cla
          cl (client (:host state) (:port state))]
      (swap! cla assoc :cl cl))
    (catch Exception e
      (println "Could not connect to obscure " (dissoc @cla :cache) "\n" (.getMessage e))
      (swap! cla assoc :retry (future (Thread/sleep 10000) (#'reconnect cla))))))


(defn connection
  [url]
  (let [[host port] (str/split (str/replace url #"tcp://" "") #":")
        state (atom {:host host
                     :port (Integer/parseInt port)
                     :cache []
                     :cnt 0})]
    (swap! state assoc :retry (future (#'reconnect state)))
    state))


(defn send-data
  [cla data]
  (let [{cl :cl cache :cache cnt :cnt} @cla]
    (if cl
      (if (.isOpen ^SocketChannel cl)
        (try
          (client-send ^SocketChannel cl data)
          (catch Exception _
            (.close ^SocketChannel cl)
            (swap! cla  assoc
                   :cl nil
                   :cache [data]
                   :cnt 1
                   :retry (future (#'reconnect cla)))
            (reconnect cla)))
        (swap! cla assoc :cl nil :cache [data] :cnt 1))
      (if (< cnt 1000)
        (swap! cla (fn [stat]
                     (-> stat
                         (update :cnt inc)
                         (update :chache conj data))))
        (do
          (println "OBSCURE: log buffer is full:" (str/join "\n" cache))
          (swap! cla assoc :cl nil :cache [data] :cnt 1))))))
