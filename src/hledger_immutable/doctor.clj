(ns hledger-immutable.doctor
  "Read-only diagnostics for explaining hledger errors in workspace terms."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hledger-immutable.byte-ops :as byte-ops]
            [hledger-immutable.entity :as entity]
            [hledger-immutable.event-log :as event-log]
            [hledger-immutable.schemas :as schemas]))

(def unbalanced-transaction-error-type "unbalanced-transaction")
(def unknown-hledger-error-type "unknown-hledger-error")

(def ^:private location-pattern #"(?m)^hledger: Error: (.+):(\d+)-(\d+):$")
(def ^:private residual-pattern
  #"(?m)^The real postings' sum should be 0 but is: (.+)$")

(defn- unknown-error
  [stderr]
  {:kind unknown-hledger-error-type
   :stderr stderr})

(defn- unbalanced-error?
  [stderr]
  (or (str/includes? stderr "This transaction is unbalanced.")
      (str/includes? stderr "This multi-commodity transaction is unbalanced.")))

(defn- parse-location
  [stderr]
  (when-let [[_ path line-start line-end] (re-find location-pattern stderr)]
    {:path path
     :line-start (Long/parseLong line-start)
     :line-end (Long/parseLong line-end)}))

(defn- parse-residual
  [stderr]
  (some-> (re-find residual-pattern stderr)
          second
          str/trim))

(defn- canonical-file
  [file]
  (.getCanonicalFile (io/file file)))

(defn- relative-journal
  [workspace-directory path]
  (let [workspace-file (canonical-file workspace-directory)
        journal-file (canonical-file path)
        workspace-path (.toPath workspace-file)
        journal-path (.toPath journal-file)]
    (when-not (.startsWith journal-path workspace-path)
      (throw (ex-info "Hledger error path must be inside the workspace-directory"
                      {:workspace-directory (.getPath workspace-file)
                       :journal-path (.getPath journal-file)})))
    (let [relative-path (str (.relativize workspace-path journal-path))]
      (event-log/validate-journal-filename relative-path)
      {:journal relative-path
       :journal-file journal-file})))

(defn- containing-entity-address
  [journal-file line-start]
  (or (byte-ops/find-entity-address-by-line journal-file line-start)
      (throw (ex-info "Cannot find an entity marker for hledger error location"
                      {:journal (.getPath journal-file)
                       :line-start line-start}))))

(defn- sorted-child-entities
  [entities parent-eid entity-type position-attribute]
  (->> entities
       vals
       (filter #(let [attributes (:attributes %)]
                  (and (= entity-type (get attributes "entity/type"))
                       (= parent-eid
                          (get attributes (schemas/parent-attribute entity-type))))))
       (sort-by (juxt #(get-in % [:attributes position-attribute]) :eid))
       vec))

(defn- tag-json
  [{:keys [eid attributes]}]
  {:eid eid
   :type (get attributes "entity/type")
   :position (get attributes "tag/position")
   :attributes attributes})

(defn- posting-json
  [entities {:keys [eid attributes]}]
  {:eid eid
   :type (get attributes "entity/type")
   :position (get attributes "posting/position")
   :account (get attributes "posting/account")
   :amount (get attributes "posting/amount")
   :attributes attributes
   :tags (mapv tag-json
               (sorted-child-entities entities
                                      eid
                                      schemas/tag-type
                                      "tag/position"))})

(defn- projected-entity-json
  [journal-file journal {:keys [eid entity-address-bytes]}]
  (let [{:keys [body metadata]} (byte-ops/read-entity journal-file entity-address-bytes)
        entities (entity/parse-entity eid body metadata)
        root (update-in (get entities eid)
                        [:attributes]
                        #(assoc %
                                "entity/file" journal
                                "entity/position"
                                (get metadata "entity/position")))]
    (when-not (entity/top-level-entity? root)
      (throw (ex-info "Hledger error location did not map to a top-level entity"
                      {:journal journal
                       :eid eid
                       :entity root})))
    {:root_entity {:eid eid
                   :type (get-in root [:attributes "entity/type"])
                   :file journal
                   :position (get-in root [:attributes "entity/position"])
                   :attributes (:attributes root)}
     :postings (mapv #(posting-json entities %)
                     (sorted-child-entities entities
                                            eid
                                            schemas/posting-type
                                            "posting/position"))
     :tags (mapv tag-json
                 (sorted-child-entities entities
                                        eid
                                        schemas/tag-type
                                        "tag/position"))}))

(defn- explain-unbalanced-transaction
  [workspace-directory stderr]
  (let [{:keys [path line-start line-end] :as location} (parse-location stderr)]
    (when-not location
      (throw (ex-info "Cannot parse hledger error location"
                      {:stderr stderr})))
    (let [{:keys [journal journal-file]} (relative-journal workspace-directory path)
          entity-address (containing-entity-address journal-file line-start)
          entity-context (projected-entity-json journal-file journal entity-address)]
      (merge
       {:kind unbalanced-transaction-error-type
        :journal journal
        :line_start line-start
        :line_end line-end
        :residual (parse-residual stderr)
        :multi_commodity (str/includes?
                          stderr
                          "This multi-commodity transaction is unbalanced.")
        :automatic_commodity_conversion_disabled
        (str/includes? stderr "Automatic commodity conversion is not enabled.")}
       entity-context))))

(defn explain-hledger-error
  "Explain hledger stderr using projected entity information from a workspace."
  [{:keys [workspace-directory stderr]}]
  (when-not (string? stderr)
    (throw (ex-info "explain-hledger-error requires string stderr"
                    {:stderr stderr})))
  (if (unbalanced-error? stderr)
    (explain-unbalanced-transaction workspace-directory stderr)
    (unknown-error stderr)))
