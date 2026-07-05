(ns hledger-immutable.db.table.workspace-projection-state
  (:require [hugsql.core :as hugsql]
            [pod.babashka.go-sqlite3 :as sqlite]))

(hugsql/def-sqlvec-fns "workspace_projection_state.sql")

(defn checkpoint
  [conn]
  (some-> (first (sqlite/query conn
                               (latest-sequence-number-sqlvec)))
          :last_projected_datom_sequence_number))

(defn last-projected-datom-sequence-number
  [conn]
  (checkpoint conn))

(defn save-checkpoint!
  [conn last-projected-datom-sequence-number]
  (when (some? last-projected-datom-sequence-number)
    (sqlite/execute!
     conn
     (save-latest-sequence-number-sqlvec
      {:latest-sequence-number
       last-projected-datom-sequence-number}))))

(defn save-last-projected-datom-sequence-number!
  [conn last-projected-datom-sequence-number]
  (save-checkpoint! conn last-projected-datom-sequence-number))
