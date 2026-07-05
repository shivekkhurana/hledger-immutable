(ns hledger-immutable.db.connection
  (:require [clojure.java.io :as io]
            [pod.babashka.go-sqlite3 :as sqlite]))

(def db-filename "immutable.sqlite")

(defn workspace-directory
  [workspace]
  (io/file workspace))

(defn db-path
  [workspace]
  (.getPath (io/file (workspace-directory workspace) db-filename)))

(defn with-transaction
  [conn f]
  (sqlite/execute! conn ["BEGIN IMMEDIATE"])
  (try
    (let [result (f)]
      (sqlite/execute! conn ["COMMIT"])
      result)
    (catch Throwable cause
      (sqlite/execute! conn ["ROLLBACK"])
      (throw cause))))

(defn- configure!
  [conn]
  (sqlite/query conn ["PRAGMA journal_mode = WAL"])
  (sqlite/execute! conn ["PRAGMA busy_timeout = 5000"])
  (sqlite/execute! conn ["PRAGMA foreign_keys = ON"])
  (sqlite/execute! conn ["PRAGMA synchronous = NORMAL"]))

(defn with-connection
  [workspace f]
  (.mkdirs (workspace-directory workspace))
  (let [conn (sqlite/get-connection (db-path workspace))]
    (try
      (configure! conn)
      (f conn)
      (finally
        (sqlite/close-connection conn)))))
