(ns hledger-immutable.position-test
  (:require [clojure.test :refer [deftest is]]
            [hledger-immutable.position :as position]))

(deftest automatic-position-derivation
  (is (= 5000000000 (position/automatic-position 5000000)))
  (is (true? (position/automatic-position? 5000000000)))
  (is (false? (position/automatic-position? 5000000001)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"safe integer range"
       (position/automatic-position 9007199254741))))

(deftest position-attribute-mapping
  (is (= "entity/position" (position/position-attribute "commodity")))
  (is (= "entity/position" (position/position-attribute "transaction")))
  (is (= "posting/position" (position/position-attribute "posting")))
  (is (= "tag/position" (position/position-attribute "tag"))))

(deftest parent-attribute-mapping
  (is (= "posting/parent-eid" (position/parent-attribute "posting")))
  (is (= "tag/parent-eid" (position/parent-attribute "tag")))
  (is (nil? (position/parent-attribute "commodity"))))

(deftest ordering-scope-for-top-level-and-child-entities
  (is (= [:workspace]
         (position/ordering-scope
          {:attributes {"entity/type" "commodity"}})))
  (is (= [:workspace]
         (position/ordering-scope
          {:attributes {"entity/type" "transaction"}})))
  (is (= ["posting" 10]
         (position/ordering-scope
          {:attributes {"entity/type" "posting"
                        "posting/parent-eid" 10}})))
  (is (= ["tag" 20]
         (position/ordering-scope
          {:attributes {"entity/type" "tag"
                        "tag/parent-eid" 20}}))))

(def scope-entities
  [{:eid 1 :attributes {"entity/type" "commodity" "entity/position" 1000}}
   {:eid 2 :attributes {"entity/type" "commodity" "entity/position" 2000}}
   {:eid 3 :attributes {"entity/type" "price" "entity/position" 1500}}])

(deftest occupied-in-scope-returns-position-to-eid-map
  (is (= {1000 1 2000 2 1500 3}
         (position/occupied-in-scope scope-entities [:workspace]))))

(deftest find-collision-returns-colliding-eid
  (is (= 2
         (position/find-collision
          {:occupied {2000 2} :desired-position 2000 :exclude-eid 1})))
  (is (nil?
       (position/find-collision
        {:occupied {2000 2} :desired-position 2000 :exclude-eid 2})))
  (is (nil?
       (position/find-collision
        {:occupied {2000 2} :desired-position 3000 :exclude-eid 1}))))

(deftest validate-reservation-rejects-manual-multiples-of-1000
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"reserved"
       (position/validate-reservation!
        {:eid 1 :current-position 1000
         :desired-position 5000 :position-changed? true})))
  (is (nil?
       (position/validate-reservation!
        {:eid 1 :current-position 1000
         :desired-position 1000 :position-changed? false})))
  (is (nil?
       (position/validate-reservation!
        {:eid 1 :current-position 1000
         :desired-position 1500 :position-changed? true}))))

(deftest validate-availability-rejects-occupied-positions
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"already occupied"
       (position/validate-availability!
        {:occupied {2000 2} :desired-position 2000
         :exclude-eid 1 :position-supplied? true :parent-supplied? false})))
  (is (nil?
       (position/validate-availability!
        {:occupied {2000 2} :desired-position 2000
         :exclude-eid 2 :position-supplied? true :parent-supplied? false})))
  (is (nil?
       (position/validate-availability!
        {:occupied {} :desired-position nil
         :exclude-eid 1 :position-supplied? false :parent-supplied? false}))))

(defn- positioned-commodity [eid position]
  {:eid eid
   :attributes (cond-> {"entity/type" "commodity"}
                 position (assoc "entity/position" position))})

(defn- entity-position [entity]
  (get-in entity [:attributes "entity/position"]))

(defn- apply-position-changes [entities changes]
  (mapv (fn [entity]
          (if-let [new-position (get changes (:eid entity))]
            (assoc-in entity [:attributes "entity/position"] new-position)
            entity))
        entities))

(defn- insert-after
  [entities anchor-eid target-eid]
  (let [position-changes (position/plan-position-changes
                          {:entities entities
                           :target-eid target-eid
                           :position-attr "entity/position"
                           :after-eid anchor-eid})]
    (apply-position-changes
     (conj entities (positioned-commodity target-eid nil))
     position-changes)))

(defn- insert-before
  [entities anchor-eid target-eid]
  (let [position-changes (position/plan-position-changes
                          {:entities entities
                           :target-eid target-eid
                           :position-attr "entity/position"
                           :before-eid anchor-eid})]
    (apply-position-changes
     (conj entities (positioned-commodity target-eid nil))
     position-changes)))

(deftest plan-position-changes-places-target-between-neighboring-eids
  (let [entities [(positioned-commodity 1 1000)
                  (positioned-commodity 2 2000)]]
    (is (= {3 1900}
           (position/plan-position-changes
            {:entities entities
             :target-eid 3
             :position-attr "entity/position"
             :after-eid 1})))
    (is (= {3 1100}
           (position/plan-position-changes
            {:entities entities
             :target-eid 3
             :position-attr "entity/position"
             :before-eid 2})))))

(deftest plan-position-changes-rebalances-smallest-window
  (let [entities [(positioned-commodity 1 1000)
                  (positioned-commodity 2 1001)
                  (positioned-commodity 3 2000)]]
    (is (= {4 1333 2 1666}
           (position/plan-position-changes
            {:entities entities
             :target-eid 4
             :position-attr "entity/position"
             :after-eid 1})))))

(deftest plan-position-changes-is-noop-when-target-is-already-adjacent
  (let [entities [(positioned-commodity 1 1000)
                  (positioned-commodity 2 1500)
                  (positioned-commodity 3 2000)]]
    (is (= {}
           (position/plan-position-changes
            {:entities entities
             :target-eid 2
             :position-attr "entity/position"
             :after-eid 1})))
    (is (= {}
           (position/plan-position-changes
            {:entities entities
             :target-eid 2
             :position-attr "entity/position"
             :before-eid 3})))))

(deftest plan-position-changes-supports-continuous-rebalancing
  (let [insert-count 200
        scenarios [{:anchor-eid 1
                    :insert-fn insert-after}
                   {:anchor-eid 2
                    :insert-fn insert-before}]]
    (doseq [{:keys [anchor-eid insert-fn]} scenarios]
      (let [entities (reduce (fn [entities target-eid]
                               (insert-fn entities anchor-eid target-eid))
                             [(positioned-commodity 1 1000)
                              (positioned-commodity 2 2000)]
                             (range 3 (+ 3 insert-count)))
            positions (mapv entity-position entities)
            inserted-positions (mapv entity-position
                                     (remove #(#{1 2} (:eid %)) entities))
            ordered (sort-by (juxt entity-position :eid) entities)]
        (is (= (+ 2 insert-count) (count entities)))
        (is (= (count positions) (count (set positions))))
        (is (= 1000 (entity-position (first ordered))))
        (is (= 1 (:eid (first ordered))))
        (is (= 2000 (entity-position (last ordered))))
        (is (= 2 (:eid (last ordered))))
        (is (every? #(< 1000 % 2000) inserted-positions))
        (is (every? (complement position/automatic-position?) inserted-positions))))))

(deftest plan-position-changes-rejects-invalid-anchor-input
  (let [entities [(positioned-commodity 1 1000)
                  (positioned-commodity 2 2000)]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"mutually exclusive"
         (position/plan-position-changes
          {:entities entities
           :target-eid 3
           :position-attr "entity/position"
           :after-eid 1
           :before-eid 2})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown entity"
         (position/plan-position-changes
          {:entities entities
           :target-eid 3
           :position-attr "entity/position"
           :after-eid 99})))))
