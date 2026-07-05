(ns hledger-immutable.db-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hledger-immutable.db.connection :as connection]
            [hledger-immutable.db.migrations :as migrations]
            [hledger-immutable.db.table.entity-ids :as entity-ids]
            [hledger-immutable.db.table.event-log :as event-log-table]
            [hledger-immutable.mutation :as mutation]
            [hledger-immutable.projector :as projector]
            [hledger-immutable.query :as query]
            [pod.babashka.go-sqlite3 :as sqlite])
  (:import [java.nio.file Files]))

(defn- temp-directory []
  (.toFile (Files/createTempDirectory
            "hledger-immutable-db-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- migration-ids
  [workspace]
  (connection/with-connection
    workspace
    (fn [conn]
      (mapv :id (sqlite/query conn ["SELECT id FROM migrations ORDER BY id"])))))

(defn- pragma-value
  [conn pragma-name]
  (-> (sqlite/query conn [(str "PRAGMA " pragma-name)])
      first
      first
      val))

(defn- current-position
  [workspace eid position-attr]
  (connection/with-connection
    workspace
    (fn [conn]
      (get-in (query/current-entity conn eid) [:attributes position-attr]))))

(defn- current-datoms
  [workspace eid]
  (connection/with-connection
    workspace
    (fn [conn]
      (event-log-table/datoms-for-eid conn eid))))

(defn- current-attributes
  [workspace eid]
  (connection/with-connection
    workspace
    (fn [conn]
      (get (query/current-entity conn eid) :attributes))))

(deftest init-creates-the-workspace-database-and-applies-migrations-once
  (let [workspace (temp-directory)]
    (projector/init! workspace)
    (is (.exists (io/file workspace connection/db-filename)))
    (is (= "immutable.sqlite" connection/db-filename))
    (is (= ["001-create-workspace-projection-state"
            "002-create-entity-ids"
            "003-create-event-log"
            "004-index-event-log-by-eid"
            "005-index-event-log-by-attribute-value"]
           (migration-ids workspace)))
    (projector/init! workspace)
    (is (= ["001-create-workspace-projection-state"
            "002-create-entity-ids"
            "003-create-event-log"
            "004-index-event-log-by-eid"
            "005-index-event-log-by-attribute-value"]
           (migration-ids workspace)))))

(deftest event-log-entity-history-uses-the-eid-index
  (let [workspace (temp-directory)]
    (projector/init! workspace)
    (connection/with-connection
      workspace
      (fn [conn]
        (let [query-plan (sqlite/query
                          conn
                          ["EXPLAIN QUERY PLAN
                            SELECT sequence, eid, attr, value_json, retract
                            FROM event_log
                            WHERE eid = ?
                            ORDER BY sequence"
                           1])]
          (is (some #(re-find #"event_log_eid_idx" (:detail %))
                    query-plan)))))))

(deftest connections-use-wal-and-local-safety-defaults
  (let [workspace (temp-directory)]
    (projector/init! workspace)
    (connection/with-connection
      workspace
      (fn [conn]
        (is (= "wal" (pragma-value conn "journal_mode")))
        (is (= 5000 (pragma-value conn "busy_timeout")))
        (is (= 1 (pragma-value conn "foreign_keys")))
        (is (= 1 (pragma-value conn "synchronous")))))))

(deftest migrations-refuse-a-database-from-a-newer-cli-version
  (let [workspace (temp-directory)]
    (projector/init! workspace)
    (connection/with-connection
      workspace
      (fn [conn]
        (sqlite/execute! conn
                         ["INSERT INTO migrations (id) VALUES (?)"
                          "999-future-migration"])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown to this CLI"
         (projector/status workspace)))))

(deftest migration-application-is-atomic
  (let [workspace (temp-directory)]
    (is (thrown?
         Throwable
         (connection/with-connection
           workspace
           (fn [conn]
             (with-redefs [migrations/migrations
                           [{:id "001-valid"
                             :sqlvec (fn [] ["CREATE TABLE valid_migration (id INTEGER)"])}
                            {:id "002-invalid"
                             :sqlvec (fn [] ["INVALID SQL"])}]]
               (migrations/ensure! conn))))))
    (connection/with-connection
      workspace
      (fn [conn]
        (is (empty? (sqlite/query
                     conn
                     ["SELECT name
                       FROM sqlite_master
                       WHERE type = 'table'
                         AND name IN ('migrations', 'valid_migration')"])))))))

(deftest add-projects-immediately-by-default
  (let [workspace (temp-directory)
        result (mutation/add-commodity! {:workspace workspace
                                         :data {:file "main.journal"
                                                :name "EUR"}
                                         :project? true})]
    (is (= 1 (:eid result)))
    (is (= {:status :projected
            :last-projected-datom-sequence-number 4
            :projected-count 4}
           (:projection result)))
    (is (= (str "; __eid: 1 __meta: {\"entity/position\" 1000}\n"
                "commodity EUR\n")
           (slurp (io/file workspace "main.journal"))))
    (is (= 0 (:pending-datom-count (projector/status workspace))))))

(deftest event-log-and-projection-timestamps-use-unix-epoch-seconds
  (let [workspace (temp-directory)
        before (quot (System/currentTimeMillis) 1000)]
    (mutation/add-commodity! {:workspace workspace
                              :data {:file "main.journal"
                                     :name "EUR"}
                              :project? true})
    (let [after (quot (System/currentTimeMillis) 1000)]
      (connection/with-connection
        workspace
        (fn [conn]
          (let [event-row (first
                           (sqlite/query
                            conn
                            ["SELECT created_at, typeof(created_at) AS timestamp_type
                              FROM event_log
                              ORDER BY sequence
                              LIMIT 1"]))
                projection-row (first
                                (sqlite/query
                                 conn
                                 ["SELECT projected_at,
                                          typeof(projected_at) AS timestamp_type
                                   FROM workspace_projection_state"]))]
            (is (= "integer" (:timestamp_type event-row)))
            (is (<= before (:created_at event-row) after))
            (is (= "integer" (:timestamp_type projection-row)))
            (is (<= before (:projected_at projection-row) after))))))))

(deftest no-project-leaves-pending-datoms-and-project-catches-up
  (let [workspace (temp-directory)]
    (mutation/add-commodity! {:workspace workspace
                              :data {:file "main.journal"
                                     :name "INR"}
                              :project? false})
    (is (not (.exists (io/file workspace "main.journal"))))
    (is (= 4 (:pending-datom-count (projector/status workspace))))
    (is (= {:status :projected
            :last-projected-datom-sequence-number 4
            :projected-count 4}
           (projector/project! workspace)))
    (is (= {:status :up-to-date
            :last-projected-datom-sequence-number 4
            :projected-count 0}
           (projector/project! workspace)))))

(deftest failed-projection-does-not-advance-the-last-projected-datom-sequence-number
  (let [workspace (temp-directory)
        eid (connection/with-connection
              workspace
              (fn [conn]
                (migrations/ensure! conn)
                (entity-ids/allocate-eid! conn)))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"incomplete entity"
         (mutation/append-datoms!
          {:workspace workspace
           :datoms [[eid "entity/type" "price" false]
                    [eid "entity/file" "main.journal" false]
                    [eid "entity/position" 1000 false]
                    [eid "price/date" "2026-06-23" false]]
           :project? true})))
    (let [status (projector/status workspace)]
      (is (nil? (:last-projected-datom-sequence-number status)))
      (is (= 4 (:pending-datom-count status))))))

(deftest amend-and-delete-append-datoms-and-project-incrementally
  (let [workspace (temp-directory)]
    (mutation/add-commodity! {:workspace workspace
                              :data {:file "main.journal"
                                     :name "EUR"}
                              :project? true})
    (is (= {:eid 1
            :datom-count 2
            :projection {:status :projected
                         :last-projected-datom-sequence-number 6
                         :projected-count 2}}
           (mutation/update-entity! {:workspace workspace
                                     :eid 1
                                     :data {"set" {"commodity/name" "USD"}}
                                     :project? true})))
    (is (= (str "; __eid: 1 __meta: {\"entity/position\" 1000}\n"
                "commodity USD\n")
           (slurp (io/file workspace "main.journal"))))
    (is (= {:deleted-eids '(1)
            :datom-count 4
            :projection {:status :projected
                         :last-projected-datom-sequence-number 10
                         :projected-count 4}}
           (mutation/delete-entity! {:workspace workspace
                                     :eid 1
                                     :project? true})))
    (is (not (.exists (io/file workspace "main.journal"))))))

(deftest amend-and-delete-read-only-the-target-entity-history
  (let [workspace (temp-directory)
        target-eid (:eid (mutation/add-commodity! {:workspace workspace
                                                   :data {:file "main.journal"
                                                          :name "EUR"}
                                                   :project? false}))
        unrelated-eid (:eid (mutation/add-commodity! {:workspace workspace
                                                      :data {:file "main.journal"
                                                             :name "USD"}
                                                      :project? false}))]
    (connection/with-connection
      workspace
      (fn [conn]
        (sqlite/execute!
         conn
         ["UPDATE event_log
           SET value_json = 'invalid-json'
           WHERE eid = ?
             AND attr = 'commodity/name'"
          unrelated-eid])))
    (is (= 2 (:datom-count
              (mutation/update-entity! {:workspace workspace
                                        :eid target-eid
                                        :data {"set" {"commodity/name" "GBP"}}
                                        :project? false}))))
    (is (= 4 (:datom-count
              (mutation/delete-entity! {:workspace workspace
                                        :eid target-eid
                                        :project? false}))))))

(deftest add-entities-support-after-and-before-eid-placement
  (let [workspace (temp-directory)
        eur-eid (:eid (mutation/add-commodity!
                       {:workspace workspace
                        :data {:file "main.journal" :name "EUR"}
                        :project? false}))
        usd-eid (:eid (mutation/add-commodity!
                       {:workspace workspace
                        :data {:file "main.journal"
                               :name "USD"
                               :after_eid eur-eid}
                        :project? false}))
        gbp-eid (:eid (mutation/add-commodity!
                       {:workspace workspace
                        :data {:file "main.journal"
                               :name "GBP"
                               :before_eid usd-eid}
                        :project? true}))]
    (is (= 1000 (current-position workspace eur-eid "entity/position")))
    (is (= 1090 (current-position workspace gbp-eid "entity/position")))
    (is (= 1900 (current-position workspace usd-eid "entity/position")))
    (is (re-find #"commodity EUR\n[\s\S]*commodity GBP\n[\s\S]*commodity USD"
                 (slurp (io/file workspace "main.journal"))))))

(deftest update-entity-rebalances-crowded-gap-with-position-datoms
  (let [workspace (temp-directory)
        anchor-eid (:eid (mutation/add-commodity!
                          {:workspace workspace
                           :data {:file "main.journal" :name "ANCHOR"}
                           :project? false}))
        crowded-eid (:eid (mutation/add-commodity!
                           {:workspace workspace
                            :data {:file "main.journal" :name "CROWDED"}
                            :project? false}))
        closing-eid (:eid (mutation/add-commodity!
                           {:workspace workspace
                            :data {:file "main.journal" :name "CLOSING"}
                            :project? false}))
        moved-eid (:eid (mutation/add-commodity!
                         {:workspace workspace
                          :data {:file "main.journal" :name "MOVED"}
                          :project? false}))]
    (mutation/update-entity!
     {:workspace workspace
      :eid crowded-eid
      :data {"set" {"entity/position" 1001}}
      :project? false})
    (mutation/update-entity!
     {:workspace workspace
      :eid closing-eid
      :data {"set" {"entity/position" 1999}}
      :project? false})
    (let [result (mutation/update-entity!
                  {:workspace workspace
                   :eid moved-eid
                   :data {"after_eid" anchor-eid}
                   :project? true})]
      (is (= 4 (:datom-count result)))
      (is (= 1333 (current-position workspace moved-eid "entity/position")))
      (is (= 1666 (current-position workspace crowded-eid "entity/position")))
      (is (= 1999 (current-position workspace closing-eid "entity/position")))
      (is (some #(= [crowded-eid "entity/position" 1001 true]
                    (subvec (vec %) 1))
                (current-datoms workspace crowded-eid)))
      (is (some #(= [crowded-eid "entity/position" 1666 false]
                    (subvec (vec %) 1))
                (current-datoms workspace crowded-eid)))
      (is (re-find #"commodity ANCHOR\n[\s\S]*commodity MOVED\n[\s\S]*commodity CROWDED\n[\s\S]*commodity CLOSING"
                   (slurp (io/file workspace "main.journal")))))))

(deftest mutation-inputs-reject-after-position
  (let [workspace (temp-directory)
        eid (:eid (mutation/add-commodity!
                   {:workspace workspace
                    :data {:file "main.journal" :name "EUR"}
                    :project? false}))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"after_position is not supported"
         (mutation/add-commodity!
          {:workspace workspace
           :data {:file "main.journal"
                  :name "USD"
                  :after_position 1000}
           :project? false})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"after_position is not supported"
         (mutation/update-entity!
          {:workspace workspace
           :eid eid
           :data {"after_position" 1000}
           :project? false})))))

(deftest add-tag-adds-a-child-tag-to-a-top-level-entity
  (let [workspace (temp-directory)
        commodity-eid (:eid (mutation/add-commodity!
                             {:workspace workspace
                              :data {:file "main.journal" :name "EUR"}
                              :project? false}))
        tag-eid (:eid (mutation/add-tag!
                       {:workspace workspace
                        :data {:parent-eid commodity-eid
                               :key "project"
                               :value "ops"}
                        :project? false}))
        attrs (current-attributes workspace tag-eid)]
    (is (= {"entity/type" "tag"
            "tag/parent-eid" commodity-eid
            "tag/position" 2000
            "tag/name" "project"
            "tag/value" "ops"}
           attrs))))

(deftest add-tag-adds-a-child-tag-to-a-posting
  (let [workspace (temp-directory)
        result (mutation/add-transaction!
                {:workspace workspace
                 :data {:file "main.journal"
                        :date "2026-07-05"
                        :description "Lunch"
                        :postings [{:account "expenses:food" :amount "INR 500"}
                                   {:account "liabilities:card"}]}
                 :project? false})
        posting-eid (first (:posting-eids result))
        tag-eid (:eid (mutation/add-tag!
                       {:workspace workspace
                        :data {:parent-eid posting-eid
                               :key "meal"}
                        :project? false}))
        attrs (current-attributes workspace tag-eid)]
    (is (= "tag" (get attrs "entity/type")))
    (is (= posting-eid (get attrs "tag/parent-eid")))
    (is (= "meal" (get attrs "tag/name")))
    (is (not (contains? attrs "tag/value")))))

(deftest add-tag-rejects-unknown-or-incompatible-parent
  (let [workspace (temp-directory)
        commodity-eid (:eid (mutation/add-commodity!
                             {:workspace workspace
                              :data {:file "main.journal" :name "EUR"}
                              :project? false}))
        tag-eid (:eid (mutation/add-tag!
                       {:workspace workspace
                        :data {:parent-eid commodity-eid
                               :key "project"}
                        :project? false}))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown parent eid"
         (mutation/add-tag!
          {:workspace workspace
           :data {:parent-eid 9999
                  :key "missing"}
           :project? false})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"incompatible parent entity"
         (mutation/add-tag!
          {:workspace workspace
           :data {:parent-eid tag-eid
                  :key "nested"}
           :project? false})))))
