(ns pc.replay
  (:require [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.datomic.web-peer :as web-peer])
  (:import java.util.UUID))

(defn- ->datom
  [[e a v tx added]]
  {:e e :a a :v v :tx tx :added added})

(defn tx-data [transaction]
  (->> (d/q '{:find [?e ?a ?v ?tx ?op]
              :in [?log ?txid]
              :where [[(tx-data ?log ?txid) [[?e ?a ?v ?tx ?op]]]]}
            (d/log (pcd/conn)) (:db/id transaction))
    (map ->datom)
    set))

(defn get-document-tx-ids
  "Returns array of tx-ids sorted by db/txInstant"
  [db doc]
  (map first
       (sort-by second
                (d/q '{:find [?t ?tx]
                       :in [$ ?doc-id]
                       :where [[?t :transaction/document ?doc-id]
                               [?t :transaction/broadcast]
                               [?t :db/txInstant ?tx]]}
                     db (:db/id doc)))))

(defn reproduce-transaction [db tx-id]
  (let [tx (d/entity db tx-id)]
    {:tx-data (tx-data tx)
     :db-after (d/as-of db (:db/txInstant tx))}))

(defn get-document-transactions
  "Returns a lazy sequence of transactions for a document in order of db/txInstant.
   Has :tx-data and :db-after fields"
  [db doc]
  (map (partial reproduce-transaction db)
       (get-document-tx-ids db doc)))

(defn replace-frontend-ids [db doc-id txes]
  (let [a (d/entid db :frontend/id)]
    (map (fn [tx]
           (if (= (:a tx) a)
             (assoc tx
                    :v (UUID. doc-id (web-peer/client-part (:v tx)))
                    :a (d/entid (pcd/default-db) :frontend/id))
             tx))
         txes)))



(defn copy-transactions [db doc new-doc & {:keys [sleep-ms]
                                           :or {sleep-ms 1000}}]
  (let [conn (pcd/conn)
        tx-datas (->> (get-document-transactions db doc)
                   (map (fn [t]
                          (->> (:tx-data t)
                            (remove #(= (:e %) (:db/id t)))
                            (map #(if (= (:v %) (:db/id doc))
                                    (assoc % :v (:db/id new-doc))
                                    %))
                            (replace-frontend-ids db (:db/id new-doc))))))
        eid-translations (-> (apply concat (map #(map :e %) tx-datas))
                           set
                           (disj (:db/id doc))
                           (zipmap (repeatedly #(d/tempid :db.part/user)))
                           (assoc (:db/id doc) (:db/id new-doc)))]
    (doseq [tx-data tx-datas]
      (def my-tx-data tx-data)
      (let [txid (d/tempid :db.part/tx)]
        @(d/transact conn (conj (map #(-> %
                                        (update-in [:e] eid-translations)
                                        pcd/datom->transaction)
                                     tx-data)
                                {:db/id txid
                                 :transaction/document (:db/id new-doc)
                                 :transaction/broadcast true}))
        (Thread/sleep sleep-ms)))))
