(ns hledger-immutable.cli-test
  (:require [babashka.cli :as bb-cli]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hledger-immutable.cli :as cli])
  (:import [java.nio.file Files]))

(defn- temp-directory []
  (.toFile (Files/createTempDirectory
            "hledger-immutable-cli-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- dispatch
  [& args]
  (bb-cli/dispatch @#'hledger-immutable.cli/dispatch-table args))

(defn- command-definition
  [cmd]
  (first (filter #(= [cmd] (:cmds %))
                 @#'hledger-immutable.cli/command-definitions)))

(def all-commands
  ["init" "add-commodity" "add-price" "add-account" "add-transaction"
   "add-budget" "add-periodic-transaction" "add-include" "add-alias"
   "add-decimal-mark" "add-default-commodity" "add-payee"
   "add-tag-declaration" "add-tag" "update-entity" "delete-entity"
   "append-datoms" "project" "read-journal" "explain-hledger-error" "status"])

(deftest cli-dispatches-init-add-status-and-project
  (let [workspace (temp-directory)]
    (is (re-find #":db"
                 (with-out-str
                   (cli/-main "init" "-w" (.getPath workspace)))))
    (is (re-find #":eid 1"
                 (with-out-str
                   (cli/-main "add-commodity"
                              "-w" (.getPath workspace)
                              "-d" "{\"file\":\"main.journal\",\"name\":\"EUR\"}"
                              "--no-project"))))
    (is (not (.exists (io/file workspace "main.journal"))))
    (is (re-find #":pending-datom-count 4"
                 (with-out-str
                   (cli/-main "status" "-w" (.getPath workspace)))))
    (is (re-find #":status :projected"
                 (with-out-str
                   (cli/-main "project" "-w" (.getPath workspace)))))
    (is (.exists (io/file workspace "main.journal")))
    (let [page (json/parse-string
                (with-out-str
                  (cli/-main "read-journal"
                             "-w" (.getPath workspace)
                             "-j" "main.journal"
                             "--limit" "1"
                             "--after-position" "0")))]
      (is (= {"journal" "main.journal"
              "limit" 1
              "after_position" 0
              "top_level_entity_count" 1
              "has_more" false
              "last_entity_position" 1000}
             (dissoc page "entities")))
      (is (= 1 (get-in page ["entities" 0 "eid"]))))))

(deftest cli-usage-is-generated-from-command-definitions
  (is (= (str "Usage:\n"
              "  hledger-immutable init -w <workspace>\n"
              "  hledger-immutable add-commodity -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-price -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-account -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-transaction -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-budget -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-periodic-transaction -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-include -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-alias -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-decimal-mark -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-default-commodity -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-payee -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-tag-declaration -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable add-tag -w <workspace> -peid <parent-entity-id> --key <key> [--value <value>] [--no-project]\n"
              "  hledger-immutable update-entity -w <workspace> -eid <entity-id> -d <json> [--no-project]\n"
              "  hledger-immutable delete-entity -w <workspace> -eid <entity-id> [--no-project]\n"
              "  hledger-immutable append-datoms -w <workspace> -d <json> [--no-project]\n"
              "  hledger-immutable project -w <workspace> [--upto <sequence>]\n"
              "  hledger-immutable read-journal -w <workspace> -j <journal> [--limit <count>] [--after-position <position>]\n"
              "  hledger-immutable explain-hledger-error -w <workspace> -d <json>\n"
              "  hledger-immutable status -w <workspace>\n"
              "\n"
              "Run `hledger-immutable <command> -h` or `hledger-immutable <command> --help` for command documentation and examples.\n")
         (with-out-str
           (cli/-main)))))

(deftest each-command-has-short-and-long-help
  (doseq [command all-commands]
    (doseq [flag ["-h" "--help"]]
      (let [help (with-out-str
                   (cli/-main command flag))]
        (is (str/includes? help (str "Command: hledger-immutable " command)))
        (is (str/includes? help "Usage:"))
        (is (str/includes? help "Options:"))
        (is (str/includes? help "-h, --help"))
        (is (str/includes? help "Examples:"))))))

(deftest cli-project-upto-projects-into-projections
  (let [workspace (temp-directory)]
    (with-out-str
      (cli/-main "init" "-w" (.getPath workspace)))
    (with-out-str
      (cli/-main "add-commodity" "-w" (.getPath workspace)
                 "-d" "{\"file\":\"main.journal\",\"name\":\"EUR\"}"))
    (is (re-find #":upto 4"
                 (with-out-str
                   (cli/-main "project" "-w" (.getPath workspace) "--upto" "4"))))
    (is (.exists (io/file workspace ".projections" "workspace-4" "main.journal")))
    ;; The live workspace journal is untouched by the upto projection.
    (is (.exists (io/file workspace "main.journal")))))

(deftest cli-read-journal-rejects-unsafe-journal-filenames
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"local .journal filename"
       (dispatch "read-journal"
                 "-w" "."
                 "-j" "../main.journal"
                 "--limit" "10"))))

(deftest command-local-options-reject-irrelevant-flags
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unknown option for command"
       (dispatch "status" "-w" "./books" "--data" "{}")))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unknown option for command"
       (dispatch "read-journal"
                 "-w" "./books"
                 "-j" "main.journal"
                 "--upto" "4"))))

(deftest command-local-options-still-parse-negated-project-and-coerced-eid
  (let [workspace (temp-directory)]
    (with-out-str
      (dispatch "init" "-w" (.getPath workspace)))
    (is (re-find #":eid 1"
                 (with-out-str
                   (dispatch "add-commodity"
                             "-w" (.getPath workspace)
                             "-d" "{\"file\":\"main.journal\",\"name\":\"EUR\"}"
                             "--no-project"))))
    (is (re-find #":eid 1"
                 (with-out-str
                   (dispatch "update-entity"
                             "-w" (.getPath workspace)
                             "-eid" "1"
                             "-d" "{\"set\":{\"commodity/name\":\"USD\"}}"
                             "--no-project")))))
  (let [definition (command-definition "update-entity")
        opts (bb-cli/parse-opts ["-w" "."
                                 "-eid" "1"
                                 "-d" "{\"set\":{\"commodity/name\":\"USD\"}}"]
                                {:spec (#'hledger-immutable.cli/command-spec
                                        definition)})
        normalized (#'hledger-immutable.cli/normalize-options definition opts)]
    (is (= 1 (:entity-id normalized)))
    (is (integer? (:entity-id normalized)))))

(deftest cli-add-tag-parses-parent-eid-key-value-and-no-project
  (let [definition (command-definition "add-tag")
        opts (bb-cli/parse-opts ["-w" "."
                                 "-peid" "3"
                                 "--key" "project"
                                 "--value" "ops"
                                 "--no-project"]
                                {:spec (#'hledger-immutable.cli/command-spec
                                        definition)})
        normalized (#'hledger-immutable.cli/normalize-options definition opts)]
    (is (= 3 (:parent-entity-id normalized)))
    (is (integer? (:parent-entity-id normalized)))
    (is (= "project" (:key normalized)))
    (is (= "ops" (:value normalized)))
    (is (false? (:project normalized)))))

(deftest cli-append-datoms-accepts-json-array-data
  (let [workspace (temp-directory)]
    (with-out-str
      (cli/-main "init" "-w" (.getPath workspace)))
    (with-out-str
      (cli/-main "add-commodity"
                 "-w" (.getPath workspace)
                 "-d" "{\"file\":\"main.journal\",\"name\":\"EUR\"}"
                 "--no-project"))
    (is (re-find #":datom-count 1"
                 (with-out-str
                   (cli/-main "append-datoms"
                              "-w" (.getPath workspace)
                              "-d" "[[1,\"commodity/name\",\"USD\",false]]"
                              "--no-project"))))))

(deftest cli-explain-hledger-error-prints-json-diagnostic
  (let [workspace (temp-directory)
        output (with-out-str
                 (cli/-main "explain-hledger-error"
                            "-w" (.getPath workspace)
                            "-d" "{\"stderr\":\"not a known hledger error\"}"))
        result (json/parse-string output)]
    (is (= "unknown-hledger-error" (get result "kind")))
    (is (= "not a known hledger error" (get result "stderr")))))
