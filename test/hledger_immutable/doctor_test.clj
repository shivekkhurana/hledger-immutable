(ns hledger-immutable.doctor-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hledger-immutable.doctor :as doctor]
            [hledger-immutable.projection :as projection])
  (:import [java.nio.file Files]))

(defn- temp-directory []
  (.toFile (Files/createTempDirectory
            "hledger-immutable-doctor-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- transaction-datoms [start eid filename position date description]
  [[start eid "entity/file" filename false]
   [(+ start 1) eid "entity/position" position false]
   [(+ start 2) eid "entity/type" "transaction" false]
   [(+ start 3) eid "transaction/date" date false]
   [(+ start 4) eid "transaction/description" description false]])

(defn- posting-datoms [start eid parent-eid position account amount]
  [[start eid "entity/type" "posting" false]
   [(+ start 1) eid "posting/parent-eid" parent-eid false]
   [(+ start 2) eid "posting/position" position false]
   [(+ start 3) eid "posting/account" account false]
   [(+ start 4) eid "posting/amount" amount false]])

(defn- tag-datoms [start eid parent-eid position name value]
  [[start eid "entity/type" "tag" false]
   [(+ start 1) eid "tag/parent-eid" parent-eid false]
   [(+ start 2) eid "tag/position" position false]
   [(+ start 3) eid "tag/name" name false]
   [(+ start 4) eid "tag/value" value false]])

(defn- include-datoms [start eid filename position path]
  [[start eid "entity/file" filename false]
   [(+ start 1) eid "entity/position" position false]
   [(+ start 2) eid "entity/type" "include" false]
   [(+ start 3) eid "include/file" path false]])

(defn- hledger-error
  [{:keys [journal line-start line-end description postings residual
           multi? automatic-conversion-disabled?]}]
  (str "hledger: Error: " (.getPath journal) ":" line-start "-" line-end ":\n"
       line-start " | " description "\n"
       (apply str
              (map (fn [posting]
                     (str "  |     " posting "\n"))
                   postings))
       "\n"
       (if multi?
         "This multi-commodity transaction is unbalanced.\n"
         "This transaction is unbalanced.\n")
       (when automatic-conversion-disabled?
         "Automatic commodity conversion is not enabled.\n")
       "The real postings' sum should be 0 but is: " residual "\n"
       (when multi?
         (str "Consider adjusting this entry's amounts, adding missing postings,\n"
              "or recording conversion price(s) with @, @@ or equity postings.\n"))))

(defn- explain
  [directory stderr]
  (doctor/explain-hledger-error
   {:workspace-directory directory
    :stderr stderr}))

(deftest explain-hledger-error-classifies-simple-unbalanced-transaction
  (let [directory (temp-directory)
        journal (io/file directory "main.journal")
        datoms (vec (concat
                     (transaction-datoms 0 5 "main.journal" 5000
                                        "2026-06-27" "Broken dinner")
                     (posting-datoms 5 6 5 6000
                                     "expenses:food" "INR 700")
                     (posting-datoms 10 7 5 7000
                                     "assets:cash" "INR -650")
                     (tag-datoms 15 8 5 8000 "project" "home")))]
    (projection/project! directory nil datoms)
    (let [result (explain
                  directory
                  (hledger-error
                   {:journal journal
                    :line-start 2
                    :line-end 4
                    :description "2026-06-27 Broken dinner"
                    :postings ["expenses:food         INR 700"
                               "assets:cash          INR -650"]
                    :residual "INR 50"}))]
      (is (= doctor/unbalanced-transaction-error-type (:kind result)))
      (is (= "main.journal" (:journal result)))
      (is (= 2 (:line_start result)))
      (is (= 4 (:line_end result)))
      (is (= "INR 50" (:residual result)))
      (is (false? (:multi_commodity result)))
      (is (false? (:automatic_commodity_conversion_disabled result)))
      (is (= {:eid 5
              :type "transaction"
              :file "main.journal"
              :position 5000}
             (select-keys (:root_entity result)
                          [:eid :type :file :position])))
      (is (= [6 7] (mapv :eid (:postings result))))
      (is (= ["expenses:food" "assets:cash"]
             (mapv :account (:postings result))))
      (is (= ["INR 700" "INR -650"]
             (mapv :amount (:postings result))))
      (is (= [8] (mapv :eid (:tags result)))))))

(deftest explain-hledger-error-captures-multi-commodity-details
  (let [directory (temp-directory)
        journal (io/file directory "multi.journal")
        datoms (vec (concat
                     (transaction-datoms 0 10 "multi.journal" 10000
                                        "2026-06-27" "Multi broken")
                     (posting-datoms 5 11 10 11000
                                     "expenses:food" "INR 500")
                     (posting-datoms 10 12 10 12000
                                     "assets:cash" "INR -400")
                     (posting-datoms 15 13 10 13000
                                     "assets:wallet" "USD 10")
                     (posting-datoms 20 14 10 14000
                                     "income:test" "USD -7")))]
    (projection/project! directory nil datoms)
    (let [result (explain
                  directory
                  (hledger-error
                   {:journal journal
                    :line-start 2
                    :line-end 6
                    :description "2026-06-27 Multi broken"
                    :postings ["expenses:food         INR 500"
                               "assets:cash          INR -400"
                               "assets:wallet          USD 10"
                               "income:test            USD -7"]
                    :residual "INR 100, USD 3"
                    :multi? true}))]
      (is (= doctor/unbalanced-transaction-error-type (:kind result)))
      (is (= "INR 100, USD 3" (:residual result)))
      (is (true? (:multi_commodity result)))
      (is (false? (:automatic_commodity_conversion_disabled result)))
      (is (= 10 (get-in result [:root_entity :eid])))
      (is (= [11 12 13 14] (mapv :eid (:postings result)))))))

(deftest explain-hledger-error-captures-disabled-automatic-conversion
  (let [directory (temp-directory)
        journal (io/file directory "conversion.journal")
        datoms (vec (concat
                     (transaction-datoms 0 20 "conversion.journal" 20000
                                        "2026-06-27" "Implicit conversion")
                     (posting-datoms 5 21 20 21000
                                     "assets:cash" "USD 10")
                     (posting-datoms 10 22 20 22000
                                     "income:test" "INR -830")))]
    (projection/project! directory nil datoms)
    (let [result (explain
                  directory
                  (hledger-error
                   {:journal journal
                    :line-start 2
                    :line-end 4
                    :description "2026-06-27 Implicit conversion"
                    :postings ["assets:cash          USD 10"
                               "income:test        INR -830"]
                    :residual "INR -830, USD 10"
                    :multi? true
                    :automatic-conversion-disabled? true}))]
      (is (= "INR -830, USD 10" (:residual result)))
      (is (true? (:multi_commodity result)))
      (is (true? (:automatic_commodity_conversion_disabled result))))))

(deftest explain-hledger-error-captures-three-commodity-and-cost-residuals
  (let [directory (temp-directory)
        three-journal (io/file directory "three.journal")
        cost-journal (io/file directory "cost.journal")
        datoms (vec (concat
                     (transaction-datoms 0 30 "three.journal" 30000
                                        "2026-06-27" "Three broken")
                     (posting-datoms 5 31 30 31000
                                     "assets:cash" "USD 10")
                     (posting-datoms 10 32 30 32000
                                     "income:test" "INR -830")
                     (posting-datoms 15 33 30 33000
                                     "equity:opening" "EUR 1")
                     (transaction-datoms 20 40 "cost.journal" 40000
                                        "2026-06-27" "Cost broken")
                     (posting-datoms 25 41 40 41000
                                     "assets:wallet" "USD 10 @@ INR 830")
                     (posting-datoms 30 42 40 42000
                                     "assets:cash" "INR -800")))]
    (projection/project! directory nil datoms)
    (is (= "EUR 1, INR -830, USD 10"
           (:residual
            (explain
             directory
             (hledger-error
              {:journal three-journal
               :line-start 2
               :line-end 5
               :description "2026-06-27 Three broken"
               :postings ["assets:cash             USD 10"
                          "income:test           INR -830"
                          "equity:opening           EUR 1"]
               :residual "EUR 1, INR -830, USD 10"
               :multi? true})))))
    (is (= "INR 30"
           (:residual
            (explain
             directory
             (hledger-error
              {:journal cost-journal
               :line-start 2
               :line-end 4
               :description "2026-06-27 Cost broken"
               :postings ["assets:wallet    USD 10 @@ INR 830"
                          "assets:cash               INR -800"]
               :residual "INR 30"
               :multi? true})))))))

(deftest explain-hledger-error-maps-included-journal-path
  (let [directory (temp-directory)
        journal (io/file directory "2026.journal")
        datoms (vec (concat
                     (include-datoms 0 1 "main.journal" 1000 "2026.journal")
                     (transaction-datoms 4 50 "2026.journal" 50000
                                        "2026-06-27" "Included broken")
                     (posting-datoms 9 51 50 51000
                                     "expenses:food" "INR 700")
                     (posting-datoms 14 52 50 52000
                                     "assets:cash" "INR -650")))]
    (projection/project! directory nil datoms)
    (let [result (explain
                  directory
                  (hledger-error
                   {:journal journal
                    :line-start 2
                    :line-end 4
                    :description "2026-06-27 Included broken"
                    :postings ["expenses:food         INR 700"
                               "assets:cash          INR -650"]
                    :residual "INR 50"}))]
      (is (= "2026.journal" (:journal result)))
      (is (= 50 (get-in result [:root_entity :eid])))
      (is (= [51 52] (mapv :eid (:postings result)))))))

(deftest explain-hledger-error-returns-unknown-without-journal-lookup
  (let [stderr "hledger: Error: /not/in/workspace/main.journal:2-4:\nNope\n"
        result (explain (temp-directory) stderr)]
    (is (= {:kind doctor/unknown-hledger-error-type
            :stderr stderr}
           result))))

(deftest explain-hledger-error-rejects-unsafe-recognized-paths
  (let [directory (temp-directory)
        outside (io/file (temp-directory) "outside.journal")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"inside the workspace-directory"
         (explain
          directory
          (hledger-error
           {:journal outside
            :line-start 2
            :line-end 4
            :description "2026-06-27 Outside"
            :postings ["expenses:food  INR 1"
                       "assets:cash  INR -2"]
            :residual "INR -1"}))))))
