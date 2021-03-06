(ns pc.http.talaria
  (:require [clj-time.core :as time]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [immutant.web.async :as immutant]
            [pc.delay :as delay]
            ;; have to extract the utils fns we use
            [pc.utils :as utils]
            [pc.util.seq :refer (dissoc-in)])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util.concurrent LinkedBlockingQueue]
           org.projectodd.wunderboss.web.async.HttpChannel))

(defonce talaria-state (ref {:connections {}
                             :stats {}}))

(defn init [& {:keys [ws-delay ajax-delay ping-ms]
               :or {ws-delay 30
                    ajax-delay 150
                    ping-ms (* 1000 60)}}]
  (let [recv-queue (LinkedBlockingQueue.)
        async-pool (delay/make-pool!)]
    (dosync (ref-set talaria-state {:connections {}
                                    :recv-queue recv-queue
                                    :async-pool async-pool
                                    :ws-delay ws-delay
                                    :ajax-delay ajax-delay
                                    :ping-ms ping-ms
                                    :stats {}})))
  talaria-state)

(defn shutdown [tal-state]
  (let [s @tal-state]
    (delay/shutdown-pool! (:async-pool s))))

(defn recv-queue [tal-state]
  (:recv-queue @tal-state))

(defn get-channel-info [tal-state ch-id]
  (get-in @tal-state [:connections ch-id]))

(defn ch-id [ch]
  (:tal/ch-id (immutant/originating-request ch)))

(defn ajax-channel? [channel]
  (instance? org.projectodd.wunderboss.web.async.HttpChannel
             channel))

(defn add-channel
  "Adds channel to state, given an id. Will throw if a channel
   already exists for the given id. Updates global stats."
  [tal-state id ch ping-job]
  (dosync
   (commute tal-state (fn [s]
                        (assert (not (get-in s [:connections id :channel])))
                        (-> s
                          (assoc-in [:connections id] {:channel ch
                                                       ;; store delay here so that we can optimize based on
                                                       ;; latency
                                                       :send-delay (if (or (nil? ch)
                                                                           (ajax-channel? ch))
                                                                     (:ajax-delay s)
                                                                     (:ws-delay s))
                                                       :delay-jobs (atom #{})
                                                       :send-queue (atom [])
                                                       :ping-job ping-job})
                          (update-in [:stats :connection-count] (fnil inc 0)))))))

(defn add-ajax-channel
  "Adds channel to state, given an id. Will throw if a channel
   already exists for the given id. Updates global stats."
  [tal-state id ch]
  (dosync
   (commute tal-state (fn [s]
                        (let [previous-ch (get-in s [:connections id :channel])]
                          (assert (get-in s [:connections id]))
                          (-> s
                            (assoc-in [:connections id :channel] ch)
                            (assoc-in [:connections id :previous-channel] previous-ch)
                            (update-in [:connections id :ajax-channel-count] (fnil inc 0))))))))

(defn record-error
  "Records error for a given channel and updates global stats"
  [tal-state id error]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (assoc :last-error error)
                                                                               (update-in [:error-count] (fnil inc 0)))))
                          (assoc-in [:stats :last-error] error)
                          (update-in [:stats :error-count] (fnil inc 0)))))))

(defn remove-channel
  "Removes channel and updates global stats"
  [tal-state id]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (update-in [:connections] dissoc id)
                          (update-in [:stats :connection-count] (fnil dec 0)))))))

(defn remove-ajax-channel
  "Removes channel and updates global stats"
  [tal-state id ch]
  (dosync
   (commute tal-state (fn [s]
                        (if (= (get-in s [:connections id :channel]) ch)
                          (dissoc-in s [:connections id :channel])
                          s)))))

(defn record-msg
  "Updates global stats for messages"
  [tal-state id msg]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (update-in [:receive-count] (fnil inc 0))
                                                                               (assoc :last-recv-time (java.util.Date.)))))
                          (update-in [:stats :receive-count] (fnil inc 0)))))))

(defn record-send
  "Updates global stats for messages"
  [tal-state id msg]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (update-in [:send-count] (fnil inc 0))
                                                                               (update-in [:in-flight] (fnil inc 0))
                                                                               (assoc :last-send-time (java.util.Date.)))))
                          (update-in [:stats :send-count] (fnil inc 0))
                          (update-in [:stats :in-flight] (fnil inc 0)))))))

(defn record-send-success
  "Updates global stats for messages"
  [tal-state id msg]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (update-in [:send-success-count] (fnil inc 0))
                                                                               (update-in [:in-flight] (fnil dec 0)))))
                          (update-in [:stats :send-success-count] (fnil inc 0))
                          (update-in [:stats :in-flight] (fnil dec 0)))))))

(defn record-send-error
  "Updates global stats for messages"
  [tal-state id msg error]
  (dosync
   (commute tal-state (fn [s]
                        (-> s
                          (utils/update-when-in [:connections id] (fn [info] (-> info
                                                                               (assoc :last-send-error error)
                                                                               (update-in [:send-error-count] (fnil inc 0))
                                                                               (update-in [:in-flight] (fnil dec 0)))))
                          (assoc-in [:stats :last-send-error] error)
                          (update-in [:stats :send-error-count] (fnil inc 0))
                          (update-in [:stats :in-flight] (fnil dec 0)))))))

(declare queue-msg!)

(defn setup-ping-job [tal-state ch-id]
  (delay/repeat-fn (:async-pool @tal-state)
                   (long (/ (:ping-ms @tal-state) 2))
                   #(when-let [ch-info (get-channel-info tal-state ch-id)]
                      (when (and (empty? @(:send-queue ch-info))
                                 (or (nil? (:last-send-time ch-info))
                                     (< (/ (:ping-ms @tal-state) 2)
                                        (- (.getTime (java.util.Date.))
                                           (.getTime (:last-send-time ch-info))))))
                        (queue-msg! tal-state ch-id {:op :tal/ping})))))

(defn cancel-ping-job [tal-state ch-id]
  (some-> (get-channel-info tal-state ch-id) :ping-job (.cancel false)))

(defn handle-message [tal-state props]
  (.put (:recv-queue @tal-state) (assoc props :tal/state tal-state)))

(defn ws-open-handler [tal-state]
  (fn [ch]
    (let [id (ch-id ch)
          msg-ch (:msg-ch @tal-state)
          ping-job (setup-ping-job tal-state id)]
      (try
       (add-channel tal-state id ch ping-job)
       (catch java.lang.AssertionError e
         (.cancel ping-job false)
         (throw e)))
      (handle-message tal-state {:op :tal/channel-open
                                 :tal/ch ch
                                 :tal/ch-id id
                                 ;; for final release, this should be obtained by
                                 ;; passing msg into a fn
                                 :tal/ring-req (immutant/originating-request ch)}))))

(defn ws-error-handler [tal-state]
  (fn [ch throwable]
    (let [id (ch-id ch)]
      (log/errorf throwable "error for channel with id %s" id)
      (record-error tal-state id throwable))))

(defn remove-by-id [tal-state id data ring-req]
  (let [msg-ch (:msg-ch @tal-state)]
    (remove-channel tal-state id)
    (handle-message tal-state
                    {:op :tal/channel-close
                     :data data
                     :tal/ch-id id
                     ;; for final release, this should be obtained by
                     ;; passing msg into a fn
                     :tal/ring-req ring-req})))

(defn ws-close-handler [tal-state]
  (fn [ch {:keys [code reason] :as args}]
    (let [id (ch-id ch)]
      (log/infof "channel with id %s closed %s" id args)
      (cancel-ping-job tal-state id)
      (remove-by-id tal-state id args (immutant/originating-request ch)))))

(defn streamify [msg]
  (if (string? msg)
    (io/input-stream (.getBytes msg "UTF-8"))
    msg))

(defn decode-msg [msg]
  (-> msg
    (streamify)
    (transit/reader :json)
    (transit/read)))

(defn encode-msg [msg]
  (let [out (ByteArrayOutputStream. 4096)
        w (transit/writer out :json)]
    (transit/write w msg)
    (.toString out)))

(defn ws-msg-handler [tal-state]
  (fn [ch msg]
    (let [id (ch-id ch)
          msg-ch (:msg-ch @tal-state)
          messages (decode-msg msg)]
      (record-msg tal-state id msg)
      (doseq [msg messages]
        (handle-message tal-state (assoc msg
                                         :tal/ch ch
                                         :tal/ch-id id
                                         ;; for final release, this should be obtained by
                                         ;; passing msg into a fn
                                         :tal/ring-req (immutant/originating-request ch)))))))

(defn handle-ajax-msg [tal-state ch-id msg ring-req]
  (if-let [channel-info (get-channel-info tal-state ch-id)]
    (let [msg-ch (:msg-ch @tal-state)
          messages (decode-msg msg)]
      (record-msg tal-state ch-id msg)
      (doseq [msg messages]
        (handle-message tal-state (assoc msg
                                         :tal/ch (:channel channel-info)
                                         :tal/ch-id ch-id
                                         :tal/ring-req ring-req)))
      :sent)
    :channel-closed))

(defn schedule-ajax-cleanup [tal-state ch-id ring-req channel-count]
  (delay/delay-fn (:async-pool @tal-state)
                  5000
                  ;; remove the channel if it doesn't reconnect
                  #(when (= channel-count (get-in @tal-state [:connections ch-id :ajax-channel-count]))
                     (log/infof "channel with id %s closed" ch-id)
                     (cancel-ping-job tal-state ch-id)
                     (remove-by-id tal-state ch-id {} ring-req))))

(defn handle-ajax-open [tal-state ch-id ring-req]
  (let [msg-ch (:msg-ch @tal-state)]
    (let [ping-job (setup-ping-job tal-state ch-id)
          new-state (try (add-channel tal-state ch-id nil ping-job)
                         (catch java.lang.AssertionError e
                           (.cancel ping-job false)
                           (throw e)))
          ch-count (get-in new-state
                           [:connections ch-id :ajax-channel-count])]
      (schedule-ajax-cleanup tal-state ch-id ring-req ch-count))
    (handle-message tal-state {:op :tal/channel-open
                               :tal/ch nil
                               :tal/ch-id ch-id
                               ;; for final release, this should be obtained by
                               ;; passing msg into a fn
                               :tal/ring-req ring-req})))

(declare send-queued!)

(defn ajax-channel-handler [tal-state ch-id]
  (fn [ch]
    (let [msg-ch (:msg-ch @tal-state)
          new-state (add-ajax-channel tal-state ch-id ch)]
      (try
        (some-> new-state
          (get-in [:connections ch-id :previous-channel])
          (immutant/close))
        (catch java.lang.IllegalStateException e
          nil))
      (send-queued! tal-state ch-id))))

(defn ajax-close-handler [tal-state]
  (fn [ch {:keys [code reason] :as args}]
    (let [id (ch-id ch)
          msg-ch (:msg-ch @tal-state)]
      (let [new-state (remove-ajax-channel tal-state id ch)]
        (when-not (get-in new-state [:connections id :channel])
          (schedule-ajax-cleanup tal-state
                                 id
                                 (immutant/originating-request ch)
                                 (get-in new-state [:connections id :ajax-channel-count])))))))

(defn send! [tal-state ch-id msg & {:keys [on-success on-error]}]
  (assert (vector? msg))
  (if-let [ch (:channel (get-channel-info tal-state ch-id))]
    (let [res (immutant/send! ch
                              (encode-msg msg)
                              {:close? (ajax-channel? ch)
                               :on-success (fn []
                                             (record-send-success tal-state ch-id msg)
                                             (when (fn? on-success)
                                               (on-success)))
                               :on-error (fn [throwable]
                                           (log/errorf throwable "error sending message for channel with id %s" ch-id)
                                           (record-send-error tal-state ch-id msg throwable)
                                           (when (fn? on-error)
                                             (on-error throwable)))})]
      (if res
        (record-send tal-state ch-id msg)
        (when (fn? on-error)
          (on-error (Exception. "channel is closed"))))
      res)
    (when (fn? on-error)
      (on-error (Exception. "channel is closed"))
      nil)))

(defn pop-all [queue-atom]
  (loop [val @queue-atom]
    (if (compare-and-set! queue-atom val (empty val))
      val
      (recur @queue-atom))))

(defn combine-callbacks [callbacks]
  (reduce (fn [acc cb]
            (if (fn? cb)
              (if (fn? acc)
                (juxt acc cb)
                cb)
              acc))
          callbacks))

(defn send-queued! [tal-state ch-id]
  (let [ch-info (get-channel-info tal-state ch-id)]
    (when (:channel ch-info)
      (let [jobs (pop-all (:delay-jobs ch-info))
            messages (pop-all (:send-queue ch-info))]
        (doseq [job jobs]
          (.cancel job false))
        (when (seq messages)
          (send! tal-state ch-id (mapv :msg messages)
                 :on-success (combine-callbacks (map :on-success messages))
                 :on-error (combine-callbacks (map :on-error messages))))))))

(defn schedule-send [tal-state ch-id delay-ms]
  (let [job (delay/delay-fn (:async-pool @tal-state)
                            delay-ms
                            #(send-queued! tal-state ch-id))
        delay-jobs (get-in @tal-state [:connections ch-id :delay-jobs])]
    (when delay-jobs
      (swap! delay-jobs conj job))))

(defn queue-msg! [tal-state ch-id msg & {:keys [on-success on-error]}]
  (when-let [channel-info (get-channel-info tal-state ch-id)]
    (swap! (:send-queue channel-info) conj {:msg msg
                                            :on-success on-success
                                            :on-error on-error})
    (schedule-send tal-state ch-id (:send-delay channel-info))))

;; debug methods
(defn send-all [tal-state msg]
  (doseq [[id _] (:connections @tal-state)]
    (send! tal-state id msg)))

(defn all-channels [tal-state]
  (map (comp :channel second) (:connections @tal-state)))

(defn all-ch-ids [tal-state]
  (keys (:connections @tal-state)))

(defn closed-channels []
  (remove immutant/open? (all-channels talaria-state)))

(defn clear-closed []
  (let [close-fn (ws-close-handler talaria-state)]
    (doseq [ch (remove ajax-channel? (closed-channels))]
      (close-fn ch {}))))

