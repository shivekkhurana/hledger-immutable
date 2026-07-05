(ns hledger-immutable.core-test
  (:require [clojure.test :refer [deftest is]]
            [hledger-immutable.core]))

(deftest core-namespace-loads
  (is (find-ns 'hledger-immutable.core)))
