(ns hledger-immutable.entity-test
  (:require [clojure.test :refer [deftest is]]
            [hledger-immutable.entity :as entity]))

(def sample-transaction-entities
  {10 {:eid 10
       :sequence 10
       :attributes {"entity/type" "transaction"
                    "entity/position" 1000
                    "transaction/date" "2026-06-23"
                    "transaction/status" "*"
                    "transaction/code" "INV-123"
                    "transaction/description" "Amazon | laptop"}}
   101 {:eid 101
        :sequence 101
        :attributes {"entity/type" "posting"
                     "posting/parent-eid" 10
                     "posting/position" 1000
                     "posting/account" "expenses:equipment"
                     "posting/amount" "INR 90000"}}
   102 {:eid 102
        :sequence 102
        :attributes {"entity/type" "posting"
                     "posting/parent-eid" 10
                     "posting/position" 2000
                     "posting/account" "liabilities:card"}}
   201 {:eid 201
        :sequence 201
        :attributes {"entity/type" "tag"
                     "tag/parent-eid" 10
                     "tag/position" 1000
                     "tag/name" "project"
                     "tag/value" "office"}}
   202 {:eid 202
        :sequence 202
        :attributes {"entity/type" "tag"
                     "tag/parent-eid" 101
                     "tag/position" 1000
                     "tag/name" "asset"
                     "tag/value" "laptop"}}})

(deftest render-entity-renders-supported-entity-bodies
  (is (= "commodity EUR"
         (entity/render-entity
          {"entity/type" "commodity"
           "commodity/name" "EUR"})))
  (is (= "P 2026-06-22 EUR 0.8"
         (entity/render-entity
          {"entity/type" "price"
           "price/date" "2026-06-22"
           "price/commodity" "EUR"
           "price/value" "0.8"})))
  (is (= "account assets:bank"
         (entity/render-entity
          {"entity/type" "account"
           "account/name" "assets:bank"})))
  (is (= "include prices.journal"
         (entity/render-entity
          {"entity/type" "include"
           "include/file" "prices.journal"})))
  (is (= "decimal-mark ."
         (entity/render-entity
          {"entity/type" "decimal-mark"
           "decimal-mark/value" "."})))
  (is (= "alias bank = assets:bank:hdfc"
         (entity/render-entity
          {"entity/type" "alias"
           "alias/old" "bank"
           "alias/new" "assets:bank:hdfc"})))
  (is (= "D INR 1,000.00"
         (entity/render-entity
          {"entity/type" "default-commodity"
           "default-commodity/value" "INR 1,000.00"})))
  (is (= "payee Amazon"
         (entity/render-entity
          {"entity/type" "payee"
           "payee/name" "Amazon"})))
  (is (= "tag project"
         (entity/render-entity
          {"entity/type" "tag-declaration"
           "tag-declaration/name" "project"}))))

(deftest render-entity-entry-renders-transactions-with-child-postings-and-tags
  (let [entry (entity/render-entity-entry
               (get sample-transaction-entities 10)
               sample-transaction-entities)]
    (is (= (str "2026-06-23 * (INV-123) Amazon | laptop ; project: office\n"
                "    expenses:equipment  INR 90000 ; asset: laptop\n"
                "    liabilities:card")
           (:body entry)))
    (is (= {"entity/position" 1000
            :tags [{:eid 201
                    :position 1000
                    :name "project"
                    :value "office"}]
            :postings [{:eid 101
                        :position 1000
                        :tags [{:eid 202
                                :position 1000
                                :name "asset"
                                :value "laptop"}]}
                       {:eid 102
                        :position 2000}]}
           (:metadata entry)))))

(deftest render-entity-rejects-incomplete-or-unknown-entities
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"incomplete entity"
       (entity/render-entity {"entity/type" "price"})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"unknown type"
       (entity/render-entity {"entity/type" :future-transaction}))))

(deftest parse-entity-parses-supported-entity-bodies
  (is (= {"entity/type" "commodity"
          "commodity/name" "EUR"}
         (entity/parse-entity "commodity EUR")))
  (is (= {"entity/type" "price"
          "price/date" "2026-06-22"
          "price/commodity" "EUR"
          "price/value" "0.8"}
         (entity/parse-entity "P 2026-06-22 EUR 0.8")))
  (is (= {"entity/type" "account"
          "account/name" "assets:bank"}
         (entity/parse-entity "account assets:bank")))
  (is (= {"entity/type" "include"
          "include/file" "prices.journal"}
         (entity/parse-entity "include prices.journal")))
  (is (= {"entity/type" "decimal-mark"
          "decimal-mark/value" ","}
         (entity/parse-entity "decimal-mark ,")))
  (is (= {"entity/type" "alias"
          "alias/old" "bank"
          "alias/new" "assets:bank:hdfc"}
         (entity/parse-entity "alias bank = assets:bank:hdfc")))
  (is (= {"entity/type" "default-commodity"
          "default-commodity/value" "INR 1,000.00"}
         (entity/parse-entity "D INR 1,000.00")))
  (is (= {"entity/type" "payee"
          "payee/name" "Amazon"}
         (entity/parse-entity "payee Amazon")))
  (is (= {"entity/type" "tag-declaration"
          "tag-declaration/name" "project"}
         (entity/parse-entity "tag project"))))

(deftest parse-entity-recovers-child-identities-from-metadata
  (let [{:keys [body metadata]}
        (entity/render-entity-entry
         (get sample-transaction-entities 10)
         sample-transaction-entities)]
    (is (= sample-transaction-entities
           (entity/parse-entity 10 body metadata)))))

(deftest parse-entity-rejects-metadata-that-disagrees-with-visible-tags
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Visible tags do not match"
       (entity/parse-entity
        10
        "commodity INR ; type: currency"
        {"entity/position" 1000
         :tags [{:eid 201
                 :position 1000
                 :name "type"
                 :value "security"}]}))))

(deftest parse-entity-rejects-unsupported-entity-bodies
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Cannot parse this price entity"
       (entity/parse-entity "P malformed")))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Cannot parse this journal entity"
       (entity/parse-entity "txn unsupported"))))

(deftest fold-datoms-creates-and-updates-entities
  (is (= {1 {:sequence 0
             :eid 1
             :attributes {"entity/type" "price"
                          "entity/file" "prices.journal"
                          "entity/position" 1000
                          "price/value" "0.9"}}}
         (reduce entity/fold-datoms
                 {}
                 [[0 1 "entity/type" "price" false]
                  [1 1 "entity/file" "prices.journal" false]
                  [2 1 "entity/position" 1000 false]
                  [3 1 "price/value" "0.8" false]
                  [4 1 "price/value" "0.9" false]]))))

(deftest fold-datoms-retracts-only-the-current-value
  (is (= {1 {:sequence 0
             :eid 1
             :attributes {"entity/type" "price"
                          "entity/file" "prices.journal"
                          "price/value" "0.9"}}}
         (reduce entity/fold-datoms
                 {}
                 [[0 1 "entity/type" "price" false]
                  [1 1 "entity/file" "prices.journal" false]
                  [2 1 "entity/position" 1000 false]
                  [3 1 "entity/position" 2000 true]
                  [4 1 "entity/position" 1000 true]
                  [5 1 "price/value" "0.8" false]
                  [6 1 "price/value" "9.9" true]
                  [7 1 "price/value" "0.8" true]
                  [8 1 "price/value" "0.9" false]]))))

(deftest fold-datoms-removes-an-entity-after-its-final-attribute-is-retracted
  (is (= {}
         (reduce entity/fold-datoms
                 {}
                 [[0 1 "entity/file" "commodities.journal" false]
                  [1 1 "entity/type" "commodity" false]
                  [2 1 "commodity/name" "EUR" false]
                  [3 1 "entity/file" "commodities.journal" true]
                  [4 1 "entity/type" "commodity" true]
                  [5 1 "commodity/name" "EUR" true]]))))
