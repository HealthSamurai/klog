(ns klog.monitor
  (:require
    [box.manifest]
    [clojure.string :as str]
    [klog.core])
  (:import
    (java.lang.management
      ManagementFactory
      MemoryType)))


(defonce *gc (atom nil))


(defn mb
  [x]
  (when x
    (/ (Math/floor (/ x 10000)) 100.0)))


(def nm-map
  {"Metaspace" :metaspace
   "CodeHeap 'profiled nmethods'" :codeheap
   "CodeHeap 'non-nmethods'"      :codeheap-n
   "CodeHeap 'non-profiled nmethods'" :codeheap-np
   "Compressed Class Space" :class
   "G1 Eden Space" :gc-eden
   "G1 Old Gen" :gc-old
   "G1 Survivor Space" :gc-surv})


(defn namify
  [x]
  (->
    x
    (str/replace  #"[^a-zA-Z0-9]" "-")
    (str/replace  #"-+" "-")
    (str/replace  #"-$" "")
    (str/lower-case)
    (keyword)))


(defn gc-metrics
  []
  (let [now (Math/ceil (/ (System/currentTimeMillis) 1000.0))
        g (->> (ManagementFactory/getGarbageCollectorMXBeans)
               (reduce (fn [acc gc]
                         (let [c (.getCollectionCount gc)
                               t (.getCollectionTime gc)]
                           (-> acc
                               (update :count (fn [x] (+ x c)))
                               (update :time (fn [x] (+ x t))))))
                       {:count 0 :time 0}))
        gc @*gc]
    (if (nil? (:ts gc))
      (do (reset! *gc (assoc g :ts now))
          nil)
      (let [dt (- now (:ts gc))
            dc (- (:count g) (:count gc))
            dtm (- (:time g) (:time gc))
            res {:count (/ dc dt)
                 :time  (/ dtm dt)}]
        (reset! *gc (assoc g :ts now))
        res))))


(defn metrics
  []
  (let [rt (Runtime/getRuntime)
        tot (.totalMemory rt)
        free (.freeMemory rt)
        os (ManagementFactory/getOperatingSystemMXBean)
        tmb (ManagementFactory/getThreadMXBean)
        gc (gc-metrics)
        ev (cond->
             {:units "Mb"
              :mem_total (mb tot)
              :mem_free (mb free)
              :mem_used (mb (- tot free))
              :load_avg (.getSystemLoadAverage os)
              :threads (.getThreadCount tmb)
              :peak_threads (.getPeakThreadCount tmb)}
             gc (assoc :gc gc))]
    (.resetPeakThreadCount tmb)
    (->> (ManagementFactory/getMemoryPoolMXBeans)
         (reduce (fn [acc x]
                   (let [tp (namify (.toString (.getName x)))
                         us (.getUsage x)]
                     (assoc-in acc [:mem_pool tp] (cond-> {:used (mb (.getUsed us))
                                                           :commited (mb (.getCommitted us))}
                                                    (= (.getType x) MemoryType/HEAP) (assoc :heap true)))))
                 ev))))


(defn *run
  [timeout]
  (try
    (loop []
      (klog.core/log :jvm (metrics))
      (Thread/sleep timeout)
      (recur))
    (catch Exception _
      (println "Stop _metrics"))))


(defn start
  [timeout]
  (println "Starting JVM monotor")
  (let [th (Thread. ^Runnable (fn [] (*run timeout)))]
    (.setName th "_metrics")
    (.start th)))


(defn stop
  []
  (doseq [t (-> (Thread/getAllStackTraces) .keySet)]
    (when (= "_metrics" (.getName t))
      (.interrupt t))))
