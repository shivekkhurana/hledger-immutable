(ns hledger-immutable.schemas-test
  (:require [clojure.test :refer [deftest is]]
            [hledger-immutable.schemas :as schemas]))

(deftest validate-add-input-accepts-valid-commodity
  (is (= {:file "main.journal" :name "EUR"}
         (schemas/validate-add-input! "commodity"
                                      {:file "main.journal" :name "EUR"}))))

(deftest validate-add-input-rejects-missing-required-field
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Invalid business input"
       (schemas/validate-add-input! "commodity" {:file "main.journal"}))))

(deftest validate-add-input-rejects-mutually-exclusive-after-options
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"mutually exclusive"
       (schemas/validate-add-input! "commodity"
                                    {:file "main.journal" :name "EUR"
                                     :after_eid 1 :before_eid 2}))))

(deftest validate-add-input-rejects-after-position
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"after_position is not supported"
       (schemas/validate-add-input! "commodity"
                                    {:file "main.journal" :name "EUR"
                                     :after_position 1000}))))

(deftest validate-add-input-accepts-transaction-with-postings-and-tags
  (is (map? (schemas/validate-add-input!
             "transaction"
             {:file "2026.journal" :date "2026-06-24" :description "Lunch"
              :postings [{:account "expenses:food" :amount "INR 500"}]}))))

(deftest amendable-attributes-includes-system-attrs
  (is (contains? (schemas/amendable-attributes "commodity") "entity/file"))
  (is (contains? (schemas/amendable-attributes "commodity") "entity/position"))
  (is (contains? (schemas/amendable-attributes "posting") "posting/parent-eid"))
  (is (contains? (schemas/amendable-attributes "posting") "posting/position"))
  (is (contains? (schemas/amendable-attributes "tag") "tag/parent-eid"))
  (is (contains? (schemas/amendable-attributes "tag") "tag/position")))
