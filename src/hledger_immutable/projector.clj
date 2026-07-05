(ns hledger-immutable.projector
  "Checkpoint-aware projection orchestration for a workspace."
  (:require [clojure.java.io :as io]
            [hledger-immutable.db.connection :as connection]
            [hledger-immutable.db.migrations :as migrations]
            [hledger-immutable.db.table.event-log :as event-log-table]
            [hledger-immutable.db.table.workspace-projection-state
             :as workspace-projection-state]
            [hledger-immutable.projection :as projection]))

(def projections-directory-name ".projections")

(defn init!
  "Create or open a workspace database, apply migrations, and return its paths."
  [workspace]
  (connection/with-connection
    workspace
    (fn [conn]
      (migrations/ensure! conn)
      {:workspace (.getPath (connection/workspace-directory workspace))
       :db (connection/db-path workspace)})))

(defn- upto-directory
  "Return the destination directory for a projection as of `upto`."
  [workspace upto]
  (io/file (connection/workspace-directory workspace)
           projections-directory-name
           (str "workspace-" upto)))

(defn project-latest!
  "Project pending event-log datoms into the workspace and persist progress."
  [workspace]
  (connection/with-connection
    workspace
    (fn [conn]
      (migrations/ensure! conn)
      (let [last-projected-datom-sequence-number
            (workspace-projection-state/last-projected-datom-sequence-number
             conn)
            datoms
            (event-log-table/pending-datoms
             conn
             last-projected-datom-sequence-number
             nil)]
        (if (empty? datoms)
          {:status :up-to-date
           :last-projected-datom-sequence-number
           last-projected-datom-sequence-number
           :projected-count 0}
          (let [new-last-projected-datom-sequence-number
                (projection/project!
                 (connection/workspace-directory workspace)
                 last-projected-datom-sequence-number
                 datoms)]
            (workspace-projection-state/save-last-projected-datom-sequence-number!
             conn
             new-last-projected-datom-sequence-number)
            {:status :projected
             :last-projected-datom-sequence-number
             new-last-projected-datom-sequence-number
             :projected-count (count datoms)}))))))

(defn- project-upto!
  "Project the journals as of `upto` from scratch into `.projections/workspace-<upto>`."
  [workspace upto]
  (connection/with-connection
    workspace
    (fn [conn]
      (migrations/ensure! conn)
      (let [latest (event-log-table/latest-sequence-number conn)]
        (when (or (nil? latest) (not (pos? upto)) (> upto latest))
          (throw
           (ex-info
            "upto must be a positive sequence number not greater than the latest event-log sequence"
            {:upto upto :latest latest})))
        (let [datoms (event-log-table/pending-datoms conn nil upto)
              destination (upto-directory workspace upto)]
          (projection/project! destination
                               nil
                               datoms
                               {:ignore-incomplete-entities? true})
          {:status :projected
           :upto upto
           :destination (.getPath destination)
           :projected-count (count datoms)})))))

(defn project!
  "Project a workspace.

  With no `upto`, projects pending event-log datoms into the workspace
  directory and advances the saved last-projected-datom-sequence-number.

  With `upto`, projects the journals as of that sequence from scratch into
  `.projections/workspace-<upto>` without advancing the checkpoint."
  ([workspace]
   (project-latest! workspace))
  ([workspace upto]
   (if upto
     (project-upto! workspace upto)
     (project-latest! workspace))))

(defn status
  "Return event-log and projection progress for a workspace."
  [workspace]
  (connection/with-connection
    workspace
    (fn [conn]
      (migrations/ensure! conn)
      (let [last-projected-datom-sequence-number
            (workspace-projection-state/last-projected-datom-sequence-number
             conn)]
        {:workspace (.getPath (connection/workspace-directory workspace))
         :db (connection/db-path workspace)
         :latest-event-log-sequence-number
         (event-log-table/latest-sequence-number conn)
         :last-projected-datom-sequence-number
         last-projected-datom-sequence-number
         :pending-datom-count
         (event-log-table/pending-datom-count
          conn
          last-projected-datom-sequence-number)}))))
