(ns hledger-immutable.mutation
  "Command-facing event-log mutation flows."
  (:require [hledger-immutable.db.connection :as connection]
            [hledger-immutable.db.migrations :as migrations]
            [hledger-immutable.db.table.entity-ids :as entity-ids]
            [hledger-immutable.db.table.event-log :as event-log-table]
            [hledger-immutable.encoder :as encoder]
            [hledger-immutable.entity :as entity]
            [hledger-immutable.event-log :as event-log]
            [hledger-immutable.position :as position]
            [hledger-immutable.projector :as projector]
            [hledger-immutable.query :as query]
            [hledger-immutable.schemas :as schemas]))

(def ^:private child-parent-attributes
  #{"posting/parent-eid" "tag/parent-eid"})

(defn- valid-parent-type?
  [child-type parent-type]
  (cond
    (= schemas/posting-type child-type)
    (schemas/transaction-like-entity-type? parent-type)

    (= schemas/tag-type child-type)
    (or (schemas/top-level-entity-type? parent-type)
        (= schemas/posting-type parent-type))

    :else false))

(defn- touched-child-eids
  [authored-datoms]
  (->> authored-datoms
       (keep (fn [[eid attr value retract?]]
               (when (or (contains? child-parent-attributes attr)
                         (and (= "entity/type" attr)
                              (schemas/child-entity-type? value)
                              (not retract?)))
                 eid)))
       set))

(defn- desired-entity-after
  [conn eid sequenced-datoms]
  (let [current (query/current-entity conn eid)
        relevant-datoms (filterv #(= eid (second %)) sequenced-datoms)]
    (get (entity/datoms->entities
          (cond-> {}
            current (assoc eid current))
          relevant-datoms)
         eid)))

(defn- validate-child-parent-references!
  "Ensure child parent references point to existing entities of compatible type."
  [conn authored-datoms]
  (let [sequenced-datoms (mapv (fn [sequence-number datom]
                                 (event-log/prepend-sequence sequence-number
                                                             datom))
                               (range)
                               authored-datoms)]
    (doseq [child-eid (touched-child-eids authored-datoms)]
      (when-let [child (desired-entity-after conn child-eid sequenced-datoms)]
        (let [child-type (query/folded-entity-type child)
              parent-attr (position/parent-attribute child-type)
              parent-eid (get-in child [:attributes parent-attr])]
          (when parent-eid
            (let [parent (desired-entity-after conn parent-eid sequenced-datoms)
                  parent-type (query/folded-entity-type parent)]
              (cond
                (nil? parent)
                (throw (ex-info "A child entity references an unknown parent eid"
                                {:child-eid child-eid
                                 :child-type child-type
                                 :parent-eid parent-eid
                                 :parent-attribute parent-attr}))

                (not (valid-parent-type? child-type parent-type))
                (throw (ex-info "A child entity references an incompatible parent entity"
                                {:child-eid child-eid
                                 :child-type child-type
                                 :parent-eid parent-eid
                                 :parent-type parent-type
                                 :parent-attribute parent-attr}))))))))))

(defn- append-authored-datoms!
  "Validate authored datoms as an event-log batch, then append them atomically."
  [conn authored-datoms]
  (event-log/validate-event-log
   (mapv (fn [sequence-number datom]
           (event-log/prepend-sequence sequence-number datom))
         (range)
         authored-datoms)
   nil)
  (validate-child-parent-references! conn authored-datoms)
  (event-log-table/append-datoms! conn authored-datoms))

(defn- validate-eids-exist!
  "Verify that every eid referenced in the datoms exists in entity_ids."
  [conn datoms]
  (doseq [eid (set (map first datoms))]
    (when-not (entity-ids/eid-exists? conn eid)
      (throw (ex-info "A datom references an unknown eid"
                      {:eid eid})))))

(defn- position-change-datoms
  [entities position-attr changes]
  (mapcat
   (fn [[eid new-position]]
     (let [old-position (get-in (get entities eid) [:attributes position-attr])]
       (if old-position
         [[eid position-attr old-position true]
          [eid position-attr new-position false]]
         [[eid position-attr new-position false]])))
   changes))

(defn- indexed-entities
  [entities]
  (into {} (map (fn [entity] [(:eid entity) entity]) entities)))

(defn- root-position-plan
  [conn input eid]
  (let [auto-pos (position/automatic-position eid)]
    (cond
      (contains? input :after_position)
      (throw (ex-info "after_position is not supported for mutation input"
                      {:input input}))

      (or (contains? input :after_eid) (contains? input :before_eid))
      (let [scope-entities (query/entities-with-attribute conn "entity/position")
            target {:eid eid
                    :attributes {"entity/type" schemas/commodity-type}}
            entities (conj scope-entities target)]
        (position/plan-position-changes
         {:entities entities
          :target-eid eid
          :position-attr "entity/position"
          :after-eid (:after_eid input)
          :before-eid (:before_eid input)}))

      :else {eid auto-pos})))

(defn- plan-amend-position-datoms
  [conn current desired after-eid before-eid]
  (when (or after-eid before-eid)
    (let [entity-type (query/folded-entity-type desired)
          pos-attr (position/position-attribute entity-type)
          scope (position/ordering-scope desired)
          scope-entities (->> (query/entities-with-attribute conn pos-attr)
                              (remove #(= (:eid current) (:eid %)))
                              (filter #(position/scope-matches?
                                        scope
                                        (position/ordering-scope %))))
          entities (conj (vec scope-entities) desired)
          entities-by-eid (indexed-entities entities)
          changes (position/plan-position-changes
                   {:entities entities
                    :target-eid (:eid current)
                    :position-attr pos-attr
                    :after-eid after-eid
                    :before-eid before-eid})]
      (vec (position-change-datoms entities-by-eid pos-attr changes)))))

(defn- fetch-occupied-positions
  "Fetch occupied positions for entities in the same scope as desired."
  [conn desired]
  (let [entity-type (query/folded-entity-type desired)
        pos-attr (position/position-attribute entity-type)
        desired-pos (get-in desired [:attributes pos-attr])
        scope (position/ordering-scope desired)]
    (if (nil? desired-pos)
      {}
      (position/occupied-in-scope
       (query/entities-with-attribute-value conn pos-attr desired-pos)
       scope))))

(defn- allocate-tag-eids
  "Allocate eids for root-level and per-posting tags."
  [conn input postings]
  (let [root-tags (:tags input)
        root-tag-eids (vec (repeatedly (count (or root-tags []))
                                       #(entity-ids/allocate-eid! conn)))
        posting-tag-eids (vec
                          (for [posting (or postings [])]
                            (vec (repeatedly (count (or (:tags posting) []))
                                             #(entity-ids/allocate-eid! conn)))))]
    {:root root-tag-eids :postings posting-tag-eids}))

(defn- compute-tag-positions
  "Derive automatic positions for all tag eids."
  [tag-eids]
  {:root (mapv position/automatic-position (:root tag-eids))
   :postings (mapv #(mapv position/automatic-position %) (:postings tag-eids))})

(defn- collect-all-tag-eids
  "Flatten all tag eids into a single vector."
  [tag-eids]
  (vec (concat (:root tag-eids)
               (apply concat (:postings tag-eids)))))

(defn- project-after-mutation
  "Run projection when requested and attach its result to a mutation result."
  [workspace project? result]
  (if project?
    (assoc result :projection (projector/project-latest! workspace))
    (assoc result :projection {:status :skipped})))

(defn- mutate!
  "Run an event-log mutation in a BEGIN IMMEDIATE transaction, then project."
  [workspace project? f]
  (let [result
        (connection/with-connection
          workspace
          (fn [conn]
            (migrations/ensure! conn)
            (connection/with-transaction conn #(f conn))))]
    (project-after-mutation workspace project? result)))

(defn append-datoms!
  "Validate and append raw authored datoms, then project."
  [{:keys [workspace datoms project?]}]
  (mutate!
   workspace
   project?
   (fn [conn]
     (validate-eids-exist! conn datoms)
     (append-authored-datoms! conn datoms)
     {:datom-count (count datoms)})))

(defn- add-simple-entity!
  [entity-type encode-fn {:keys [workspace data project?]}]
  (mutate!
   workspace
   project?
   (fn [conn]
     (let [input (schemas/validate-add-input! entity-type data)
           eid (entity-ids/allocate-eid! conn)
           position-plan (root-position-plan conn input eid)
           pos (get position-plan eid)
           entities (indexed-entities (query/entities-with-attribute conn "entity/position"))
           rebalance-datoms (position-change-datoms entities
                                                    "entity/position"
                                                    (dissoc position-plan eid))
           datoms (concat (encode-fn entity-type input {:eid eid :position pos})
                          rebalance-datoms)]
       (append-authored-datoms! conn datoms)
       {:eid eid :datom-count (count datoms)}))))

(defn add-commodity! [opts] (add-simple-entity! schemas/commodity-type encoder/encode-add-simple opts))
(defn add-price! [opts] (add-simple-entity! schemas/price-type encoder/encode-add-simple opts))
(defn add-account! [opts] (add-simple-entity! schemas/account-type encoder/encode-add-simple opts))
(defn add-include! [opts] (add-simple-entity! schemas/include-type encoder/encode-add-simple opts))
(defn add-alias! [opts] (add-simple-entity! schemas/alias-type encoder/encode-add-simple opts))
(defn add-decimal-mark! [opts] (add-simple-entity! schemas/decimal-mark-type encoder/encode-add-simple opts))
(defn add-default-commodity! [opts] (add-simple-entity! schemas/default-commodity-type encoder/encode-add-simple opts))
(defn add-payee! [opts] (add-simple-entity! schemas/payee-type encoder/encode-add-simple opts))
(defn add-tag-declaration! [opts] (add-simple-entity! schemas/tag-declaration-type encoder/encode-add-simple opts))

(defn add-tag!
  "Add a child tag to an existing top-level entity or posting."
  [{:keys [workspace data project?]}]
  (mutate!
   workspace
   project?
   (fn [conn]
     (let [input (schemas/validate-add-tag-input! data)
           eid (entity-ids/allocate-eid! conn)
           position (position/automatic-position eid)
           datoms (encoder/encode-add-tag input {:eid eid
                                                 :position position})]
       (append-authored-datoms! conn datoms)
       {:eid eid
        :parent-entity-id (:parent-eid input)
        :datom-count (count datoms)}))))

(defn- add-transaction-like!
  [entity-type {:keys [workspace data project?]}]
  (mutate!
   workspace
   project?
   (fn [conn]
     (let [input (schemas/validate-add-input! entity-type data)
           root-eid (entity-ids/allocate-eid! conn)
           postings (:postings input)
           posting-eids (vec (repeatedly (count postings)
                                         #(entity-ids/allocate-eid! conn)))
           tag-eids (allocate-tag-eids conn input postings)
           position-plan (root-position-plan conn input root-eid)
           root-pos (get position-plan root-eid)
           entities (indexed-entities (query/entities-with-attribute conn "entity/position"))
           rebalance-datoms (position-change-datoms entities
                                                    "entity/position"
                                                    (dissoc position-plan root-eid))
           posting-positions (mapv position/automatic-position posting-eids)
           tag-positions (compute-tag-positions tag-eids)
           eid-ctx {:root-eid root-eid
                    :posting-eids posting-eids
                    :tag-eids tag-eids
                    :positions {:root root-pos
                                :postings posting-positions
                                :tags tag-positions}}
           datoms (concat (encoder/encode-add-transaction-like entity-type input eid-ctx)
                          rebalance-datoms)]
       (append-authored-datoms! conn datoms)
       {:eid root-eid
        :posting-eids posting-eids
        :tag-eids (collect-all-tag-eids tag-eids)
        :datom-count (count datoms)}))))

(defn add-transaction! [opts] (add-transaction-like! schemas/transaction-type opts))
(defn add-periodic-transaction! [opts] (add-transaction-like! schemas/periodic-transaction-type opts))
(defn add-budget! [opts] (add-transaction-like! schemas/periodic-transaction-type opts))

(defn- parse-patch
  "Parse a raw JSON data map (string keys) into a patch."
  [data]
  (when (and (get data "after_eid") (get data "before_eid"))
    (throw (ex-info "after_eid and before_eid are mutually exclusive"
                    {:data data})))
  (when (contains? data "after_position")
    (throw (ex-info "after_position is not supported for mutation input"
                    {:data data})))
  {:set (or (get data "set") {})
   :unset (or (get data "unset") [])
   :after-eid (get data "after_eid")
   :before-eid (get data "before_eid")})

(defn- apply-patch
  "Apply a patch to a current entity to produce the desired entity."
  [current patch]
  (let [{:keys [set unset]} patch]
    (-> current
        (update :attributes merge set)
        (update :attributes #(apply dissoc % (or unset []))))))

(defn update-entity!
  "Apply a set/unset patch to an existing entity and optionally project."
  [{:keys [workspace eid data project?]}]
  (mutate!
   workspace
   project?
   (fn [conn]
     (let [current (query/current-entity conn eid)]
       (when-not current
         (throw (ex-info "Cannot update an unknown entity"
                         {:eid eid})))
       (let [entity-type (query/folded-entity-type current)
             raw-patch (parse-patch data)
             pos-attr (position/position-attribute entity-type)
             patch {:set (:set raw-patch)
                    :unset (:unset raw-patch)}
             desired (apply-patch current patch)
             occupied (fetch-occupied-positions conn desired)
             movement-datoms (plan-amend-position-datoms conn
                                                         current
                                                         desired
                                                         (:after-eid raw-patch)
                                                         (:before-eid raw-patch))
             patch-data (:set raw-patch)]
         (position/validate-amendment! current desired patch-data occupied)
         (let [datoms (concat (encoder/encode-amend entity-type current patch)
                              movement-datoms)]
           (append-authored-datoms! conn datoms)
           {:eid eid :datom-count (count datoms)}))))))

(defn delete-entity!
  "Retract an entity and all its descendants, then optionally project."
  [{:keys [workspace eid project?]}]
  (mutate!
   workspace
   project?
   (fn [conn]
     (let [root (query/current-entity conn eid)]
       (when-not root
         (throw (ex-info "Cannot delete an unknown entity"
                         {:eid eid})))
       (let [child-entities (query/child-entities conn eid)
             entities (assoc child-entities eid root)
             datoms (entity/deletion->datoms entities eid)]
         (append-authored-datoms! conn datoms)
         {:deleted-eids (keys entities)
          :datom-count (count datoms)})))))
