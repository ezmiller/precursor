(ns pc.delay
  (:import [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

(defn cpu-count []
  (.availableProcessors (Runtime/getRuntime)))

(defn make-pool! [& {:keys [thread-count]
                     :or {thread-count (+ 2 (cpu-count))}}]
  (ScheduledThreadPoolExecutor. thread-count))

(defn delay-fn [thread-pool delay-ms f]
  (.schedule thread-pool f delay-ms TimeUnit/MILLISECONDS))

(defn shutdown-pool! [pool]
  (.shutdown pool))
