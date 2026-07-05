(ns hledger-immutable.event-log-test
  (:require [clojure.test :refer [deftest is]]
            [hledger-immutable.event-log :as event-log]
            [hledger-immutable.schemas :as schemas]))

(def supported-entity-types
  (sort-by name
           (concat schemas/top-level-entity-types
                   schemas/child-entity-types)))

(def text-attribute-examples
  {"commodity/name" "EUR"
   "price/date" "2026-06-23"
   "price/commodity" "EUR"
   "price/value" "INR 83"
   "account/name" "assets:bank"
   "transaction/date" "2026-06-23"
   "transaction/status" "*"
   "transaction/code" "INV-123"
   "transaction/description" "Amazon | laptop"
   "periodic/expression" "monthly"
   "periodic/description" "rent budget"
   "include/file" "prices.journal"
   "decimal-mark/value" "."
   "alias/old" "bank"
   "alias/new" "assets:bank:hdfc"
   "default-commodity/value" "INR 1,000.00"
   "payee/name" "Amazon"
   "tag-declaration/name" "project"
   "posting/status" "!"
   "posting/account" "expenses:food"
   "posting/amount" "INR 500"
   "tag/name" "meal"
   "tag/value" "lunch"})

(def integer-attribute-examples
  {"entity/position" 1000
   "posting/parent-eid" 10
   "posting/position" 1000
   "tag/parent-eid" 101
   "tag/position" 1000})

(defn- assert-valid-datoms [datoms]
  (is (nil? (event-log/validate-event-log (vec datoms) nil))))

(defn- sequenced-datoms [datoms]
  (map-indexed (fn [index datom]
                 (into [index] datom))
               datoms))

(deftest prepend-sequence-adds-the-event-log-sequence-number
  (is (= [10 1 :price/value "0.9" false]
         (event-log/prepend-sequence
          10
          [1 :price/value "0.9" false]))))

(deftest valid-filename-accepts-only-local-journal-filenames
  (is (true? (#'hledger-immutable.event-log/valid-filename?
              "prices.journal")))
  (is (false? (#'hledger-immutable.event-log/valid-filename?
               "../prices.journal")))
  (is (false? (#'hledger-immutable.event-log/valid-filename?
               "prices.txt")))
  (is (false? (#'hledger-immutable.event-log/valid-filename?
               nil))))

(deftest validate-last-projected-datom-sequence-number-accepts-nil-or-integer
  (is (nil? (event-log/validate-last-projected-datom-sequence-number nil)))
  (is (nil? (event-log/validate-last-projected-datom-sequence-number 10)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"nil or integer"
       (event-log/validate-last-projected-datom-sequence-number "invalid"))))

(deftest validate-event-log-accepts-new-strictly-increasing-datoms
	     (is (nil? (event-log/validate-event-log
             [[6 1 "entity/file" "prices.journal" false]
              [7 1 "entity/type" "price" false]
              [8 1 "entity/position" 1000 false]
              [9 1 "price/value" "1.0" false]
              [10 2 "entity/type" "posting" false]
              [11 2 "posting/parent-eid" 1 false]
              [12 2 "posting/position" 1000 false]
              [13 3 "entity/type" "tag" false]
              [14 3 "tag/parent-eid" 2 false]
              [15 3 "tag/position" 1000 false]]
             5))))

(deftest validate-event-log-accepts-every-supported-entity-type
  (assert-valid-datoms
   (sequenced-datoms
	    (map-indexed (fn [index entity-type]
                   [(inc index) "entity/type" entity-type false])
                 supported-entity-types))))

(deftest validate-event-log-accepts-all-supported-vocabulary-attributes
  (assert-valid-datoms
   (sequenced-datoms
    (concat [[1 "entity/file" "main.journal" false]
             [1 "entity/type" "transaction" false]]
            (map (fn [[attr value]]
                   [1 attr value false])
                 integer-attribute-examples)
            (map (fn [[attr value]]
                   [1 attr value false])
                 text-attribute-examples)))))

(deftest validate-event-log-accepts-retractions-for-all-supported-attributes
  (assert-valid-datoms
   (sequenced-datoms
    (concat [[1 "entity/file" "main.journal" true]
             [1 "entity/type" "transaction" true]]
            (map (fn [[attr value]]
                   [1 attr value true])
                 integer-attribute-examples)
            (map (fn [[attr value]]
                   [1 attr value true])
                 text-attribute-examples)))))

(deftest validate-event-log-rejects-processed-or-out-of-order-datoms
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"already-processed"
       (event-log/validate-event-log
        [[5 1 "price/value" "0.9" false]]
        5)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"strictly increasing"
       (event-log/validate-event-log
        [[7 1 "price/value" "0.9" false]
         [6 1 "price/value" "1.0" false]]
        5))))

(deftest validate-event-log-rejects-malformed-datoms
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"five-item vector"
       (event-log/validate-event-log
        [[0 1 "price/value" "0.9"]]
        nil)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"sequence must be an integer"
       (event-log/validate-event-log
        [["0" 1 "price/value" "0.9" false]]
        nil)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"eid cannot be nil"
       (event-log/validate-event-log
        [[0 nil "price/value" "0.9" false]]
        nil)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"attribute must be a string"
       (event-log/validate-event-log
        [[0 1 :price/value "0.9" false]]
        nil)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"local .journal filename"
       (event-log/validate-event-log
        [[0 1 "entity/file" "../prices.journal" false]]
        nil)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
        #"supported entity type"
       (event-log/validate-event-log
        [[0 1 "entity/type" :price false]]
        nil)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
        #"supported entity type"
       (event-log/validate-event-log
        [[0 1 "entity/type" "future-transaction" false]]
        nil)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"attribute schema"
       (event-log/validate-event-log
        [[0 1 "entity/position" "1000" false]]
        nil)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"attribute schema"
       (event-log/validate-event-log
        [[0 1 "posting/parent-eid" "10" false]]
        nil)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"attribute schema"
       (event-log/validate-event-log
        [[0 1 "tag/position" "1000" false]]
        nil)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"attribute schema"
       (event-log/validate-event-log
        [[0 1 "price/value" 0.9 false]]
        nil)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"retraction flag must be boolean"
       (event-log/validate-event-log
        [[0 1 "price/value" "0.9" nil]]
        nil))))
