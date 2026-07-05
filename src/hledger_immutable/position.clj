(ns hledger-immutable.position
  "Pure position logic for entity ordering.

  All functions are pure. Callers fetch occupied positions
  from the database and pass them as data."
  (:require [hledger-immutable.schemas :as schemas]))

(def position-step 1000)

(def max-safe-integer 9007199254740991)

(defn position-attribute
  "Return the ordering attribute owned by an entity type."
  [entity-type]
  (schemas/position-attribute entity-type))

(defn parent-attribute
  "Return the parent-eid attribute owned by a child entity type."
  [entity-type]
  (schemas/parent-attribute entity-type))

(defn automatic-position
  "Derive the reserved automatic position for a newly allocated eid."
  [eid]
  (let [position (*' eid position-step)]
    (when (> position max-safe-integer)
      (throw (ex-info "An automatic entity position exceeds the safe integer range"
                      {:eid eid
                       :position position
                       :max-safe-integer max-safe-integer})))
    (long position)))

(defn automatic-position?
  "Return true when a position is reserved for automatic eid-derived allocation."
  [position]
  (and (integer? position)
       (zero? (mod position position-step))))

(defn ordering-scope
  "Return the scope in which an entity's position must be unique.

  Top-level entities share a workspace-wide scope. Child entities are scoped
  to their siblings under the same parent."
  [folded-entity]
  (let [entity-type (get-in folded-entity [:attributes "entity/type"])]
    (if (schemas/top-level-entity-type? entity-type)
      [:workspace]
      [entity-type
       (get-in folded-entity [:attributes (parent-attribute entity-type)])])))

(defn scope-matches?
  "Return true when two ordering scopes are the same."
  [scope-a scope-b]
  (= scope-a scope-b))

(defn occupied-in-scope
  "Return a map of position to eid for entities occupying the same scope.

  entities: collection of folded entities
  scope: the ordering scope to match"
  [entities scope]
  (->> entities
       (filter #(scope-matches? scope (ordering-scope %)))
       (keep (fn [{:keys [eid attributes]}]
               (let [pos-attr (position-attribute (get attributes "entity/type"))
                     pos (get attributes pos-attr)]
                 (when (some? pos)
                   [pos eid]))))
       (into {})))

(defn find-collision
  "Return the colliding eid if desired-position is occupied in scope, nil otherwise."
  [{:keys [occupied desired-position exclude-eid]}]
  (let [colliding-eid (get occupied desired-position)]
    (when (and (some? colliding-eid)
               (not= colliding-eid exclude-eid))
      colliding-eid)))

(defn validate-position-attribute!
  "Ensure the entity amends only its own position attribute."
  [entity-type supplied-position-attributes]
  (let [own-position-attribute (position-attribute entity-type)]
    (when (some #(not= own-position-attribute %) supplied-position-attributes)
      (throw (ex-info "An entity can amend only its own position attribute"
                      {:entity-type entity-type
                       :position-attribute own-position-attribute
                       :supplied-position-attributes
                       supplied-position-attributes})))))

(defn validate-reservation!
  "Reject manual use of reserved automatic positions.

  Unchanged automatic positions may be reasserted. Changed positions cannot
  use multiples of 1000."
  [{:keys [eid current-position desired-position position-changed?]}]
  (when (and position-changed?
             (automatic-position? desired-position))
    (throw (ex-info "Multiples of 1000 are reserved for automatic entity positions"
                    {:eid eid
                     :position desired-position}))))

(defn validate-availability!
  "Reject positions already occupied in the same ordering scope."
  [{:keys [occupied desired-position exclude-eid position-supplied? parent-supplied?]}]
  (when (and (integer? desired-position)
             (or position-supplied? parent-supplied?))
    (when-let [collision (find-collision {:occupied occupied
                                           :desired-position desired-position
                                           :exclude-eid exclude-eid})]
      (throw (ex-info "An entity position is already occupied in this ordering scope"
                      {:eid exclude-eid
                       :position desired-position
                       :colliding-eid collision})))))

(defn validate-amendment!
  "Validate position changes for an entity amendment.

  current:  folded entity before amendment
  desired:  folded entity after amendment
  data:     patch map with supplied attributes
  occupied: map of position to eid for entities in the same scope"
  [current desired data occupied]
  (let [entity-type (get-in desired [:attributes "entity/type"])
        pos-attr (position-attribute entity-type)
        current-pos (get-in current [:attributes pos-attr])
        desired-pos (get-in desired [:attributes pos-attr])
        supplied-pos-attrs (filterv #(contains? data %) schemas/position-attributes)
        position-supplied? (contains? data pos-attr)
        parent-attr (parent-attribute entity-type)
        parent-supplied? (and parent-attr (contains? data parent-attr))]
    (validate-position-attribute! entity-type supplied-pos-attrs)
    (validate-reservation! {:eid (:eid current)
                            :current-position current-pos
                            :desired-position desired-pos
                            :position-changed? (and position-supplied?
                                                    (not= current-pos desired-pos))})
    (validate-availability! {:occupied occupied
                             :desired-position desired-pos
                             :exclude-eid (:eid current)
                             :position-supplied? position-supplied?
                             :parent-supplied? parent-supplied?})))

(defn- positioned-entry
  [position-attr {:keys [eid attributes]}]
  (when-let [position (get attributes position-attr)]
    {:eid eid :position position}))

(defn- sorted-positioned-entries
  [entities target-eid position-attr]
  (->> entities
       (remove #(= target-eid (:eid %)))
       (keep #(positioned-entry position-attr %))
       (sort-by (juxt :position :eid))
       vec))

(defn- anchor-index
  [entries anchor-eid relation-key]
  (let [index (first (keep-indexed (fn [idx {:keys [eid]}]
                                     (when (= anchor-eid eid) idx))
                                   entries))]
    (when-not index
      (throw (ex-info (str relation-key " references an unknown entity in this ordering scope")
                      {relation-key anchor-eid})))
    index))

(defn- manual-positions-between
  [left-boundary right-boundary count]
  (loop [index 1
         previous left-boundary
         positions []]
    (if (> index count)
      positions
      (let [raw-position (+ left-boundary
                            (quot (* index (- right-boundary left-boundary))
                                  (inc count)))
            minimum-position (max raw-position (inc previous))
            next-position (loop [candidate minimum-position]
                            (cond
                              (>= candidate right-boundary) nil
                              (automatic-position? candidate) (recur (inc candidate))
                              :else candidate))]
        (when next-position
          (recur (inc index)
                 next-position
                 (conj positions next-position)))))))

(defn- manual-position-away-from-anchor
  [left-boundary right-boundary relation-key]
  (let [gap (- right-boundary left-boundary)
        lower (inc left-boundary)
        upper (dec right-boundary)]
    (when (<= lower upper)
      (let [raw-position (if (= relation-key :after_eid)
                           (+ left-boundary (quot (* 9 gap) 10))
                           (+ left-boundary (quot gap 10)))
            bounded-position (-> raw-position
                                 (max lower)
                                 (min upper))]
        (loop [candidate bounded-position]
          (cond
            (or (< candidate lower) (> candidate upper))
            nil

            (automatic-position? candidate)
            (recur (if (= relation-key :after_eid)
                     (dec candidate)
                     (inc candidate)))

            :else candidate))))))

(defn- next-automatic-boundary
  [position]
  (* (inc (quot position position-step)) position-step))

(defn- previous-automatic-boundary
  [position]
  (* (quot (dec position) position-step) position-step))

(defn- manual-positions-after
  [left-boundary count relation-key]
  (loop [right-boundary (next-automatic-boundary left-boundary)]
    (or (if (= 1 count)
          (when-let [position (manual-position-away-from-anchor
                               left-boundary
                               right-boundary
                               relation-key)]
            [position])
          (manual-positions-between left-boundary right-boundary count))
        (recur (+ right-boundary position-step)))))

(defn- manual-positions-before
  [right-boundary count relation-key]
  (loop [left-boundary (previous-automatic-boundary right-boundary)]
    (or (if (= 1 count)
          (when-let [position (manual-position-away-from-anchor
                               left-boundary
                               right-boundary
                               relation-key)]
            [position])
          (manual-positions-between left-boundary right-boundary count))
        (recur (- left-boundary position-step)))))

(defn- assignable-positions
  [left-boundary right-boundary count relation-key]
  (cond
    (zero? count) []

    (and (= 1 count) left-boundary right-boundary)
    (when-let [position (manual-position-away-from-anchor left-boundary
                                                          right-boundary
                                                          relation-key)]
      [position])

    (and left-boundary right-boundary)
    (manual-positions-between left-boundary right-boundary count)

    left-boundary
    (manual-positions-after left-boundary count relation-key)

    right-boundary
    (manual-positions-before right-boundary count relation-key)

    :else
    (manual-positions-after 0 count relation-key)))

(defn- insert-at
  [entries index entry]
  (vec (concat (subvec entries 0 index)
               [entry]
               (subvec entries index))))

(defn- window-ranges
  [entry-count insert-index relation-key]
  (keep
   (fn [window-size]
     (if (= relation-key :after_eid)
       (let [start insert-index
             end (dec (+ start window-size))]
         (when (< end entry-count)
           [start end]))
       (let [end insert-index
             start (inc (- end window-size))]
         (when (<= 0 start)
           [start end]))))
   (range 1 (inc entry-count))))

(defn- plan-window
  [entries relation-key [start end]]
  (let [left-boundary (:position (get entries (dec start)))
        right-boundary (:position (get entries (inc end)))
        window-entries (subvec entries start (inc end))
        positions (assignable-positions left-boundary
                                        right-boundary
                                        (count window-entries)
                                        relation-key)]
    (when (= (count positions) (count window-entries))
      (->> (map vector window-entries positions)
           (keep (fn [[{:keys [eid position]} new-position]]
                   (when (not= position new-position)
                     [eid new-position])))
           (into {})))))

(defn- already-adjacent?
  [entries target-eid anchor-eid relation-key]
  (let [target-index (first (keep-indexed (fn [idx {:keys [eid]}]
                                            (when (= target-eid eid) idx))
                                          entries))
        anchor-index (first (keep-indexed (fn [idx {:keys [eid]}]
                                            (when (= anchor-eid eid) idx))
                                          entries))]
    (and target-index
         anchor-index
         (= target-index
            (if (= relation-key :after_eid)
              (inc anchor-index)
              (dec anchor-index))))))

(defn plan-position-changes
  "Plan identity-based position changes in one ordering scope."
  [{:keys [entities target-eid position-attr after-eid before-eid]}]
  (when (and after-eid before-eid)
    (throw (ex-info "after_eid and before_eid are mutually exclusive"
                    {:after-eid after-eid
                     :before-eid before-eid})))
  (let [all-entries (->> entities
                         (keep #(positioned-entry position-attr %))
                         (sort-by (juxt :position :eid))
                         vec)
        entries (sorted-positioned-entries entities target-eid position-attr)
        [anchor-eid relation-key index-offset]
        (cond
          after-eid [after-eid :after_eid 1]
          before-eid [before-eid :before_eid 0]
          :else (throw (ex-info "Either after_eid or before_eid is required"
                                {:target-eid target-eid})))
        anchor-idx (anchor-index entries anchor-eid relation-key)
        insert-index (+ anchor-idx index-offset)
        target-position (some->> entities
                                 (filter #(= target-eid (:eid %)))
                                 first
                                 :attributes
                                 (get position-attr))
        desired-entries (insert-at entries
                                   insert-index
                                   {:eid target-eid
                                    :position target-position})]
    (if (already-adjacent? all-entries target-eid anchor-eid relation-key)
      {}
      (or (some #(plan-window desired-entries relation-key %)
                (window-ranges (count desired-entries)
                               insert-index
                               relation-key))
          (throw (ex-info "No position window available for reordering"
                          {:target-eid target-eid
                           :after-eid after-eid
                           :before-eid before-eid}))))))
