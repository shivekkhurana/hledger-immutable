(ns hledger-immutable.schemas
  "Static entity contracts and schema validation."
  (:require [clojure.set :as set]
            [malli.core :as m]))

(def commodity-type "commodity")
(def price-type "price")
(def account-type "account")
(def transaction-type "transaction")
(def periodic-transaction-type "periodic-transaction")
(def include-type "include")
(def decimal-mark-type "decimal-mark")
(def alias-type "alias")
(def default-commodity-type "default-commodity")
(def payee-type "payee")
(def tag-declaration-type "tag-declaration")
(def posting-type "posting")
(def tag-type "tag")

(def top-level-entity-types
  #{commodity-type
    price-type
    account-type
    transaction-type
    periodic-transaction-type
    include-type
    decimal-mark-type
    alias-type
    default-commodity-type
    payee-type
    tag-declaration-type})

(def child-entity-types #{posting-type tag-type})

(def integer-valued-attributes
  #{"entity/position"
    "posting/parent-eid"
    "posting/position"
    "tag/parent-eid"
    "tag/position"})

(def string-valued-attributes
  #{"entity/type"})

(def position-attributes
  #{"entity/position" "posting/position" "tag/position"})

(def child-parent-attributes
  {posting-type "posting/parent-eid"
   tag-type "tag/parent-eid"})

(def attribute-registry
  "Body attributes owned by each entity type, split into required and optional."
  {commodity-type {:required #{"commodity/name"}
                   :optional #{}}
   price-type {:required #{"price/date" "price/commodity" "price/value"}
               :optional #{}}
   account-type {:required #{"account/name"}
                 :optional #{}}
   transaction-type {:required #{"transaction/date" "transaction/description"}
                     :optional #{"transaction/status" "transaction/code"}}
   periodic-transaction-type {:required #{"periodic/expression"}
                              :optional #{"periodic/description"}}
   include-type {:required #{"include/file"}
                 :optional #{}}
   decimal-mark-type {:required #{"decimal-mark/value"}
                      :optional #{}}
   alias-type {:required #{"alias/old" "alias/new"}
               :optional #{}}
   default-commodity-type {:required #{"default-commodity/value"}
                           :optional #{}}
   payee-type {:required #{"payee/name"}
               :optional #{}}
   tag-declaration-type {:required #{"tag-declaration/name"}
                         :optional #{}}
   posting-type {:required #{"posting/account"}
                 :optional #{"posting/status" "posting/amount"}}
   tag-type {:required #{"tag/name"}
             :optional #{"tag/value"}}})

(def simple-add-attribute-mappings
  "Ordered input-key to datom-attribute mappings for simple top-level add flows."
  {commodity-type [[:name "commodity/name"]]
   price-type [[:date "price/date"]
               [:commodity "price/commodity"]
               [:value "price/value"]]
   account-type [[:name "account/name"]]
   include-type [[:path "include/file"]]
   alias-type [[:old "alias/old"]
               [:new "alias/new"]]
   decimal-mark-type [[:value "decimal-mark/value"]]
   default-commodity-type [[:value "default-commodity/value"]]
   payee-type [[:name "payee/name"]]
   tag-declaration-type [[:name "tag-declaration/name"]]})

(def transaction-like-entity-types
  #{transaction-type periodic-transaction-type})

(def transaction-add-attribute-mapping
  [[:date "transaction/date"]
   [:description "transaction/description"]
   [:status "transaction/status"]
   [:code "transaction/code"]])

(def periodic-transaction-add-attribute-mapping
  [[:period "periodic/expression"]
   [:description "periodic/description"]])

(def transaction-like-add-attribute-mappings
  {transaction-type transaction-add-attribute-mapping
   periodic-transaction-type periodic-transaction-add-attribute-mapping})

(def datom-value-schema
  [:multi {:dispatch first}
   [:integer [:tuple [:= :integer] :int]]
   [:text [:tuple [:= :text] :string]]])

(def tag-metadata-schema
  [:map
   [:eid :int]
   [:position :int]
   [:name :string]
   [:value {:optional true} :string]])

(def posting-metadata-schema
  [:map
   [:eid :int]
   [:position :int]
   [:tags {:optional true} [:vector tag-metadata-schema]]])

(def marker-metadata-schema
  [:map
   ["entity/position" {:optional true} :int]
   [:tags {:optional true} [:vector tag-metadata-schema]]
   [:postings {:optional true} [:vector posting-metadata-schema]]])

(def tag-input-schema
  [:map
   [:name :string]
   [:value {:optional true} :string]])

(def add-tag-input-schema
  [:map
   [:parent-eid :int]
   [:key :string]
   [:value {:optional true} :string]])

(def posting-input-schema
  [:map
   [:account :string]
   [:amount {:optional true} :string]
   [:status {:optional true} :string]
   [:tags {:optional true} [:vector tag-input-schema]]])

(def after-options
  [[:after_eid {:optional true} :int]
   [:before_eid {:optional true} :int]])

(def commodity-input-schema
  (into [:map [:file :string] [:name :string]] after-options))

(def price-input-schema
  (into [:map [:file :string] [:date :string] [:commodity :string] [:value :string]]
        after-options))

(def account-input-schema
  (into [:map [:file :string] [:name :string]] after-options))

(def transaction-input-schema
  (into [:map
         [:file :string]
         [:date :string]
         [:status {:optional true} :string]
         [:code {:optional true} :string]
         [:description :string]
         [:tags {:optional true} [:vector tag-input-schema]]
         [:postings [:vector posting-input-schema]]]
        after-options))

(def periodic-transaction-input-schema
  (into [:map
         [:file :string]
         [:period :string]
         [:description {:optional true} :string]
         [:tags {:optional true} [:vector tag-input-schema]]
         [:postings [:vector posting-input-schema]]]
        after-options))

(def include-input-schema
  (into [:map [:file :string] [:path :string]] after-options))

(def alias-input-schema
  (into [:map [:file :string] [:old :string] [:new :string]] after-options))

(def decimal-mark-input-schema
  (into [:map [:file :string] [:value :string]] after-options))

(def default-commodity-input-schema
  (into [:map [:file :string] [:value :string]] after-options))

(def payee-input-schema
  (into [:map [:file :string] [:name :string]] after-options))

(def tag-declaration-input-schema
  (into [:map [:file :string] [:name :string]] after-options))

(def add-input-schemas
  {commodity-type commodity-input-schema
   price-type price-input-schema
   account-type account-input-schema
   transaction-type transaction-input-schema
   periodic-transaction-type periodic-transaction-input-schema
   include-type include-input-schema
   alias-type alias-input-schema
   decimal-mark-type decimal-mark-input-schema
   default-commodity-type default-commodity-input-schema
   payee-type payee-input-schema
   tag-declaration-type tag-declaration-input-schema})

(defn position-attribute
  "Return the ordering attribute owned by an entity type."
  [entity-type]
  (cond
    (contains? top-level-entity-types entity-type) "entity/position"
    (= posting-type entity-type) "posting/position"
    (= tag-type entity-type) "tag/position"))

(defn parent-attribute
  "Return the parent-eid attribute owned by a child entity type."
  [entity-type]
  (get child-parent-attributes entity-type))

(defn top-level-entity-type?
  [entity-type]
  (contains? top-level-entity-types entity-type))

(defn child-entity-type?
  [entity-type]
  (contains? child-entity-types entity-type))

(defn supported-entity-type?
  [entity-type]
  (or (top-level-entity-type? entity-type)
      (child-entity-type? entity-type)))

(defn transaction-like-entity-type?
  [entity-type]
  (contains? transaction-like-entity-types entity-type))

(defn body-attributes
  "Return the set of all body attributes for an entity type."
  [entity-type]
  (let [entry (get attribute-registry entity-type #{})]
    (set/union (:required entry) (:optional entry))))

(defn amendable-attributes
  "Return the set of attributes an entity type can be amended with."
  [entity-type]
  (let [body (body-attributes entity-type)
        pos-attr (position-attribute entity-type)
        system-attrs (cond
                       (top-level-entity-type? entity-type)
                       #{"entity/file" pos-attr}
                       (child-entity-type? entity-type)
                       #{(parent-attribute entity-type) pos-attr}
                       :else #{})]
    (set/union body system-attrs)))

(defn datom-value-kind
  "Return the schema branch used to validate a datom value."
  [attr]
  (cond
    (contains? string-valued-attributes attr) :text
    (contains? integer-valued-attributes attr) :integer
    :else :text))

(defn valid-datom-value?
  "Return true when a datom value is valid for its attribute."
  [attr value]
  (m/validate datom-value-schema [(datom-value-kind attr) value]))

(defn validate-marker-metadata!
  [metadata]
  (when-not (m/validate marker-metadata-schema (or metadata {}))
    (throw (ex-info "Cannot parse entity marker metadata"
                    {:metadata metadata}))))

(defn validate-add-input!
  "Validate business JSON input against the schema for the given entity type."
  [entity-type input]
  (let [schema (get add-input-schemas entity-type)]
    (when-not schema
      (throw (ex-info "No input schema for entity type"
                      {:entity-type entity-type})))
    (when-not (m/validate schema input)
      (throw (ex-info "Invalid business input for entity type"
                      {:entity-type entity-type
                       :input input
                       :explain (m/explain schema input)})))
    (when (and (contains? input :after_eid)
               (contains? input :before_eid))
      (throw (ex-info "after_eid and before_eid are mutually exclusive"
                      {:input input})))
    (when (contains? input :after_position)
      (throw (ex-info "after_position is not supported for mutation input"
                      {:input input})))
    input))

(defn validate-add-tag-input!
  "Validate business input for adding a child tag entity."
  [input]
  (when-not (m/validate add-tag-input-schema input)
    (throw (ex-info "Invalid business input for tag"
                    {:entity-type tag-type
                     :input input
                     :explain (m/explain add-tag-input-schema input)})))
  input)

(defn validate-patch-attributes!
  [entity-type patch]
  (let [allowed (amendable-attributes entity-type)
        set-attrs (set (keys (:set patch)))
        unset-attrs (set (:unset patch))
        all-attrs (set/union set-attrs unset-attrs)
        disallowed (set/difference all-attrs allowed)]
    (when (seq disallowed)
      (throw (ex-info "Attributes are not amendable for this entity type"
                      {:entity-type entity-type
                       :disallowed (vec disallowed)
                       :allowed (vec allowed)})))
    (when (or (contains? set-attrs "entity/type")
              (contains? unset-attrs "entity/type"))
      (throw (ex-info "Cannot amend entity/type"
                      {:entity-type entity-type})))))
