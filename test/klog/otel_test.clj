(ns klog.otel-test
  (:require [clojure.test :refer :all]
            [klog.core :as sut]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            ))


(deftest ^:kaocha/pending otel-appender-test

  (do
    (sut/add-otel-appender {:url "http://localhost:4318"})

    (sut/log :test-log-2 {:msg "hello!!!"})

    (is (= 200 (:status @(http/post "http://localhost:9200/logs/_refresh")))))





  )
