(ns hledger-immutable.projection
  (:require [clojure.java.io :as io]
            [hledger-immutable.byte-ops :as byte-ops]
            [hledger-immutable.entity :as entity]
            [hledger-immutable.event-log :as event-log]
            [hledger-immutable.schemas :as schemas]))

(defn- datom-eid
  "Extract the stable entity id from a sequenced datom."
  [[_ eid]]
  eid)

(defn- datom-entity-file
  "Return the asserted entity file from a datom, when it has one."
  [[_ _ attr value retract?]]
  (when (and (= "entity/file" attr) (not retract?))
    value))

(defn- journal-file?
  "Return true when a workspace child is a projected journal file."
  [file]
  (and (.isFile file)
       (.endsWith (.getName file) ".journal")))

(defn- journal-files
  "Return the projected journal files that currently exist in a workspace."
  [workspace-directory]
  (->> (or (seq (.listFiles workspace-directory)) [])
       (filter journal-file?)
       (sort-by #(.getName %))))

(defn- current-file-entities
  "Read every marked entity currently stored in one journal file."
  [journal-file]
  (let [filename (.getName journal-file)
        entity-address-bytes (byte-ops/find-all-entity-address-bytes journal-file)
        framed-entities (byte-ops/read-entities journal-file
                                                entity-address-bytes)]
    (->> entity-address-bytes
         (sort-by (comp :start val))
         (map-indexed
          (fn [index [eid entity-address-bytes]]
            (let [{:keys [body metadata]} (get framed-entities eid)
                  parsed-entities (entity/parse-entity eid body metadata)
                  root-position (get-in parsed-entities
                                        [eid :attributes "entity/position"])]
              (update-in parsed-entities
                         [eid :attributes]
                         #(assoc %
                                 "entity/file" filename
                                 "entity/position"
                                 (or root-position (* (inc index) 1000)))))))
         (apply merge))))

(defn- current-entities
  "Read the current projected entity state from the workspace-directory."
  [workspace-directory]
  (->> (journal-files workspace-directory)
       (map current-file-entities)
       (apply merge)))

(defn- affected-eids
  "Return event-log eids in first-appearance order."
  [datoms]
  (distinct (map datom-eid datoms)))

(defn- fold-affected-entities
  "Apply the event-log to current entity state, folding by eid before file."
  [{:keys [current-entities datoms] :as context}]
  (assoc context
         :desired-entities
         (entity/datoms->entities current-entities datoms)))

(defn- filter-projectable-entities
  "Attach folded entities eligible for rendering into projected journals."
  [{:keys [desired-entities ignore-incomplete-entities?] :as context}]
  (assoc context
         :projectable-entities
         (if ignore-incomplete-entities?
           (into {}
                 (filter (fn [[_ folded-entity]]
                           (entity/render-complete? folded-entity)))
                 desired-entities)
           desired-entities)))

(defn- desired-file
  "Return a folded entity's target journal filename, or fail before writing."
  [{:keys [eid attributes] :as folded-entity}]
  (let [filename (get attributes "entity/file")]
    (when-not filename
      (throw (ex-info "Cannot project an entity without entity/file"
                      {:eid eid :entity folded-entity})))
    filename))

(defn- entity-position
  "Return the sortable projection position for an entity."
  [{:keys [sequence projection-position attributes]}]
  (or (get attributes "entity/position")
      projection-position
      sequence))

(defn- root-eid
  "Return the top-level root eid for a possibly-child entity."
  [entities eid]
  (let [attributes (get-in entities [eid :attributes])]
    (condp = (get attributes "entity/type")
      schemas/posting-type
      (get attributes "posting/parent-eid")

      schemas/tag-type
      (let [parent-eid (get attributes "tag/parent-eid")
            parent-type (get-in entities [parent-eid :attributes "entity/type"])]
        (if (= schemas/posting-type parent-type)
          (get-in entities [parent-eid :attributes "posting/parent-eid"])
          parent-eid))

      (when (entity/top-level-entity? (get entities eid))
        eid))))

(defn- impacted-root-eids
  "Return roots touched by affected eids in either before or after state."
  [{:keys [current-entities desired-entities datoms]}]
  (->> (affected-eids datoms)
       (mapcat (fn [eid]
                 [(root-eid current-entities eid)
                  (root-eid desired-entities eid)]))
       (remove nil?)
       distinct))

(defn- desired-file-entry
  "Turn one folded entity into the byte-op entry for ordered file rewrites."
  [desired-entities folded-entity]
  (assoc (entity/render-entity-entry folded-entity desired-entities)
         :position (entity-position folded-entity)))

(defn- impacted-filenames
  "Return files that may need a rewrite because affected entities changed."
  [{:keys [current-entities projectable-entities datoms] :as context}]
  (let [root-eids (impacted-root-eids context)
        current-files (keep #(get-in current-entities [% :attributes "entity/file"])
                            root-eids)
        desired-files (keep #(some-> (get projectable-entities %) desired-file)
                            root-eids)
        asserted-files (keep datom-entity-file datoms)]
    (assoc context
           :filenames
           (distinct (concat current-files desired-files asserted-files)))))

(defn- desired-file-entries
  "Return every desired entity that should live in one journal file."
  [desired-entities filename]
  (->> desired-entities
       vals
       (filter entity/top-level-entity?)
       (filter #(= filename (desired-file %)))
       (map #(desired-file-entry desired-entities %))
       (sort-by (juxt :position :eid))
       (mapv #(select-keys % [:eid :body :metadata]))))

(defn- attach-workspace-directory
  "Turn the workspace directory input into a File object."
  [{:keys [workspace-directory] :as context}]
  (assoc context :workspace-directory (io/file workspace-directory)))

(defn- ensure-workspace-directory!
  "Create the workspace directory if needed."
  [{:keys [workspace-directory] :as context}]
  (.mkdirs workspace-directory)
  context)

(defn- attach-current-entities
  "Attach entity state parsed from the current journal projection."
  [{:keys [workspace-directory] :as context}]
  (assoc context :current-entities (current-entities workspace-directory)))

(defn- attach-mutation-plans
  "Prepare every impacted file rewrite before any file is flushed."
  [{:keys [workspace-directory projectable-entities filenames] :as context}]
  (assoc context
         :mutation-plans
         (mapv (fn [filename]
                 (byte-ops/plan-ordered-entity-mutations
                  (io/file workspace-directory filename)
                  (desired-file-entries projectable-entities filename)))
               filenames)))

(defn- flush-file-mutations!
  "Flush all prepared mutation plans to journal files."
  [{:keys [mutation-plans] :as context}]
  (doseq [plan mutation-plans]
    (byte-ops/flush-mutations! plan))
  context)

(defn- return-last-projected-datom-sequence-number
  "Return the last projected datom sequence number after a successful projection."
  [{:keys [datoms]}]
  (-> datoms last first))

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
   :attributes attributes
   :tags (mapv tag-json
               (sorted-child-entities entities
                                      eid
                                      schemas/tag-type
                                      "tag/position"))})

(defn- entity-page-json
  [journal page-entry]
  (let [{:keys [eid position entity-address-bytes]} page-entry
        {:keys [body metadata]} (byte-ops/read-entity journal entity-address-bytes)
        parsed-entities (entity/parse-entity eid body metadata)
        root (update-in (get parsed-entities eid)
                        [:attributes]
                        #(assoc %
                                "entity/file" (.getName (io/file journal))
                                "entity/position" position))]
    (when-not (entity/top-level-entity? root)
      (throw (ex-info "Paged journal entity is not top-level"
                      {:eid eid
                       :entity root})))
    {:eid eid
     :type (get-in root [:attributes "entity/type"])
     :file (.getName (io/file journal))
     :position position
     :body body
     :attributes (:attributes root)
     :children {:postings (mapv #(posting-json parsed-entities %)
                                (sorted-child-entities parsed-entities
                                                       eid
                                                       schemas/posting-type
                                                       "posting/position"))
                :tags (mapv tag-json
                            (sorted-child-entities parsed-entities
                                                   eid
                                                   schemas/tag-type
                                                   "tag/position"))}}))

(defn read-journal-page
  "Read a position-cursor page from one projected journal file.

  This reads the latest projected journal file in the workspace-directory. It
  does not read the event-log and does not project pending datoms."
  [{:keys [workspace-directory journal limit after-position]}]
  (event-log/validate-journal-filename journal)
  (let [limit (or limit 100)
        journal-file (io/file workspace-directory journal)
        {:keys [entries has-more?]}
        (byte-ops/find-positioned-entity-address-page
         journal-file
         {:limit limit :after-position after-position})
        entities (mapv #(entity-page-json journal-file %) entries)]
    {:journal journal
     :limit limit
     :after_position after-position
     :top_level_entity_count (count entities)
     :has_more has-more?
     :last_entity_position (:position (last entities))
     :entities entities}))

(defn project!
  "Apply `[sequence eid attr value retract?]` datoms to a workspace directory and
  return the last projected datom sequence number.

  A nil last projected datom sequence number means the projection is empty and
  the event log contains its full history. An integer value means the event log
  contains only newer facts. The caller persists the returned sequence number
  only after this function succeeds."
  ([workspace-directory last-projected-datom-sequence-number event-log]
   (project! workspace-directory
             last-projected-datom-sequence-number
             event-log
             {}))
  ([workspace-directory
    last-projected-datom-sequence-number
    event-log
    {:keys [ignore-incomplete-entities?]}]
   (let [context {:workspace-directory workspace-directory
                  :last-projected-datom-sequence-number last-projected-datom-sequence-number
                  :datoms (vec event-log)
                  :ignore-incomplete-entities? ignore-incomplete-entities?}]

     ;; Run side-effect validations on the context map
     (doto context
       (-> :last-projected-datom-sequence-number event-log/validate-last-projected-datom-sequence-number)
       (-> :datoms (event-log/validate-event-log last-projected-datom-sequence-number)))

     ;; Execute projection pipeline
     (if (empty? (:datoms context))
       (:last-projected-datom-sequence-number context)
       (-> context
           attach-workspace-directory
           ensure-workspace-directory!
           attach-current-entities
           fold-affected-entities
           filter-projectable-entities
           impacted-filenames
           attach-mutation-plans
           flush-file-mutations!
           return-last-projected-datom-sequence-number)))))
