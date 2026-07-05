(ns hledger-immutable.encoder-test
  (:require [clojure.test :refer [deftest is]]
            [hledger-immutable.encoder :as encoder]
            [hledger-immutable.schemas :as schemas]))

;; ---------------------------------------------------------------------------
;; Input validation
;; ---------------------------------------------------------------------------

(deftest validate-input-accepts-valid-commodity
  (is (= {:file "main.journal" :name "EUR"}
         (schemas/validate-add-input! schemas/commodity-type
                                      {:file "main.journal" :name "EUR"}))))

(deftest validate-input-rejects-missing-required-field
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Invalid business input"
       (schemas/validate-add-input! schemas/commodity-type
                                    {:file "main.journal"}))))

(deftest validate-input-rejects-mutually-exclusive-after-options
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"mutually exclusive"
       (schemas/validate-add-input! schemas/commodity-type
                                    {:file "main.journal" :name "EUR"
                                     :after_eid 1 :before_eid 2}))))

(deftest validate-input-rejects-after-position
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"after_position is not supported"
       (schemas/validate-add-input! schemas/commodity-type
                                    {:file "main.journal" :name "EUR"
                                     :after_position 1000}))))

(deftest validate-input-accepts-transaction-with-postings-and-tags
  (is (map? (schemas/validate-add-input!
             schemas/transaction-type
             {:file "2026.journal" :date "2026-06-24" :description "Lunch"
              :postings [{:account "expenses:food" :amount "INR 500"}]}))))

;; ---------------------------------------------------------------------------
;; encode-add — simple entities
;; ---------------------------------------------------------------------------

(deftest encode-add-commodity-produces-correct-datoms
  (is (= [[1 "entity/type" "commodity" false]
          [1 "entity/file" "main.journal" false]
          [1 "entity/position" 1000 false]
          [1 "commodity/name" "EUR" false]]
         (encoder/encode-add-simple
          schemas/commodity-type
          {:file "main.journal" :name "EUR"}
          {:eid 1 :position 1000}))))

(deftest encode-add-price-produces-correct-datoms
  (is (= [[1 "entity/type" "price" false]
          [1 "entity/file" "prices.journal" false]
          [1 "entity/position" 1000 false]
          [1 "price/date" "2026-06-24" false]
          [1 "price/commodity" "USD" false]
          [1 "price/value" "INR 83.50" false]]
         (encoder/encode-add-simple
          schemas/price-type
          {:file "prices.journal" :date "2026-06-24"
           :commodity "USD" :value "INR 83.50"}
          {:eid 1 :position 1000}))))

(deftest encode-add-account-produces-correct-datoms
  (is (= [[1 "entity/type" "account" false]
          [1 "entity/file" "accounts.journal" false]
          [1 "entity/position" 1000 false]
          [1 "account/name" "assets:bank" false]]
         (encoder/encode-add-simple
          schemas/account-type
          {:file "accounts.journal" :name "assets:bank"}
          {:eid 1 :position 1000}))))

(deftest encode-add-include-produces-correct-datoms
  (is (= [[1 "entity/type" "include" false]
          [1 "entity/file" "main.journal" false]
          [1 "entity/position" 1000 false]
          [1 "include/file" "prices.journal" false]]
         (encoder/encode-add-simple
          schemas/include-type
          {:file "main.journal" :path "prices.journal"}
          {:eid 1 :position 1000}))))

(deftest encode-add-alias-produces-correct-datoms
  (is (= [[1 "entity/type" "alias" false]
          [1 "entity/file" "main.journal" false]
          [1 "entity/position" 1000 false]
          [1 "alias/old" "bank" false]
          [1 "alias/new" "assets:bank:hdfc" false]]
         (encoder/encode-add-simple
          schemas/alias-type
          {:file "main.journal" :old "bank" :new "assets:bank:hdfc"}
          {:eid 1 :position 1000}))))

;; ---------------------------------------------------------------------------
;; encode-add — transaction with postings and tags
;; ---------------------------------------------------------------------------

(deftest encode-add-transaction-produces-root-postings-and-tags
  (let [input {:file "2026.journal" :date "2026-06-24" :description "Lunch"
               :status "*"
               :tags [{:name "project" :value "office"}]
               :postings [{:account "expenses:food" :amount "INR 500"
                           :tags [{:name "meal" :value "lunch"}]}
                          {:account "liabilities:card"}]}
        eid-ctx {:root-eid 100
                 :posting-eids [101 102]
                 :tag-eids {:root [103] :postings [[104] []]}
                 :positions {:root 100000
                             :postings [101000 102000]
                             :tags {:root [103000] :postings [[104000] []]}}}
        datoms (encoder/encode-add-transaction-like schemas/transaction-type
                                                    input
                                                    eid-ctx)]
    (is (some #(= [100 "entity/type" "transaction" false] %) datoms))
    (is (some #(= [100 "entity/file" "2026.journal" false] %) datoms))
    (is (some #(= [100 "entity/position" 100000 false] %) datoms))
    (is (some #(= [100 "transaction/date" "2026-06-24" false] %) datoms))
    (is (some #(= [100 "transaction/description" "Lunch" false] %) datoms))
    (is (some #(= [100 "transaction/status" "*" false] %) datoms))
    (is (some #(= [101 "entity/type" "posting" false] %) datoms))
    (is (some #(= [101 "posting/parent-eid" 100 false] %) datoms))
    (is (some #(= [101 "posting/position" 101000 false] %) datoms))
    (is (some #(= [101 "posting/account" "expenses:food" false] %) datoms))
    (is (some #(= [101 "posting/amount" "INR 500" false] %) datoms))
    (is (some #(= [102 "posting/account" "liabilities:card" false] %) datoms))
    (is (some #(= [103 "entity/type" "tag" false] %) datoms))
    (is (some #(= [103 "tag/parent-eid" 100 false] %) datoms))
    (is (some #(= [103 "tag/position" 103000 false] %) datoms))
    (is (some #(= [103 "tag/name" "project" false] %) datoms))
    (is (some #(= [103 "tag/value" "office" false] %) datoms))
    (is (some #(= [104 "entity/type" "tag" false] %) datoms))
    (is (some #(= [104 "tag/parent-eid" 101 false] %) datoms))
    (is (some #(= [104 "tag/position" 104000 false] %) datoms))
    (is (some #(= [104 "tag/name" "meal" false] %) datoms))
    (is (some #(= [104 "tag/value" "lunch" false] %) datoms))))

(deftest encode-add-periodic-transaction-maps-period-to-expression
  (let [input {:file "budget.journal" :period "monthly"
               :postings [{:account "expenses:rent" :amount "INR 30000"}]}
        eid-ctx {:root-eid 1
                 :posting-eids [2]
                 :tag-eids {:root [] :postings [[]]}
                 :positions {:root 1000
                             :postings [2000]
                             :tags {:root [] :postings [[]]}}}
        datoms (encoder/encode-add-transaction-like
                schemas/periodic-transaction-type
                input
                eid-ctx)]
    (is (some #(= [1 "periodic/expression" "monthly" false] %) datoms))
    (is (not (some #(= "transaction/date" (nth % 1)) datoms)))))

(deftest encode-add-budget-is-alias-for-periodic-transaction
  (let [input {:file "budget.journal" :period "monthly"
               :postings [{:account "expenses:rent" :amount "INR 30000"}]}
        eid-ctx {:root-eid 1
                 :posting-eids [2]
                 :tag-eids {:root [] :postings [[]]}
                 :positions {:root 1000
                             :postings [2000]
                             :tags {:root [] :postings [[]]}}}
        budget-datoms (encoder/encode-add-transaction-like
                       schemas/periodic-transaction-type
                       input
                       eid-ctx)
        periodic-datoms (encoder/encode-add-transaction-like
                         schemas/periodic-transaction-type
                         input
                         eid-ctx)]
    (is (= budget-datoms periodic-datoms))))

;; ---------------------------------------------------------------------------
;; encode-amend — patch to retract+assert datoms
;; ---------------------------------------------------------------------------

(def commodity-entity
  {:eid 1 :attributes {"entity/type" "commodity"
                       "entity/file" "main.journal"
                       "entity/position" 1000
                       "commodity/name" "EUR"}})

(deftest encode-amend-generates-retract-then-assert-for-changed-attr
  (is (= [[1 "commodity/name" "EUR" true]
          [1 "commodity/name" "USD" false]]
         (encoder/encode-amend
          schemas/commodity-type
          commodity-entity
          {:set {"commodity/name" "USD"} :unset []}))))

(deftest encode-amend-skips-unchanged-values
  (is (= []
         (encoder/encode-amend
          schemas/commodity-type
          commodity-entity
          {:set {"commodity/name" "EUR"} :unset []}))))

(deftest encode-amend-asserts-new-attr-without-retract
  (is (= [[1 "entity/file" "other.journal" false]]
         (encoder/encode-amend
          schemas/commodity-type
          {:eid 1 :attributes {"entity/type" "commodity"}}
          {:set {"entity/file" "other.journal"} :unset []}))))

(deftest encode-amend-generates-retraction-for-unset
  (is (= [[1 "commodity/name" "EUR" true]]
         (encoder/encode-amend
          schemas/commodity-type
          commodity-entity
          {:set {} :unset ["commodity/name"]}))))

(deftest encode-amend-skips-unset-for-attrs-not-currently-set
  (is (= []
         (encoder/encode-amend
          schemas/commodity-type
          {:eid 1 :attributes {"entity/type" "commodity"
                               "commodity/name" "EUR"}}
          {:set {} :unset ["entity/file"]}))))

(deftest encode-amend-rejects-disallowed-attributes
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"not amendable"
       (encoder/encode-amend
        schemas/commodity-type
        commodity-entity
        {:set {"posting/account" "assets:cash"} :unset []}))))

(deftest encode-amend-rejects-entity-type-change
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"not amendable"
       (encoder/encode-amend
        schemas/commodity-type
        commodity-entity
        {:set {"entity/type" "price"} :unset []}))))

(deftest encode-amend-dispatch-routes-to-correct-function
  (is (= [[1 "commodity/name" "EUR" true]
          [1 "commodity/name" "USD" false]]
         (encoder/encode-amend schemas/commodity-type commodity-entity
                               {:set {"commodity/name" "USD"} :unset []})))
  (is (thrown?
       clojure.lang.ExceptionInfo
       (encoder/encode-amend "unknown-type" commodity-entity
                             {:set {} :unset []}))))

(deftest encode-amend-posting-allows-parent-and-position
  (let [posting {:eid 10 :attributes {"entity/type" "posting"
                                       "posting/parent-eid" 1
                                       "posting/position" 10000
                                       "posting/account" "assets:cash"}}]
    (is (= [[10 "posting/account" "assets:cash" true]
            [10 "posting/account" "assets:bank" false]]
           (encoder/encode-amend schemas/posting-type posting
                                 {:set {"posting/account" "assets:bank"} :unset []})))))

(deftest amendable-attributes-includes-system-attrs
  (is (contains? (schemas/amendable-attributes schemas/commodity-type) "entity/file"))
  (is (contains? (schemas/amendable-attributes schemas/commodity-type) "entity/position"))
  (is (contains? (schemas/amendable-attributes schemas/posting-type) "posting/parent-eid"))
  (is (contains? (schemas/amendable-attributes schemas/posting-type) "posting/position"))
  (is (contains? (schemas/amendable-attributes schemas/tag-type) "tag/parent-eid"))
  (is (contains? (schemas/amendable-attributes schemas/tag-type) "tag/position")))
