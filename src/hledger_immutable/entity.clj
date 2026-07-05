(ns hledger-immutable.entity
  (:require [clojure.string :as str]
            [hledger-immutable.schemas :as schemas]))

(defn top-level-entity?
  "Return true when a folded entity renders as its own journal entity."
  [{:keys [attributes]}]
  (schemas/top-level-entity-type? (get attributes "entity/type")))

(defn child-entity?
  "Return true when a folded entity is rendered inside another entity body."
  [{:keys [attributes]}]
  (schemas/child-entity-type? (get attributes "entity/type")))

(defn render-complete?
  "Return true when a folded entity has enough attributes to render safely."
  [{:keys [attributes]}]
  (let [entity-type (get attributes "entity/type")
        required-body-attributes
        (get-in schemas/attribute-registry [entity-type :required])
        required-system-attributes
        (cond
          (schemas/top-level-entity-type? entity-type)
          #{"entity/file"}

          (schemas/child-entity-type? entity-type)
          #{(schemas/parent-attribute entity-type)
            (schemas/position-attribute entity-type)})]
    (boolean
     (and entity-type
          required-body-attributes
          required-system-attributes
          (every? #(contains? attributes %)
                  (concat required-system-attributes
                          required-body-attributes))))))

(defn- required-value
  "Return an entity attribute, or fail before rendering an incomplete journal entry."
  [entity attribute]
  (let [value (get entity attribute)]
    (when (nil? value)
      (throw (ex-info "Cannot render an incomplete entity"
                      {:attribute attribute
                       :entity entity})))
    value))

(defn- validate-marker-metadata!
  [metadata]
  (schemas/validate-marker-metadata! metadata))

(defn- sorted-children
  [entities parent-eid entity-type position-attribute]
  (->> entities
       vals
       (filter #(let [attributes (:attributes %)]
                  (and (= entity-type (get attributes "entity/type"))
                       (= parent-eid (get attributes
                                          (schemas/parent-attribute
                                           entity-type))))))
       (sort-by (juxt #(get-in % [:attributes position-attribute])
                      :eid))))

(defn- render-tag
  [{:keys [attributes]}]
  (let [name (required-value attributes "tag/name")]
    (str name ":"
         (when-let [value (get attributes "tag/value")]
           (str " " value)))))

(defn- tags-comment
  [tags]
  (when (seq tags)
    (str " ; " (str/join ", " (map render-tag tags)))))

(defn- tag-metadata
  [{:keys [eid attributes]}]
  (cond-> {:eid eid
           :position (required-value attributes "tag/position")
           :name (required-value attributes "tag/name")}
    (contains? attributes "tag/value")
    (assoc :value (get attributes "tag/value"))))

(defn- posting-metadata
  [posting entities]
  (cond-> {:eid (:eid posting)
           :position (required-value (:attributes posting) "posting/position")}
    (seq (sorted-children entities (:eid posting) schemas/tag-type "tag/position"))
    (assoc :tags (mapv tag-metadata
                       (sorted-children entities
                                        (:eid posting)
                                        schemas/tag-type
                                        "tag/position")))))

(defn- root-metadata
  [{:keys [attributes eid] :as root} entities]
  (let [tags (sorted-children entities eid schemas/tag-type "tag/position")
        postings (when (schemas/transaction-like-entity-type?
                        (get attributes "entity/type"))
                   (sorted-children entities
                                    eid
                                    schemas/posting-type
                                    "posting/position"))]
    (cond-> {}
      (contains? attributes "entity/position")
      (assoc "entity/position" (get attributes "entity/position"))

      (seq tags)
      (assoc :tags (mapv tag-metadata tags))

      (seq postings)
      (assoc :postings (mapv #(posting-metadata % entities) postings)))))

(defn- render-posting
  [{:keys [attributes eid] :as posting} entities]
  (let [status (get attributes "posting/status")
        account (required-value attributes "posting/account")
        amount (get attributes "posting/amount")
        tags (sorted-children entities eid schemas/tag-type "tag/position")]
    (str "    "
         (when status (str status " "))
         account
         (when amount
           (str "  " amount))
         (tags-comment tags))))

(defn- render-transaction-header
  [entity tags]
  (str (required-value entity "transaction/date")
       (when-let [status (get entity "transaction/status")]
         (str " " status))
       (when-let [code (get entity "transaction/code")]
         (str " (" code ")"))
       " "
       (required-value entity "transaction/description")
       (tags-comment tags)))

(defn- render-periodic-header
  [entity tags]
  (str "~ " (required-value entity "periodic/expression")
       (when-let [description (get entity "periodic/description")]
         (str " " description))
       (tags-comment tags)))

(defn render-entity
  "Turn a folded entity map or plain attribute map into hledger entity text.

  When `entities` is supplied, child posting/tag entities are assembled into the
  rendered parent body."
  ([entity]
   (render-entity {:eid nil :attributes entity} {}))
  ([{:keys [attributes eid] :as folded-entity} entities]
   (let [tags (when eid
                (sorted-children entities eid schemas/tag-type "tag/position"))]
     (condp = (get attributes "entity/type")
       schemas/commodity-type
       (str "commodity " (required-value attributes "commodity/name")
            (tags-comment tags))

       schemas/price-type
       (str "P "
            (required-value attributes "price/date") " "
            (required-value attributes "price/commodity") " "
            (required-value attributes "price/value")
            (tags-comment tags))

       schemas/account-type
       (str "account " (required-value attributes "account/name")
            (tags-comment tags))

       schemas/transaction-type
       (let [postings (sorted-children entities
                                       eid
                                       schemas/posting-type
                                       "posting/position")]
         (str/join "\n"
                   (cons (render-transaction-header attributes tags)
                         (map #(render-posting % entities) postings))))

       schemas/periodic-transaction-type
       (let [postings (sorted-children entities
                                       eid
                                       schemas/posting-type
                                       "posting/position")]
         (str/join "\n"
                   (cons (render-periodic-header attributes tags)
                         (map #(render-posting % entities) postings))))

       schemas/include-type
       (str "include " (required-value attributes "include/file"))

       schemas/decimal-mark-type
       (str "decimal-mark " (required-value attributes "decimal-mark/value"))

       schemas/alias-type
       (str "alias " (required-value attributes "alias/old")
            " = " (required-value attributes "alias/new"))

       schemas/default-commodity-type
       (str "D " (required-value attributes "default-commodity/value"))

       schemas/payee-type
       (str "payee " (required-value attributes "payee/name"))

       schemas/tag-declaration-type
       (str "tag " (required-value attributes "tag-declaration/name"))

       (throw (ex-info "Cannot render an entity with an unknown type"
                       {:entity folded-entity}))))))

(defn render-entity-entry
  "Return `{:eid eid :body string :metadata map}` for byte-op planning."
  [folded-entity entities]
  {:eid (:eid folded-entity)
   :body (render-entity folded-entity entities)
   :metadata (root-metadata folded-entity entities)})

(defn- split-comment
  [line]
  (if-let [index (str/index-of line ";")]
    [(subs line 0 index)
     (str/trim (subs line (inc index)))]
    [line nil]))

(defn- parse-visible-tags
  [comment]
  (if (str/blank? comment)
    []
    (->> (str/split comment #",")
         (map str/trim)
         (remove str/blank?)
         (mapv (fn [tag-text]
                 (let [[name value] (str/split tag-text #":" 2)]
                   (cond-> {:name (str/trim name)}
                     (some? value)
                     (assoc :value (str/trim value)))))))))

(defn- comparable-tag
  [tag]
  (select-keys tag [:name :value]))

(defn- assert-tags-match!
  [visible metadata context]
  (when-not (= (mapv comparable-tag visible)
               (mapv comparable-tag metadata))
    (throw (ex-info "Visible tags do not match entity marker metadata"
                    {:visible visible
                     :metadata metadata
                     :context context}))))

(defn- tag-entities
  [parent-eid tags]
  (into {}
        (map (fn [{:keys [eid position name value] :as tag}]
               [eid {:sequence eid
                     :eid eid
                     :attributes (cond-> {"entity/type" schemas/tag-type
                                          "tag/parent-eid" parent-eid
                                          "tag/position" position
                                          "tag/name" name}
                                   (contains? tag :value)
                                   (assoc "tag/value" value))}]))
        tags))

(defn- parse-header-tags
  [line metadata context]
  (let [[body comment] (split-comment line)
        body (str/trimr body)
        visible-tags (parse-visible-tags comment)
        metadata-tags (or (:tags metadata) [])]
    (assert-tags-match! visible-tags metadata-tags context)
    {:body (str/trim body)
     :tags metadata-tags}))

(defn- parse-posting-line
  [line metadata]
  (let [[body comment] (split-comment line)
        body (str/trimr body)
        visible-tags (parse-visible-tags comment)
        metadata-tags (or (:tags metadata) [])
        [_ status account amount]
        (re-matches #"^\s+(?:([!*])\s+)?(.+?)(?:\s{2,}(.+))?$" body)]
    (when-not account
      (throw (ex-info "Cannot parse this posting entity"
                      {:line line})))
    (assert-tags-match! visible-tags metadata-tags {schemas/posting-type metadata})
    {:attributes (cond-> {"entity/type" schemas/posting-type
                          "posting/position" (:position metadata)
                          "posting/account" account}
                   status (assoc "posting/status" status)
                   amount (assoc "posting/amount" amount))
     :tags metadata-tags}))

(defn- parse-transaction-like
  [eid lines metadata entity-type]
  (let [{header-body :body header-tags :tags}
        (parse-header-tags (first lines) metadata {:eid eid})
        postings-metadata (or (:postings metadata) [])
        posting-lines (vec (rest lines))]
    (when-not (= (count posting-lines) (count postings-metadata))
      (throw (ex-info "Posting count does not match entity marker metadata"
                      {:eid eid
                       :posting-lines posting-lines
                       :postings-metadata postings-metadata})))
    (let [[attributes]
          (condp = entity-type
            schemas/transaction-type
            (let [[_ date status code description]
                  (re-matches #"^(\d{4}-\d{2}-\d{2})(?:\s+([!*]))?(?:\s+\(([^)]+)\))?\s+(.+)$"
                              header-body)]
              [(cond-> {"entity/type" schemas/transaction-type
                        "transaction/date" date
                        "transaction/description" description}
                 status (assoc "transaction/status" status)
                 code (assoc "transaction/code" code))])

            schemas/periodic-transaction-type
            (let [[_ expression description]
                  (re-matches #"^~\s+(\S+)(?:\s+(.+))?$" header-body)]
              [(cond-> {"entity/type" schemas/periodic-transaction-type
                        "periodic/expression" expression}
                 description (assoc "periodic/description" description))]))]
      (when (or (nil? attributes)
                (some nil? (vals (select-keys attributes
                                              (if (= schemas/transaction-type
                                                     entity-type)
                                                ["transaction/date"
                                                 "transaction/description"]
                                                ["periodic/expression"])))))
        (throw (ex-info "Cannot parse this journal entity"
                        {:line (first lines)})))
      (let [posting-results (mapv parse-posting-line
                                  posting-lines
                                  postings-metadata)
            posting-entities
            (into {}
                  (map (fn [metadata {:keys [attributes]}]
                         [(:eid metadata)
                          {:sequence (:eid metadata)
                           :eid (:eid metadata)
                           :attributes (assoc attributes
                                              "posting/parent-eid" eid)}])
                       postings-metadata
                       posting-results))
            posting-tag-entities
            (apply merge
                   (map (fn [metadata {:keys [tags]}]
                          (tag-entities (:eid metadata) tags))
                        postings-metadata
                        posting-results))]
        (merge {eid {:sequence eid
                     :eid eid
                     :attributes (cond-> attributes
                                   (contains? metadata "entity/position")
                                   (assoc "entity/position"
                                          (get metadata "entity/position")))}}
               (tag-entities eid header-tags)
               posting-entities
               posting-tag-entities)))))

(defn parse-entity
  "Parse one entity body and marker metadata into folded entities keyed by eid.

  The one-arity form preserves the older plain attribute-map API for tests and
  simple parsing. The three-arity form is used by projection recovery."
  ([body]
   (:attributes (get (parse-entity nil body {}) nil)))
  ([eid body metadata]
   (validate-marker-metadata! metadata)
   (let [lines (str/split-lines body)
         first-line (first lines)
         header (parse-header-tags first-line metadata {:eid eid})
         line-body (:body header)
         root-tags (:tags header)
         entity-attributes
         (cond
           (str/starts-with? line-body "commodity ")
           {"entity/type" schemas/commodity-type
            "commodity/name" (subs line-body (count "commodity "))}

           (str/starts-with? line-body "P ")
           (if-let [[_ date commodity value]
                    (re-matches #"^P (\S+) (\S+) (.+)$" line-body)]
             {"entity/type" schemas/price-type
              "price/date" date
              "price/commodity" commodity
              "price/value" value}
             (throw (ex-info "Cannot parse this price entity" {:line body})))

           (str/starts-with? line-body "account ")
           {"entity/type" schemas/account-type
            "account/name" (subs line-body (count "account "))}

           (str/starts-with? line-body "include ")
           {"entity/type" schemas/include-type
            "include/file" (subs line-body (count "include "))}

           (str/starts-with? line-body "decimal-mark ")
           {"entity/type" schemas/decimal-mark-type
            "decimal-mark/value" (subs line-body (count "decimal-mark "))}

           (str/starts-with? line-body "alias ")
           (if-let [[_ old new] (re-matches #"^alias (.+) = (.+)$" line-body)]
             {"entity/type" schemas/alias-type
              "alias/old" old
              "alias/new" new}
             (throw (ex-info "Cannot parse this alias entity" {:line body})))

           (str/starts-with? line-body "D ")
           {"entity/type" schemas/default-commodity-type
            "default-commodity/value" (subs line-body (count "D "))}

           (str/starts-with? line-body "payee ")
           {"entity/type" schemas/payee-type
            "payee/name" (subs line-body (count "payee "))}

           (str/starts-with? line-body "tag ")
           {"entity/type" schemas/tag-declaration-type
            "tag-declaration/name" (subs line-body (count "tag "))}

           (str/starts-with? line-body "~ ")
           nil

           :else
           nil)]
     (cond
       (str/starts-with? line-body "~ ")
       (parse-transaction-like eid lines metadata schemas/periodic-transaction-type)

       (and entity-attributes
            (= schemas/transaction-type
               (get entity-attributes "entity/type")))
       (parse-transaction-like eid lines metadata schemas/transaction-type)

       entity-attributes
       (merge {eid {:sequence eid
                    :eid eid
                    :attributes (cond-> entity-attributes
                                  (contains? metadata "entity/position")
                                  (assoc "entity/position"
                                         (get metadata "entity/position")))}}
              (tag-entities eid root-tags))

       :else
       (parse-transaction-like eid lines metadata schemas/transaction-type)))))

(defn apply-datom
  "Apply one `[sequence eid attr value retract?]` datom."
  [entities [sequence-number eid attr value retract?]]
  (let [entity (get entities eid)]
    (if retract?
      ;; Retract only when this exact value is currently asserted. A stale or
      ;; repeated retraction is a no-op, which also makes replay safe.
      (if (= value (get-in entity [:attributes attr]))
        (let [attributes (dissoc (:attributes entity) attr)]
          ;; Once its final attribute is retracted, the entity no longer
          ;; contributes anything to the assembled journals.
          (if (seq attributes)
            (assoc-in entities [eid :attributes] attributes)
            (dissoc entities eid)))
        entities)
      ;; The first assertion creates the entity and fixes its journal order.
      ;; Later assertions add or replace attributes.
      (-> entities
          (update eid
                  #(or % {:sequence sequence-number
                          :eid eid
                          :attributes {}}))
          (assoc-in [eid :attributes attr] value)))))

(defn datoms->entities
  "Fold sequenced datoms into current entities keyed by eid."
  ([datoms]
   (datoms->entities {} datoms))
  ([entities datoms]
   (reduce apply-datom entities datoms)))

(def fold-datoms apply-datom)

(defn entity->datoms
  "Return authored assertion datoms for one public entity shape."
  [{:keys [eid attributes]}]
  (mapv (fn [[attr value]]
          [eid attr value false])
        attributes))

(defn deletion->datoms
  "Generate retraction datoms for an entity and all its descendants.

  The caller loads the root entity and all descendants (postings, root-level
  tags, and posting-level tags) into the entities map. This function walks
  the entity graph starting from root-eid and emits [eid attr value true]
  for every currently folded attribute across the entire closure."
  [entities root-eid]
  (let [root (get entities root-eid)]
    (when-not root
      (throw (ex-info "Cannot delete an unknown entity"
                      {:eid root-eid})))
    (let [posting-eids (map :eid
                            (sorted-children entities
                                             root-eid
                                             schemas/posting-type
                                             "posting/position"))
          root-tag-eids (map :eid
                             (sorted-children entities root-eid schemas/tag-type
                                              "tag/position"))
          posting-tag-eids (mapcat
                            (fn [posting-eid]
                              (map :eid
                                   (sorted-children entities
                                                    posting-eid
                                                    schemas/tag-type
                                                    "tag/position")))
                            posting-eids)
          all-eids (into [root-eid] (concat posting-eids root-tag-eids
                                            posting-tag-eids))]
      (vec
       (mapcat (fn [eid]
                 (let [attributes (get-in entities [eid :attributes])]
                   (map (fn [[attr value]]
                          [eid attr value true])
                        attributes)))
               all-eids)))))
