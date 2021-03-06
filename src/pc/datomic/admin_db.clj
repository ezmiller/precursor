(ns pc.datomic.admin-db
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [pc.datomic :as pcd]
            [pc.datomic.schema :as schema]
            [pc.profile]))

(defn admin-uri []
  (pc.profile/admin-datomic-uri))

(defn admin-conn [& {:keys [uri]}]
  (d/connect (or uri (admin-uri))))

(defn admin-db []
  (d/db (admin-conn)))

(defn admin-schema []
  [(schema/attribute :admin/email
                     :db.type/string
                     :db/index true
                     :db/doc "Admin email")
   (schema/attribute :google-account/sub
                     :db.type/string
                     :db/unique :db.unique/value
                     :db/doc "Account id unique across Google: https://developers.google.com/accounts/docs/OAuth2Login")
   (schema/attribute :admin/http-session-key
                     :db.type/uuid
                     :db/index true
                     :db/doc "Session key stored in the cookie that is used to find the user")])

(defn ensure-admins []
  (doseq [admin (pc.profile/admins)]
    (when (empty? (d/datoms (admin-db) :avet :google-account/sub (:google-account/sub admin)))
      @(d/transact (admin-conn) [(merge admin {:db/id (d/tempid :db.part/user)})]))))

(defn ensure-connection []
  (log/infof "Creating admin database if it doesn't exist: %s"
             (d/create-database (admin-uri)))
  (log/infof "Connected to admin db: %s" (admin-conn)))

(defn ensure-schema []
  @(d/transact (admin-conn) (admin-schema)))

(defn init []
  (ensure-connection)
  (ensure-schema)
  (ensure-admins))
