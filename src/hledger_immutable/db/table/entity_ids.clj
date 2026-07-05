(ns hledger-immutable.db.table.entity-ids
  (:require [hugsql.core :as hugsql]
            [pod.babashka.go-sqlite3 :as sqlite]))

(hugsql/def-sqlvec-fns "entity_ids.sql")

(defn allocate-eid!
  [conn]
  (:eid (first (sqlite/query conn (allocate-entity-id-sqlvec)))))

(defn eid-exists?
  [conn eid]
  (boolean
   (seq (sqlite/query conn (eid-exists?-sqlvec {:eid eid})))))
