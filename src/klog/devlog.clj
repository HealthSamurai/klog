(ns klog.devlog
  (:require
    [cheshire.core]
    [clojure.java.jdbc]))


(defn devlog-appender
  [db l]
  (try
    (clojure.java.jdbc/execute!
      db ["INSERT INTO  _logs (resource) VALUES (?)"
          (cheshire.core/generate-string l)])
    (catch Exception e
      (println e)
      :ups)))
