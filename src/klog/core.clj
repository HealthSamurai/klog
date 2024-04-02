(ns klog.core
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.stacktrace :as stacktrace]
    [clojure.string :as str]
    [klog.devlog]
    [klog.loki]
    [klog.obscure]
    [org.httpkit.client :as http])
  (:import
    (java.io
      BufferedWriter
      FileWriter
      Writer)
    (java.text
      SimpleDateFormat)
    (java.time
      LocalDateTime)
    (java.time.format
      DateTimeFormatter)
    (java.util
      Date
      TimeZone))
  (:refer-clojure :exclude [flush]))

;; (set! *warn-on-reflection* true)

(def fmt
  (let [tz (TimeZone/getTimeZone "UTC")
        df (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")]
    (.setTimeZone df tz)
    df))


(defn format-date
  [^Date x]
  (str (.format ^SimpleDateFormat fmt x)))

(defonce ^:dynamic *enable* (if (System/getenv "KLOG_DISABLE") false true))

(defn enable-log  [] (alter-var-root #'*enable* (constantly true)))
(defn disable-log [] (alter-var-root #'*enable* (constantly false)))


(defonce ^:dynamic *warn-unknown-event* false) ; TODO: add option to enable

(defonce publisher (agent nil :error-mode :continue))


(comment

  (do
    (restart-agent publisher nil)
    :ups))


;; TODO: it's better to have one ThreadLocal map instead of -op, -ctx, -tn...
;; But interface could be saved (get-ctx, set-ctx...)
;; nicola: Looks like it's better to pass ctx into logs - but we have to refactor all log calls

(def -op (ThreadLocal.))
(defn set-op [x] (.set ^ThreadLocal -op x))
(defn get-op [] (.get ^ThreadLocal -op))
(defn clear-op [] (.set ^ThreadLocal -op nil))

(def -ctx (ThreadLocal.))
(defn set-ctx [v] (.set ^ThreadLocal -ctx v))
(defn get-ctx [] (.get ^ThreadLocal -ctx))
(defn clear-ctx [] (.set ^ThreadLocal -ctx nil))


(def -context (ThreadLocal.))

(defn set-context
  "Set local log context map
  (set-context {:foo bar :tar mar})"
  [v] (.set ^ThreadLocal -context v))

(defn get-context [] (.get ^ThreadLocal -context))
(defn clear-context [] (.set ^ThreadLocal -context nil))


(def -tn (ThreadLocal.))
(defn set-tn [v] (.set ^ThreadLocal -tn v))
(defn clear-tn [] (.set ^ThreadLocal -tn nil))


(defn mk-log
  [ev arg]
  (let [i   (format-date (Date.))
        timeUnix (System/currentTimeMillis)
        w   (.getName (Thread/currentThread))
        tn  (.get ^ThreadLocal  -tn)
        op  (.get ^ThreadLocal -op)
        ctx     (get-ctx)
        context (get-context)
        log (cond-> (assoc arg :timeUnix timeUnix :ts i :w w :ev ev)
              tn  (assoc :tn tn)
              ctx (assoc :ctx ctx)
              op  (assoc :op op)
              context (merge context))]
    log))


(defonce appenders (atom {}))

(defn clear-appenders [] (reset! appenders {}))

(def level-priorities
  {:off 0
   :fatal 100
   :error 200
   :warn 300
   :info 400
   :debug 500
   :trace 600
   :all Integer/MAX_VALUE})

(defn filter-log-by-lvl
  [appender-lvl-num f]
  (fn [l]
    (let [lvl-num (get level-priorities (or (:lvl l) :info) 0)]
      (when (<= lvl-num appender-lvl-num)
        (f l)))))

(defn add-appender
  ([{:keys [id f flush state transform]}]
   (swap! appenders assoc id {:log f :state state :transform transform :flush flush}))
  ([k f]
   (add-appender k :all f))
  ([k lvl f]
   (assert (contains? level-priorities lvl) (str lvl " level not supported. Pick one of " (str (keys level-priorities))))
   (swap! appenders assoc k
          {:log (if (= :all lvl)
                  f
                  (filter-log-by-lvl (get level-priorities lvl) f))}))
  ([k lvl f state]
   (assert (contains? level-priorities lvl) (str lvl " level not supported. Pick one of " (str (keys level-priorities))))
   (swap! appenders assoc k {:log f :state state})))


(defn rm-appender
  "Removes given appender."
  [k]
  (swap! appenders dissoc k))


(defn append-log
  [^Writer w l]
  (try
    (cheshire.core/generate-stream l w)
    (.write w "\n")
    (.flush w)
    (catch Exception e
      (println "ERROR while logging" e))))


(defn millis->protobuf-duration [ms]
  (when ms
    (str (/ ms 1000.) "s")))

(defn ->google-format [l]
  (-> l
      (assoc :severity (str/upper-case (name (:lvl l :info))))
      (dissoc :lvl)
      (assoc :timestamp (:ts l))
      (dissoc :ts)
      (cond->
        (= :w/resp (:ev l))
        (assoc :httpRequest
               {:requestMethod (when (:w_m l) (str/upper-case (name (:w_m l))))
                :requestUrl (:w_url l)
                :requestSize nil
                :status (:w_st l)

                ;; we don't have body size in proto.box/handle-to-ctx
                ;; :responseSize nil ;; ignored

                :userAgent (:w_user_agent l)
                :remoteIp (:w_ip l)
                ;; :serverIp (:w_server_id l) ;; FIXME: deal with slash in the begging
                :referer (:w_referer l)
                :latency (millis->protobuf-duration (:d l))

                ;; seems like httpkit doesn't provide protocol version. See https://chat.openai.com/share/3ad018e3-fa5b-4f59-8dbc-0e3811678c8f
                ;; :protocol nil ;; ignored

                ;; We don't cache
                ;; :cacheLookup nil
                ;; :cacheHit nil
                ;; :cacheValidatedWithOriginServer nil
                ;; :cacheFillBytes nil
                })
        (= :w/resp (:ev l))
        (dissoc :w_m :w_url :w_st :w_user_agent :w_ip :w_referer :d)

        (some? (:ctx l))
        (assoc-in [:operation :id] (:ctx l))
        (some? (:ctx l))
        (dissoc :ctx))))

(defn stdout-google-appender [& [lvl]]
  (add-appender
   :google-format-stdout (or lvl :all)
   (fn [l]
     (let [google-format-log (->google-format l)]
       (println (cheshire.core/generate-string google-format-log))))))

(defn stdout-appender [& [lvl]]
  (add-appender :stdout (or lvl :all) (fn [l] (println (cheshire.core/generate-string l)))))

(defn green  [x] (str "\033[0;32m" x "\033[0m"))
(defn gray   [x] (str "\033[0;37m" x "\033[0m"))
(defn yellow [x] (str "\033[0;33m" x "\033[0m"))
(defn white  [x] (str "\033[0;97m" x "\033[0m"))
(defn cyan   [x] (str "\033[0;36m" x "\033[0m"))
(defn red    [x] (str "\033[0;31m" x "\033[0m"))


(defn format-line
  [l]
  (try
    (let [s (cond-> []
              (:tn l) (conj (:tn l))
              (:ts l) (conj (subs (:ts l) 11 19))
              (:lvl l) (conj (:lvl l))
              (:w l)  (conj (gray
                              (let [w (:w l)
                                    c (count w)]
                                (if (> c 5)
                                  (subs w (- c 5) c)
                                  w))))
              (:d l)  (conj (str "[" (:d l) "ms]"))
              (:err l) (conj (red (:err l))))

          s (cond
              (= :w/req (:ev l))
              (conj s (yellow (str/upper-case (name (or (:w_m l) "get"))))
                    (if-let [qs (:w_qs l)]
                      (str (white (:w_url l)) "?" qs)
                      (white (:w_url l))))

              (= :w/resp (:ev l))
              (conj s (yellow (:w_st l)))

              (= :auth/authorized-access-policy (:ev l))
              (conj s
                    (white "policy")
                    (green (when-let [id (:access-policy-id l)]
                             (name id))))

              (= :resource/create (:ev l))
              (conj s
                    (white "create")
                    (green (str (:rtp l) "/" (:rid l))))

              (= :resource/update (:ev l))
              (conj s
                    (white "update")
                    (green (str (:rtp l) "/" (:rid l))))

              (= :resource/delete (:ev l))
              (conj s
                    (white "delete")
                    (green (str (:rtp l) "/" (:rid l))))

              (or (= :db/q (:ev l))
                  (= :db/ex (:ev l)))
              (conj s (cyan (or (:sql l) ""))
                    (:db_prm l))

              (= :w/ex (:ev l))
              (conj s
                    (red (:msg l))
                    (red (:etr l)))

              :else (conj s (yellow (str (:ev l))) (dissoc l :tn :ts :ev :w :lvl :error)))]
      (str/join  " " s))
    (catch Exception e
      (println "UPS EX IN LOGS: " e))))


(defn pretty-appender
  [l]
  (println (format-line l)))


(defn stdout-pretty-appender
  ([] (stdout-pretty-appender :all))
  ([lvl]
   (let [_ctx (atom nil)]
     (add-appender :pretty-stdout lvl pretty-appender))))

(defn pretty-appender-regexp
  [regexp]
  (fn [l]
    (let [line (format-line l)]
      (when (re-find regexp line)
        (println line)))))

(defn stdout-pretty-appender-regexp
  [regexp]
  (add-appender :pretty-stdout :all (pretty-appender-regexp regexp)))


(defn gz-writer
  [pth]
  (let [w  (FileWriter. ^String pth true)]
    (BufferedWriter. w)))


;; FIXME: it doesn't allow to specify log lvl
(defn file-appender
  [path & [max-lines]]
  (let [w         (atom (gz-writer path))
        max-lines (or max-lines 10000)
        i         (atom 0)]
    (add-appender :file :all (fn [l]
                               (let [^Writer wr @w]
                                 (if (<= max-lines @i)
                                   (do (append-log wr l)
                                       (.close  wr)
                                       (.renameTo (io/file path) (io/file (str path ".old")))
                                       (reset! w (gz-writer path))
                                       (reset! i 0))
                                   (do (append-log @w l) ; TODO: replace @w with wr?
                                       (swap! i inc))))))))


(defonce obscure-client (atom nil))


(defn obscure-appender
  ([obscure-url]
   (obscure-appender :all obscure-url))
  ([lvl obscure-url]
   (let [cl (klog.obscure/connection obscure-url)]
     (add-appender :obscure lvl (fn [l] (klog.obscure/send-data cl l))))))

(defn devlog-appender
  ([db]
   (devlog-appender :all db))
  ([lvl db]
   (add-appender :devlog lvl (fn [l] (klog.devlog/devlog-appender db l)))))


(defn emit
  [_ {_ts :ts _ns :ns _ev :ev :as l}]
  (doseq [{f :log s :state xs :transform} (vals @appenders)]
    (when-let [l' (if xs (xs l) l)]
      (if s
        (f s l')
        (f l')))))

(defn flush [& [timeout-ms]]
  (if timeout-ms
    (await-for timeout-ms publisher)
    (await publisher))
  (doall
   (for [[id appender] @appenders
         :let [flush-fn (:flush appender)]]
     (if (some? flush-fn)
       {:id id :result (if-let [state (:state appender)] (flush-fn state) (flush-fn))}
       {:id id :result :no-flush-fn}))))

(defonce shutdown-hook (new Thread flush))

(try
  (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
  (catch java.lang.IllegalArgumentException e
    (when (not= "Hook previously registered" (.getMessage e))
      (throw e))))

(comment
  (System/exit 2)

  (.halt
   (Runtime/getRuntime) 2)

  )

(defn log
  [ev arg]
  (when *enable*
    (send-off publisher emit (mk-log ev arg))
    nil))

(defn log-ex [e]
  (let [cause-trace (with-out-str (stacktrace/print-cause-trace e))]
    (log :w/ex (cond-> {:msg cause-trace :lvl :error} cause-trace (assoc :etr cause-trace)))))

(set-error-handler!
  publisher
  (fn [_agent err]
    (println ::internal-error err)))


(defn err-msg
  [e]
  (when e (.getMessage ^Exception e)))

(defn exception
  [ev e & [args]]
  (log ev (assoc (or args {})
                 :lvl :error
                 :ex/type (type e)
                 :msg (err-msg e)
                 :etr (with-out-str (stacktrace/print-stack-trace e)))))

(defn exeption [& args]
  (println "WARN: Deprecated exeption. Use exception")
  (apply exception args))

(defn error [ev arg] (log ev (assoc arg :lvl :error)))
(defn warn  [ev arg] (log ev (assoc arg :lvl :warn)))
(defn info  [ev arg] (log ev (assoc arg :lvl :info)))
(defn debug [ev arg] (log ev (assoc arg :lvl :debug)))
(defn trace [ev arg] (log ev (assoc arg :lvl :trace)))


(defn parse-int
  [s]
  (when-let [x (re-matches #"[-+]?\d+" (str s))]
    (Integer/parseInt x)))


(defn save-to-file
  [pth cont]
  (spit pth cont :append true))

(def ^:const minute 60000)

(defn clear-nils
  [m]
  (->> m
       (reduce (fn [m [k v]]
                 (if (nil? v) (dissoc m k) m))
               m)))


(def es-defaults
  {:lvl                :all
   :appender-id        :es
   :index-pat          "'aidbox-logs'-yyyy-MM-dd"
   :batch-size         200
   :batch-timeout      minute
   :fallback-max-lines 10000})


(defn mk-idx
  [n]
  (str "{\"create\": {\"_index\": \"" n "\"}}\n"))


(defn get-idx
  [date-fmt]
  (mk-idx (.format ^DateTimeFormatter date-fmt (LocalDateTime/now))))


(defn es-default-state
  [cfg]
  (let [b (StringBuilder.)]
    {:start-time (System/currentTimeMillis)
     :batch b
     :index (get-idx (:date-fmt cfg))
     :i 0
     :cfg cfg}))


(defn flush-es-logs [*state]
  (locking *state
    (if (not-empty (:batch @*state))
      (let [{^StringBuilder b :batch
             i :i
             {post-params :post url :url :as cfg} :cfg} @*state]
        (try
          (let [start (System/currentTimeMillis)
                cb (fn [res]
                     (let [body (try
                                  (json/parse-string (:body res) true)
                                  (catch Exception _ nil))]
                       (cond
                         (:errors body)
                         (let [errors
                               (->> (:items body)
                                    (keep #(get-in % [:create :error :reason]))
                                    (str/join "\n"))]
                           (do
                             (println ::es-batch-error errors)
                             (swap! *state assoc :error errors)))

                         :else
                         (if-let [err (:error res)]
                           (let [err-msg (.getMessage err)]
                             (println ::es-batch-error err-msg)
                             (swap! *state assoc :error err-msg))
                           (if-let [s (:status res)]
                             (if (> s 300)
                               (do
                                 (println ::es-batch-error s (:body res))
                                 (swap! *state assoc :error res))
                               (let [status {:msgs i :d (- (System/currentTimeMillis) start)}]
                                 (swap! *state (fn [x] (-> x (dissoc :error) (assoc :status status))))
                                 (println (str ::es-batch-sent " " status))))
                             (println ::es-batch-unexpected res))))))]
            (cb @(http/post url (assoc post-params :body (.toString b)))))
          (catch Exception e
            (println ::es-batch-error (.getMessage e))))
        (reset! *state (es-default-state cfg))
        :flushed)
      :flush-ignored)))

(defn log-es-message
  [*state l]
  (let [{^StringBuilder b :batch
         i :i
         start-time :start-time
         index :index
         {batch-size :batch-size
          batch-timeout :batch-timeout} :cfg} @*state]
    (.append b index)
    (.append b (json/generate-string (assoc l "@timestamp" (:ts l))))
    (.append b "\n")
    (swap! *state (fn [s] (update s :i inc)))
    (when (or (<= batch-size (inc i))
              (< batch-timeout (- (System/currentTimeMillis) start-time)))
      (flush-es-logs *state))))

(defn es-appender
  [arg]
  (let [cfg (merge es-defaults (clear-nils arg))
        appender-id        (:appender-id cfg)
        cfg (-> cfg
                (update :batch-size parse-int)
                (update :batch-timeout parse-int)
                (assoc :url (str (:es-url cfg) "/_bulk")
                       :post (cond-> {:headers {"Content-Type" "application/x-ndjson"}}
                               (:es-auth cfg) (assoc :basic-auth (:es-auth cfg)))
                       :date-fmt (DateTimeFormatter/ofPattern ^String (:index-pat cfg))))
        state         (atom (es-default-state cfg))]
    (add-appender {:id        appender-id
                   :f         log-es-message
                   :flush     flush-es-logs
                   :state     state
                   :transform (:transform arg)})))


(defn dd-appender
  "Appender to Datadog Logs service

  Uses Datadog API to upload logs

  Argument map keys:
  - `:lvl`: Appender log level, higher level -- higher verbosity
  - `:dd-auth`: Datadog API Key
  - `:dd-tags`: Datadog tags
  - `:dd-site`: The regional site for a Datadog customer. (This can only be one of datadoghq.com,us3.datadoghq.com,datadoghq.eu,ddog-gov.com) 
  - `:appender-id`: Appender name
  - `:batch-size`: how many log entries to collect before uploading
  - `:batch-timeout`: how long to wait before uploading
  - `:fallback-file`: file to write in if uploading fails"
  [arg]
  (let [{:keys [lvl           dd-auth
                appender-id   batch-size
                batch-timeout fallback-file
                dd-tags dd-site] #_"TODO: add max-lines support"
         :or   {lvl                :all
                appender-id        :dd
                batch-size         200
                batch-timeout      minute}}
        (apply dissoc arg (for [[k v] arg :when (nil? v)] k))

        batch-size         (parse-int batch-size)
        batch-timeout      (parse-int batch-timeout)
        dd-url             (str "https://http-intake.logs." (or dd-site "datadoghq.com") "/v1/input")

        post-params   {:headers {"Content-Type" "application/json"
                                 "DD-API-KEY" dd-auth}}
        default-state (fn []
                        {:start-time (System/currentTimeMillis)
                         :batch      nil
                         :i          0})
        state         (atom (default-state))
        log-fallback  (if fallback-file (comp (partial save-to-file fallback-file) str) (comp println str/trim-newline str))]
    (letfn [(mk-log-entry
              [m]
              {:message (json/generate-string m)
               :ddsource "aidbox"
               :ddtags dd-tags})
            (log-error
              [batch l errs]
              (->> (for [{:keys [error status]} errs]
                     (->> {:err  (err-msg error)
                           :w_st status
                           :etr  (with-out-str (stacktrace/print-stack-trace error))
                           :lvl  :error
                           :ev   :log/ex}
                          (merge (select-keys l [:w :tn :ts :ctx]))
                          mk-log-entry))
                   (str/join "\n")
                   (str (str/join "\n" batch) "\n")
                   ;; (concat batch)
                   log-fallback))
            (report-posting-errors
              [reporter {:keys [error status] :or {status 500} :as args}]
              ;; FIXME Datadog docs and actual behavior differ
              ;; I Could only get HTTP 400 with HTML stating that
              ;; "Your browser sent an invalid request"
              ;; Therefore error handling is useless
              ;;
              ;; Datadog integration implementation is based on elasticsearch integration
              ;; And "clever" error reporting is commented until datadog behavior is investigated
              (some->> (or (when error
                             [{:status status
                               :error error}])
                           #_(let [body (some-> (:body args) (json/parse-string keyword))]
                               (when (:errors body)
                                 (->> (:items body)
                                      (mapcat
                                       (fn [actions]
                                         (for [[_ {:keys [status error]}] actions
                                               :when (seq error)]
                                           {:status status
                                            :error (Exception. (json/generate-string error))}))))))
                           (when (< 299 status)
                             [{:status status
                               :error (Exception. (str status "\n" (:body args)))}]))
                       reporter))]
      (add-appender
        appender-id
        lvl
        (fn [l]
          (try
            (swap! state #(-> % (update :batch conj (mk-log-entry l)) (update :i inc)))
            (when-let [batch (and (or (<= batch-size (:i @state))
                                      (< batch-timeout (- (System/currentTimeMillis) (:start-time @state))))
                                  (:batch @state))]
              (try (http/post dd-url (assoc post-params :body (json/generate-string batch))
                              (partial report-posting-errors (partial log-error batch l)))
                   (catch Exception e (log-error batch l [{:error e :status 500}])))
              (reset! state (default-state)))
            (catch Exception e
              (println "LOG ERROR:" (err-msg e))
              (clojure.stacktrace/print-stack-trace e)
              (println "LOG:" l))))))))


(defn devbox-monitoring-appender
  "Unreliably sends logs marked with license owner & license id to aidbox central server"
  [{:keys [license-owner-email license-id box-name
           license-token url
           index-pat batch-size batch-timeout]}]
  (let [index-pat     (or index-pat "'devbox-logs'-yyyy-MM-dd")
        batch-size    (or (parse-int batch-size) 200)
        batch-timeout (or (parse-int batch-timeout) minute)

        post-params {:headers {"Content-Type" "application/x-ndjson"
                               "x-license"    (str "Bearer " license-token)}}

        monitoring-log-part {:lid   license-id
                             :luser license-owner-email
                             :bname box-name}

        date-fmt      (DateTimeFormatter/ofPattern index-pat)
        default-state (fn []
                        {:start-time (System/currentTimeMillis)
                         :batch      nil
                         :i          0})
        state         (atom (default-state))]
    (letfn [(mk-idx [n] (str "{\"index\": {\"_index\": \"" n "\"}}\n"))
            (get-idx []  (mk-idx (.format date-fmt (LocalDateTime/now))))
            (mk-log-line [m] (str (get-idx) (json/generate-string m) "\n"))]
      (fn [log-map]
        (let [l (merge log-map monitoring-log-part)]
          (swap! state #(-> %
                            (update :batch str (mk-log-line l))
                            (update :i inc)))
          (when-let [batch (and (or (<= batch-size (:i @state))
                                    (< batch-timeout (- (System/currentTimeMillis) (:start-time @state))))
                                (:batch @state))]
            (try @(http/post url (assoc post-params :body batch))
                 (catch Exception _))
            (reset! state (default-state))))))))


(defn loki-appender
  [arg]
  (add-appender :loki :all klog.loki/log-message (klog.loki/default-state arg)))


(comment
  (disable-log)

  (append-log *out* {:ev :myevent})

  *out*

  (es-appender
   {:es-url "http://localhost:9200"
    :appender-id :es
    :batch-size 1
    :batch-timeout 3600})

  (stdout-appender)

  (clear-appenders)
  (obscure-appender "tcp://localhost:7777")


  (file-appender "/tmp/logs" 100000)

  (def w (gz-writer "/tmp/logs"))

  (append-log w {:ev :ups/myevent
                 :ts "2011-01-01"
                 :msg "ups"})

  (log ::event {:lvl :error :msg "Hello"})

  (doseq [i (range 10000)]
    (log ::event {:message (str "msg - " i)}))

  (cheshire.core/generate-stream {:a 1} *out*)

  (def f (io/file "/tmp/logs.ndjson"))

  (int (/ (.length f) 1024.0))

  (.close w)


  (enable-log))
