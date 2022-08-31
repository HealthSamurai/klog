(ns klog.loki-test
  (:require
   [klog.loki]
   [clojure.test :refer [deftest]]
   [matcho.core :as matcho]))




(deftest loki-test

  (def state (klog.loki/default-state {:batch-size 1
                                       :stream "{\"customerID\": \"11\"}"
                                       :batch-timeout 3000
                                       :sync  true
                                       :url "http://localhost:3100"}))

  (def line {:opts 1 :lvl :info :ev "test/event" :ts "2022-07-12T11:30:17.592Z"})

  (matcho/match (klog.loki/*log-message state line)
    {:method :post
     :url "http://localhost:3100/loki/api/v1/push"
     :body {:streams [{:stream {:customerID "11"}
                       :values [[string? string? nil] nil]}]}})

  (matcho/match (klog.loki/*log-message state line)
    {:method :post
     :url "http://localhost:3100/loki/api/v1/push"
     :body {:streams [{:stream {:customerID "11"}
                       :values [[string? string? nil]
                                [string? string? nil]
                                nil]}]}}))
