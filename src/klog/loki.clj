(ns klog.loki
  (:require
    [cheshire.core :as json]
    [org.httpkit.client :as http])
  (:import
    (java.text
      SimpleDateFormat)))


(def ^:const hour 3600000)


(def defaults
  {:url nil
   ;; TODO: add default labels
   :stream {:box "aidbox"}
   :batch-size 200
   :batch-timeout hour})


(defn *default-state
  [config]
  {:start-time (System/currentTimeMillis)
   :batch (atom [])
   :i 0
   :config config})


(defn parse-json
  [s]
  (try (json/parse-string s keyword)
       (catch Exception _ nil)))


(defn parse-int
  [s]
  (if (int? s) s
      (try (Integer/parseInt s)
           (catch Exception _ nil))))


(defn coerce
  [params]
  (-> params
      (update :stream parse-json)
      (update :batch-size parse-int)
      (update :batch-timeout parse-int)))


(defn strip-nils
  [m]
  (reduce (fn [acc [k v]]
            (if (nil? v) acc
                (assoc acc k v))) {} m))


(defn default-state
  [params]
  (atom (*default-state
          (merge defaults (strip-nils (coerce params))))))


(defn nanosec
  [ts]
  (->> ts
       (.parse (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
       .getTime
       (* 1000000)))


;; (def labels [:ev :lvl :tn])
(defn *log-message
  [state l]
  (let [{:keys [config batch start-time i]} @state
        {:keys [batch-size batch-timeout]} config]
    (swap! batch conj [(str (nanosec (:ts l))) (json/generate-string l)])
    (swap! state update :i inc)
    (when (or (<= batch-size (inc i))
              (< batch-timeout (- (System/currentTimeMillis) start-time)))
      (let [_start (System/currentTimeMillis)
            params {:url (str (:url config) "/loki/api/v1/push")
                    :method :post
                    :headers {"content-type" "application/json"}
                    :body {:streams [{:stream (:stream config) :values @batch}]}}]
        #_(clojure.pprint/pprint params)
        params))))


(defn callback
  [state res]
  (if-let [err (:error res)]
    (let [err-msg (.getMessage err)]
      (println ::batch-error err-msg)
      (swap! state assoc :error err-msg))
    (if-let [s (:status res)]
      (if (> s 300)
        (do
          (println ::batch-http-error s (:body res))
          (swap! state assoc :error res))
        (let [status {:msgs (:i @state) :d (- (System/currentTimeMillis) (:start-time @state))}]
          (swap! state (fn [x] (-> x (dissoc :error) (assoc :status status))))
          (println (str ::batch-sent " " status))))
      (println ::batch-unexpected res))))


(defn log-message
  [state l]
  (try
    (when-let [req (*log-message state l)]
      (let [resp  (http/request (update req :body json/generate-string) (fn [res] (callback state res)))]
        (reset! state (*default-state (:config @state)))
        (when (get-in @state [:config :sync])
          @resp)))
    (catch Exception e
      (println ::loki-batch-error (.getMessage e))
      (swap! state assoc :error e)
      (reset! state (*default-state (:config @state))) nil)))
