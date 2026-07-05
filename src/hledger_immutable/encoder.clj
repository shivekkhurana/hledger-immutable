(ns hledger-immutable.encoder
  "Pure translation of business-shaped JSON into flat authored datoms.

  encode-add functions take validated business input plus allocated eids and
  resolved positions, and return assertion datoms.

  encode-amend-* functions take a current folded entity plus a patch, and return
  retract-then-assert datoms.

  No database access, no projection."
  (:require [hledger-immutable.schemas :as schemas]))

;; ---------------------------------------------------------------------------
;; Encode-add helpers
;; ---------------------------------------------------------------------------

(defn- top-level-system-datoms
  [eid entity-type file position]
  [[eid "entity/type" entity-type false]
   [eid "entity/file" file false]
   [eid "entity/position" position false]])

(defn- mapped-attribute-datoms
  [eid input mappings]
  (into []
        (keep (fn [[input-key attr]]
                (when (contains? input input-key)
                  [eid attr (get input input-key) false])))
        mappings))

(defn- encode-tag-datoms
  [tag-eid parent-eid tag-position tag-input]
  (cond-> [[tag-eid "entity/type" schemas/tag-type false]
           [tag-eid "tag/parent-eid" parent-eid false]
           [tag-eid "tag/position" tag-position false]
           [tag-eid "tag/name" (:name tag-input) false]]
    (contains? tag-input :value)
    (conj [tag-eid "tag/value" (:value tag-input) false])))

(defn- encode-tags
  [tag-eids parent-eid tag-positions tags]
  (mapcat (fn [tag-input tag-eid tag-pos]
            (encode-tag-datoms tag-eid parent-eid tag-pos tag-input))
          (or tags [])
          tag-eids
          tag-positions))

(defn encode-add-tag
  [input {:keys [eid position]}]
  (let [tag-input (cond-> {:name (:key input)}
                    (contains? input :value)
                    (assoc :value (:value input)))]
    (vec (encode-tag-datoms eid (:parent-eid input) position tag-input))))

(defn- encode-posting-datoms
  [posting-eid parent-eid posting-position posting-input]
  (cond-> [[posting-eid "entity/type" schemas/posting-type false]
           [posting-eid "posting/parent-eid" parent-eid false]
           [posting-eid "posting/position" posting-position false]
           [posting-eid "posting/account" (:account posting-input) false]]
    (contains? posting-input :amount)
    (conj [posting-eid "posting/amount" (:amount posting-input) false])
    (contains? posting-input :status)
    (conj [posting-eid "posting/status" (:status posting-input) false])))

(defn- encode-postings
  [parent-eid posting-eids posting-positions posting-tag-eids posting-tag-positions postings]
  (mapcat (fn [posting-input posting-eid posting-pos tag-eids tag-positions]
            (let [posting-datoms (encode-posting-datoms posting-eid parent-eid posting-pos posting-input)
                  tag-datoms (encode-tags tag-eids posting-eid tag-positions (:tags posting-input))]
              (concat posting-datoms tag-datoms)))
          (or postings [])
          posting-eids
          posting-positions
          posting-tag-eids
          posting-tag-positions))

;; ---------------------------------------------------------------------------
;; Encode-add functions — simple top-level entities
;; ---------------------------------------------------------------------------

(defn encode-add-simple
  [entity-type input {:keys [eid position]}]
  (vec
   (concat
    (top-level-system-datoms eid entity-type (:file input) position)
    (mapped-attribute-datoms
     eid
     input
     (get schemas/simple-add-attribute-mappings entity-type)))))

;; ---------------------------------------------------------------------------
;; Encode-add functions — transaction-like entities
;; ---------------------------------------------------------------------------

(defn encode-add-transaction-like
  [entity-type input {:keys [root-eid posting-eids tag-eids positions]}]
  (let [root-pos (:root positions)
        root-tag-eids (:root tag-eids [])
        root-tag-positions (get-in positions [:tags :root] [])
        root-tags (:tags input)
        root-attribute-mapping (get schemas/transaction-like-add-attribute-mappings
                                    entity-type)
        root-datoms (concat
                     (top-level-system-datoms root-eid entity-type (:file input) root-pos)
                     (mapped-attribute-datoms root-eid input root-attribute-mapping))
        root-tag-datoms (encode-tags
                         root-tag-eids root-eid root-tag-positions root-tags)
        posting-tag-eids (or (:postings tag-eids)
                              (repeat (count posting-eids) []))
        posting-tag-positions (get-in positions [:tags :postings]
                                     (repeat (count posting-eids) []))
        posting-datoms (encode-postings
                        root-eid
                        posting-eids
                        (:postings positions)
                        posting-tag-eids
                        posting-tag-positions
                        (:postings input))]
    (vec (concat root-datoms root-tag-datoms posting-datoms))))

;; ---------------------------------------------------------------------------
;; Encode-amend functions
;; ---------------------------------------------------------------------------

(defn- encode-amend-common
  [entity-type current-entity patch]
  (schemas/validate-patch-attributes! entity-type patch)
  (let [eid (:eid current-entity)
        current-attrs (:attributes current-entity)
        {:keys [set unset]} patch
        set-datoms (mapcat
                    (fn [[attr new-value]]
                      (let [old-value (get current-attrs attr)]
                        (cond
                          (= old-value new-value)
                          []

                          (some? old-value)
                          [[eid attr old-value true]
                           [eid attr new-value false]]

                          :else
                          [[eid attr new-value false]])))
                    (or set {}))
        unset-datoms (keep
                      (fn [attr]
                        (let [old-value (get current-attrs attr)]
                          (when (some? old-value)
                            [eid attr old-value true])))
                      (or unset []))]
    (vec (concat set-datoms unset-datoms))))

(defn encode-amend
  "Encode a patch for an entity type into retraction/assertion datoms."
  [entity-type current-entity patch]
  (when-not (schemas/supported-entity-type? entity-type)
    (throw (ex-info "No encode-amend function for entity type"
                    {:entity-type entity-type})))
  (encode-amend-common entity-type current-entity patch))
