(ns hledger-immutable.query
  "Database-backed queries over the current logical entity state."
  (:require [hledger-immutable.db.table.event-log :as event-log-table]
            [hledger-immutable.entity :as entity]))

(defn current-entity
  "Fold one eid's event-log history into its current entity, or return nil."
  [conn eid]
  (get (entity/datoms->entities
        (event-log-table/datoms-for-eid conn eid))
       eid))

(defn folded-entity-type
  "Return the entity/type attribute from a folded entity."
  [folded-entity]
  (get-in folded-entity [:attributes "entity/type"]))

(defn entities-with-attribute-value
  "Fold entities that have ever asserted an attribute/value candidate.

  The indexed candidate query avoids folding the complete event-log. Folding
  each candidate is still required to discard stale assertions and retractions."
  [conn attr value]
  (->> (event-log-table/eids-for-attribute-value conn attr value)
       (keep #(current-entity conn %))))

(defn entities-with-attribute
  "Load all entities that have ever asserted a given attribute, folded to
  their current state."
  [conn attr]
  (->> (event-log-table/eids-for-attribute conn attr)
       (keep #(current-entity conn %))))

(defn child-entities
  "Load all child entities (postings, root tags, posting tags) for root-eid."
  [conn root-eid]
  (let [posting-eids (event-log-table/eids-for-attribute-value
                      conn "posting/parent-eid" root-eid)
        root-tag-eids (event-log-table/eids-for-attribute-value
                       conn "tag/parent-eid" root-eid)
        posting-tag-eids (mapcat
                          (fn [posting-eid]
                            (event-log-table/eids-for-attribute-value
                             conn "tag/parent-eid" posting-eid))
                          posting-eids)
        all-descendant-eids (concat posting-eids root-tag-eids posting-tag-eids)]
    (into {}
          (keep (fn [deid]
                  (when-let [de (current-entity conn deid)]
                    [deid de])))
          all-descendant-eids)))
