(ns klog.es-test
  (:require  [clojure.test :refer :all]
             [klog.core :as klog]
             [org.httpkit.client :as http]
             [cheshire.core :as json]))

(deftest ^:kaocha/pending klog-es-test

  (klog/enable-log)

  (is (#{404 200} (:status @(http/delete "http://localhost:9200/logs"))))

  (is (#{404 200} (:status @(http/put "http://localhost:9200/logs" ))))

  klog/appenders

  (klog/es-appender
   {:es-url "http://localhost:9200"
    :lvl                :all
    :appender-id        :es
    :index-pat          "'logs'"
    :batch-size         2
    :batch-timeout      10000
    :fallback-max-lines 10000})

  (klog/log :test {:msg (str "message 1")})

  (await klog/publisher)

  (is (= 200 (:status @(http/post "http://localhost:9200/logs/_refresh"))))

  (is (empty?
       (->>
        (get-in
         (json/parse-string
          (:body
           @(http/get "http://localhost:9200/logs/_search"))
          true)
         [:hits :hits])
        (map :_source)
        (map :msg))))

  (klog/log :test {:msg (str "message 2")})

  (await klog/publisher)

  (is (= 200 (:status @(http/post "http://localhost:9200/logs/_refresh"))))

  (is (= ["message 1" "message 2"]
         (->>
          (get-in
           (json/parse-string
            (:body
             @(http/get "http://localhost:9200/logs/_search"))
            true)
           [:hits :hits])
          (map :_source)
          (map :msg))))

  (klog/log :test {:msg (str "message 3")})

  (await klog/publisher)

  (def flush-result (klog/flush))

  (is (true? (realized? flush-result)))
  (is (= [{:id :es :result :flushed}] flush-result))

  (is (= 200 (:status @(http/post "http://localhost:9200/logs/_refresh"))))

  (is (= ["message 1" "message 2" "message 3"]
         (->>
          (get-in
           (json/parse-string
            (:body
             @(http/get "http://localhost:9200/logs/_search"))
            true)
           [:hits :hits])
          (map :_source)
          (map :msg)))))
