(ns hledger-immutable.db.migrations
  (:require [hledger-immutable.db.connection :as connection]
            [hugsql.core :as hugsql]
            [pod.babashka.go-sqlite3 :as sqlite]))

(hugsql/def-sqlvec-fns "migrations.sql")

(def migration-sqlvec-fns
  (hugsql/map-of-sqlvec-fns "migrations.sql"))

(def migrations
  (->> migration-sqlvec-fns
       (keep (fn [[sqlvec-name sqlvec-definition]]
               (when-let [[_ id] (re-matches #"migration-(.+)-sqlvec"
                                             (name sqlvec-name))]
                 {:id id
                  :sqlvec (:fn sqlvec-definition)})))
       (sort-by :id)
       vec))

(defn- applied-ids
  [conn]
  (set (map :id
            (sqlite/query
             conn
             (applied-migration-ids-sqlvec)))))

(defn ensure!
  [conn]
  (connection/with-transaction
    conn
    #(do
       (sqlite/execute! conn (create-migrations-table-sqlvec))
       (let [known-ids (set (map :id migrations))
             applied-ids (applied-ids conn)
             unknown-ids (seq (sort (remove known-ids applied-ids)))]
         (when unknown-ids
           (throw (ex-info "Database contains migrations unknown to this CLI version"
                           {:unknown-migration-ids (vec unknown-ids)})))
         (doseq [{:keys [id sqlvec]} migrations
                 :when (not (contains? applied-ids id))]
           (sqlite/execute! conn (sqlvec))
           (sqlite/execute!
            conn
            (record-migration-sqlvec {:id id})))))))
