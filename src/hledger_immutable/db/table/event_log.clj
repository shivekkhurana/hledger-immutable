(ns hledger-immutable.db.table.event-log
  (:require [cheshire.core :as json]
            [hugsql.core :as hugsql]
            [pod.babashka.go-sqlite3 :as sqlite]))

(hugsql/def-sqlvec-fns "event_log.sql")

(defn- row->datom
  [{:keys [sequence eid attr value_json retract]}]
  [sequence eid attr (json/parse-string value_json) (not (zero? retract))])

(defn datoms-for-eid
  [conn eid]
  (mapv row->datom
        (sqlite/query conn
                      (event-log-for-entity-sqlvec {:eid eid}))))

(defn eids-for-attribute-value
  [conn attr value]
  (mapv :eid
        (sqlite/query
         conn
         (event-log-eids-for-attribute-value-sqlvec
          {:attr attr
           :value-json (json/generate-string value)}))))

(defn eids-for-attribute
  [conn attr]
  (mapv :eid
        (sqlite/query
         conn
         (event-log-eids-for-attribute-sqlvec
          {:attr attr}))))

(defn pending-datoms
  [conn last-projected-datom-sequence-number upto]
  (let [params {:last-projected-datom-sequence-number
                (or last-projected-datom-sequence-number -1)}]
    (mapv row->datom
          (sqlite/query conn
                        (if upto
                          (event-log-through-sqlvec
                           (assoc params :upto upto))
                          (event-log-after-sqlvec params))))))

(defn append-datoms!
  [conn authored-datoms]
  (when (seq authored-datoms)
    (sqlite/execute!
     conn
     (insert-datoms-sqlvec
      {:datoms
       (mapv (fn [[eid attr value retract?]]
               [eid
                attr
                (json/generate-string value)
                (if retract? 1 0)])
             authored-datoms)}))))

(defn append-datom!
  [conn authored-datom]
  (append-datoms! conn [authored-datom]))

(defn latest-sequence-number
  [conn]
  (:sequence (first (sqlite/query conn
                                  (latest-event-log-sequence-sqlvec)))))

(defn pending-datom-count
  [conn last-projected-datom-sequence-number]
  (:count
   (first
    (sqlite/query
     conn
     (pending-event-log-count-sqlvec
      {:last-projected-datom-sequence-number
       (or last-projected-datom-sequence-number -1)})))))
