(ns klog.core-test
  (:require [klog.core :as sut]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [matcho.core :as matcho]
            [org.httpkit.server :as http-server]
            [cheshire.core]))


(defn remove-if-exists [path]
  (let [f (io/file path)]
    (when (.exists f)
      (.delete f))))


(defn wait-for-publish [times period]
  (let [timeout (atom times)]
    (send-off sut/publisher (fn [_ arg] arg) :done)

    (while (and (not= :done @sut/publisher)
                (pos? @timeout))
      (swap! timeout dec)
      (Thread/sleep period))

    (send-off sut/publisher (fn [_ arg] arg) nil)))


(use-fixtures :each
  (fn [t]
    (let [logs-enable-state        sut/*enable*
          appenders                @sut/appenders]
      (alter-var-root #'sut/*enable* (constantly true))
      (reset! sut/appenders {})

      (t)

      (alter-var-root #'sut/*enable* (constantly logs-enable-state))
      (reset! sut/appenders appenders)
      (sut/clear-context))))

(deftest test-threadlocal-vars
  (testing "-context"
    (sut/set-context {:foo "bar"})
    (matcho/match
     (sut/mk-log :event {:url "/hello"})
     {:foo "bar"
      :url "/hello"}))
  (testing "-ctx"
    (sut/set-ctx "my-ctx-id")
    (matcho/match
     (sut/mk-log :event {:url "/hello"})
     {:foo "bar"
      :url "/hello"
      :ctx "my-ctx-id"}))
  (testing "-tn & -op"
    (sut/set-tn "my-tn")
    (sut/set-op "GET /hello")
    (matcho/match
     (sut/mk-log :event {:url "/hello"})
     {:foo "bar"
      :url "/hello"
      :ctx "my-ctx-id"
      :op "GET /hello"
      :tn "my-tn"}))

  (sut/clear-context)
  (sut/clear-ctx)
  (sut/clear-op)
  (sut/clear-tn))


(deftest ^:slow error-in-lazy-log-message
  (def logs-file "/tmp/lazy-log-test-logs.ndjson")

  (remove-if-exists logs-file)
  (remove-if-exists (str logs-file ".old"))

  (defn fib
    ([]
     (fib 1 1))
    ([a b]
     (lazy-seq (cons a (fib b (+ a b))))))


  (sut/stdout-appender)
  (sut/file-appender logs-file 10)

  (sut/log :test/event {:fib (take 5 (fib))})


  ;; Attempt to log full sequence will cause overflow error.
  (sut/log :test/event {:fib (fib)})

  (sut/log :test/event {:fib (take 5 (fib))})

  (Thread/sleep 1000)

  (matcho/match
   (let [logs (-> logs-file
                  slurp
                  (str/split #"\n"))]
     (->> logs
          (map #(cheshire.core/parse-string % keyword))
          (filter #(#{"test/event" "klog.core/internal-error"} (:ev %)))
          (sort-by :ev)))

    [{:ev "klog.core/internal-error"}
     {:fib [1,1,2,3,5]
      :ev "test/event"}
     {:fib [1,1,2,3,5]
      :ev "test/event"}]))


(deftest ^:slow klog-levels-test
  (defonce logs (atom []))

  (reset! logs [])

  (sut/add-appender :test-appender :info (fn [l] (swap! logs conj l)))

  (do
    (sut/log :test/event {:opts 0})
    (sut/log :test/event {:opts 1 :lvl :info})
    (sut/log :test/event {:opts 2 :lvl :warn})
    (sut/log :test/event {:opts 3 :lvl :debug}))

  (wait-for-publish 20 100)

  (is (matcho/match
       @logs
        [{:opts 0}
         {:opts 1 :lvl :info}
         {:opts 2 :lvl :warn}
         nil]))

  (reset! logs [])

  (sut/add-appender :test-appender (fn [l] (swap! logs conj l)))

  (do
    (sut/log :test/event {:opts 0})
    (sut/log :test/event {:opts 1 :lvl :info})
    (sut/log :test/event {:opts 2 :lvl :warn})
    (sut/log :test/event {:opts 3 :lvl :debug}))

  (wait-for-publish 20 100)

  (is (matcho/match
       @logs
        [{:opts 0}
         {:opts 1 :lvl :info}
         {:opts 2 :lvl :warn}
         {:opts 3 :lvl :debug}]))


  (reset! logs [])

  (sut/add-appender :test-appender :warn (fn [l] (swap! logs conj l)))

  (do
    (sut/log :test/event {:opts -1})
    (sut/trace :test/event {:opts 0})
    (sut/debug :test/event {:opts 1})
    (sut/info :test/event {:opts 2})
    (sut/warn :test/event {:opts 3})
    (sut/error :test/event {:opts 4}))

  (wait-for-publish 20 100)

  (is (matcho/match
       @logs
        [{:opts 3 :lvl :warn}
         {:opts 4 :lvl :error}
         nil?])))


(do ;; TODO: move to utils make reusable

  (defonce service (atom nil))

  (defn service-handler [req]
    (let [res (update req :body (fn [b]
                                  (some->> (slurp (.bytes b) :encoding "UTF-8")
                                           clojure.string/split-lines
                                           seq
                                           (mapv #(cheshire.core/parse-string % keyword)))))]
      (swap! service
             (fn [state]
               (-> state
                   (update :nots conj res)
                   (assoc :last-note res))))
      {:status 200
       :body (cheshire.core/generate-string {:message "Ok" :request-id (:id (:body res))})}))

  (defn start-service [port handler]
    (swap! service assoc :srv (http-server/run-server handler {:port port})))

  (defn stop-service []
    (when-let [srv (:srv @service)]
      (srv)))

  (defrecord MockChannel [state])

  (defn parse-body [b]
    (when b
      (cond
        (string? b) (cheshire.core/parse-string b keyword)
        (instance? java.io.InputStream b) (cheshire.core/parse-stream (io/reader b) keyword)
        :else b)))

  (extend-type MockChannel
    org.httpkit.server/Channel
    (open? [_] true)
    (close [_]
      #_(println "close"))
    (websocket? [_] false)
    (send!
      ([ch data]
       (swap! (:state ch) assoc :send! (-> (:body data) parse-body)))
      ([ch data _close-after-send?]
       (swap! (:state ch) assoc :send-and-close! (-> (:body data) parse-body))))
    (on-receive [_ch _callback] #_(println "on-recieve"))
    (on-ping [_ch _callback] #_(println "on-ping"))
    (on-close [_ch _callback] #_(println "on-close")))

  (defn wait-channel [state body]
    (loop [i 100]
      (let [ln (:send-and-close! @state)]
        (if (< i 0)
          (matcho/match  ln body)
          (when (empty? (matcho.core/match {} ln body))
            (matcho/match ln body)
            (Thread/sleep 2)
            (recur (dec i)))))))

  (defmacro service-got
    "waits for the service to receive notification for the maximum of 1 sec"
    [req]
    `(loop [i# 100]
       (let [ln# (:last-note @service)]
         (if (< i# 0)
           (do
             (matcho/match ln# ~req)
             (:body ln#))
           (if (empty? (matcho.core/match* {} ln# ~req))
             (do
               (matcho/match ln# ~req)
               (:body ln#))
             (do
               (Thread/sleep 10)
               (recur (dec i#)))))))))


(deftest devbox-monitoring
  (def url "http://127.0.0.1:5678/devbox-monitoring")

  (sut/clear-appenders)
  (stop-service)

  (start-service 5678 #'service-handler)

  (testing "Log bundle contains license owner id & license"
    (sut/add-appender
     :devbox-monitoring-appender-1
     :all
     (sut/devbox-monitoring-appender
      {:url                 url
       :license-owner-email "User-1"
       :box-name            "test"
       :license-id          "License-1"
       :license-token       "token"
       :index-pat           "'test-devbox-logs'-yyyy-MM-dd"
       :batch-size          1
       :batch-timeout       1}))

    (sut/log :test/event {:opts 1})

    (service-got {:headers {"x-license" "Bearer token"}
                  :body    [{:index {:_index string?}}
                            {:opts  1
                             :luser "User-1"
                             :bname "test"
                             :lid   "License-1"}
                            nil?]})))
