(ns hledger-immutable.projection-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hledger-immutable.projection :as projection])
  (:import [java.nio.file Files]))

(defn- temp-directory []
  (.toFile (Files/createTempDirectory
            "hledger-immutable-projection-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- commodity-datoms [start eid filename position name]
  [[start eid "entity/file" filename false]
   [(+ start 1) eid "entity/position" position false]
   [(+ start 2) eid "entity/type" "commodity" false]
   [(+ start 3) eid "commodity/name" name false]])

(defn- price-datoms [start eid filename position date commodity value]
  [[start eid "entity/file" filename false]
   [(+ start 1) eid "entity/position" position false]
   [(+ start 2) eid "entity/type" "price" false]
   [(+ start 3) eid "price/date" date false]
   [(+ start 4) eid "price/commodity" commodity false]
   [(+ start 5) eid "price/value" value false]])

(def base-datoms
  (vec (concat (commodity-datoms 0 0 "commodities.journal" 1000 "EUR")
               (price-datoms 4 1 "prices.journal" 1000
                             "2026-06-22" "EUR" "0.8"))))

(defn- eid-line [eid position]
  (str "; __eid: " eid " __meta: {\"entity/position\" " position "}\n"))

(deftest datom-accessor-reads-eid
  (is (= 1
         (#'hledger-immutable.projection/datom-eid
          [0 1 "price/value" "0.9" false]))))

(deftest project-builds-an-empty-projection
  (let [directory (temp-directory)]
    (is (= 9 (projection/project! directory nil base-datoms)))
    (is (= (str (eid-line 0 1000) "commodity EUR\n")
           (slurp (io/file directory "commodities.journal"))))
    (is (= (str (eid-line 1 1000) "P 2026-06-22 EUR 0.8\n")
           (slurp (io/file directory "prices.journal"))))))

(deftest project-orders-entities-by-position
  (let [directory (temp-directory)
        datoms (vec (concat (price-datoms 0 20 "prices.journal" 2000
                                          "2026-06-22" "EUR" "0.8")
                            (price-datoms 6 10 "prices.journal" 1000
                                          "2026-06-23" "EUR" "0.9")))]
    (projection/project! directory nil datoms)
    (is (= (str (eid-line 10 1000) "P 2026-06-23 EUR 0.9\n\n"
                (eid-line 20 2000) "P 2026-06-22 EUR 0.8\n")
           (slurp (io/file directory "prices.journal"))))))

(deftest empty-project-retains-its-checkpoint
  (let [directory (temp-directory)]
    (is (nil? (projection/project! directory nil [])))
    (is (= 10 (projection/project! directory 10 [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"nil or integer"
                          (projection/project! directory "invalid" [])))))

(deftest project-replaces-a-value-and-appends-an-entity
  (let [directory (temp-directory)]
    (projection/project! directory nil base-datoms)
    (is (= 17
           (projection/project!
            directory 9
            (vec (concat [[10 1 "price/value" "0.8" true]
                          [11 1 "price/value" "0.9" false]]
                         (price-datoms 12 2 "prices.journal" 2000
                                       "2026-06-23" "EUR" "1.0"))))))
    (is (= (str (eid-line 1 1000) "P 2026-06-22 EUR 0.9\n\n"
                (eid-line 2 2000) "P 2026-06-23 EUR 1.0\n")
           (slurp (io/file directory "prices.journal"))))))

(deftest project-appends-new-entities-and-replays-without-duplicates
  (let [directory (temp-directory)
        delta (price-datoms 10 2 "prices.journal" 2000
                            "2026-06-23" "EUR" "1.0")]
    (projection/project! directory nil base-datoms)
    (projection/project! directory 9 delta)
    ;; Replay with the old checkpoint: __eid now exists, so this becomes a
    ;; no-op rewrite instead of a second append.
    (projection/project! directory 9 delta)
    (is (= (str (eid-line 1 1000) "P 2026-06-22 EUR 0.8\n\n"
                (eid-line 2 2000) "P 2026-06-23 EUR 1.0\n")
           (slurp (io/file directory "prices.journal"))))))

(deftest stale-retractions-are-no-ops
  (let [directory (temp-directory)]
    (projection/project! directory nil base-datoms)
    (is (= 10 (projection/project!
               directory 9
               [[10 1 "price/value" "9.9" true]])))
    (is (= (str (eid-line 1 1000) "P 2026-06-22 EUR 0.8\n")
           (slurp (io/file directory "prices.journal"))))))

(deftest project-updates-multiple-files-in-one-batch
  (let [directory (temp-directory)]
    (projection/project! directory nil base-datoms)
    (is (= 13
           (projection/project!
            directory 9
            [[10 0 "commodity/name" "EUR" true]
             [11 0 "commodity/name" "USD" false]
             [12 1 "price/value" "0.8" true]
             [13 1 "price/value" "0.9" false]])))
    (is (= (str (eid-line 0 1000) "commodity USD\n")
           (slurp (io/file directory "commodities.journal"))))
    (is (= (str (eid-line 1 1000) "P 2026-06-22 EUR 0.9\n")
           (slurp (io/file directory "prices.journal"))))))

(deftest project-preserves-repeated-edits-to-one-eid
  (let [directory (temp-directory)]
    (projection/project! directory nil base-datoms)
    (projection/project! directory 9
                         [[10 1 "price/value" "0.8" true]
                          [11 1 "price/value" "0.9" false]
                          [12 1 "price/value" "0.9" true]
                          [13 1 "price/value" "1.0" false]])
    (is (= (str (eid-line 1 1000) "P 2026-06-22 EUR 1.0\n")
           (slurp (io/file directory "prices.journal"))))))

(deftest retracting-every-attribute-removes-the-file
  (let [directory (temp-directory)]
    (projection/project! directory nil base-datoms)
    (projection/project!
     directory 9
     [[10 1 "entity/file" "prices.journal" true]
      [11 1 "entity/position" 1000 true]
      [12 1 "entity/type" "price" true]
      [13 1 "price/date" "2026-06-22" true]
      [14 1 "price/commodity" "EUR" true]
      [15 1 "price/value" "0.8" true]])
    (is (not (.exists (io/file directory "prices.journal"))))
    (is (.exists (io/file directory "commodities.journal")))))

(deftest project-moves-an-entity-between-files
  (let [directory (temp-directory)]
    (projection/project! directory nil base-datoms)
    (is (= 11
           (projection/project!
            directory 9
            [[10 1 "entity/file" "archive.journal" false]
             [11 1 "price/value" "0.9" false]])))
    (is (not (.exists (io/file directory "prices.journal"))))
    (is (= (str (eid-line 1 1000) "P 2026-06-22 EUR 0.9\n")
           (slurp (io/file directory "archive.journal"))))))

(deftest replaying-a-move-before-checkpoint-persistence-is-safe
  (let [directory (temp-directory)
        delta [[10 1 "entity/file" "archive.journal" false]]]
    (projection/project! directory nil base-datoms)
    (projection/project! directory 9 delta)
    ;; Simulate a crash after the file write but before checkpoint 10 is saved.
    (projection/project! directory 9 delta)
    (is (not (.exists (io/file directory "prices.journal"))))
    (is (= (str (eid-line 1 1000) "P 2026-06-22 EUR 0.8\n")
           (slurp (io/file directory "archive.journal"))))))

(deftest project-validates-checkpoint-and-event-log-order
  (let [directory (temp-directory)]
    (projection/project! directory nil base-datoms)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"already-processed"
                          (projection/project!
                           directory 9
                           [[9 1 "price/value" "0.9" false]])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"strictly increasing"
                          (projection/project!
                           directory 9
                           [[11 1 "price/value" "0.9" false]
                            [10 1 "price/value" "1.0" false]])))))

(deftest invalid-or-incomplete-datoms-do-not-replace-existing-projection
  (let [directory (temp-directory)
        journal (io/file directory "prices.journal")]
    (spit journal "original\n")
    (is (thrown? clojure.lang.ExceptionInfo
                 (projection/project!
                  directory nil
                  [[0 1 "entity/type" "price" false]])))
    (is (= "original\n" (slurp journal)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (projection/project!
                  directory nil
                  [[0 1 "entity/file" "../prices.journal" false]])))
    (is (= "original\n" (slurp journal)))))

(deftest project-prepares-every-file-before-publishing-any-file
  (let [directory (temp-directory)
        commodities (io/file directory "commodities.journal")
        prices (io/file directory "prices.journal")]
    (projection/project! directory nil base-datoms)
    (let [original-commodities (slurp commodities)
          original-prices (slurp prices)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"incomplete entity"
           (projection/project!
            directory 9
            [[10 0 "commodity/name" "EUR" true]
             [11 0 "commodity/name" "USD" false]
             [12 2 "entity/file" "prices.journal" false]
             [13 2 "entity/type" "price" false]])))
      (is (= original-commodities (slurp commodities)))
      (is (= original-prices (slurp prices))))))

(deftest changing-position-reorders-existing-entity-bodies
  (let [directory (temp-directory)
        datoms (vec (concat (price-datoms 0 1 "prices.journal" 1000
                                          "2026-06-22" "EUR" "0.8")
                            (price-datoms 6 2 "prices.journal" 2000
                                          "2026-06-23" "EUR" "0.9")))]
    (projection/project! directory nil datoms)
    (projection/project! directory 11 [[12 2 "entity/position" 500 false]])
    (is (= (str (eid-line 2 500) "P 2026-06-23 EUR 0.9\n\n"
                (eid-line 1 1000) "P 2026-06-22 EUR 0.8\n")
           (slurp (io/file directory "prices.journal"))))))

(deftest sparse-positions-allow-insertion-between-existing-entities
  (let [directory (temp-directory)
        initial (vec (concat (price-datoms 0 1 "prices.journal" 1000
                                           "2026-06-22" "EUR" "0.8")
                             (price-datoms 6 2 "prices.journal" 2000
                                           "2026-06-24" "EUR" "1.0")))
        insertion (price-datoms 12 3 "prices.journal" 1500
                                "2026-06-23" "EUR" "0.9")]
    (projection/project! directory nil initial)
    (projection/project! directory 11 insertion)
    (is (= (str (eid-line 1 1000) "P 2026-06-22 EUR 0.8\n\n"
                (eid-line 3 1500) "P 2026-06-23 EUR 0.9\n\n"
                (eid-line 2 2000) "P 2026-06-24 EUR 1.0\n")
           (slurp (io/file directory "prices.journal"))))))

(deftest mixed-file-move-plus-position-change-works-in-one-batch
  (let [directory (temp-directory)
        initial (vec (concat (price-datoms 0 1 "prices.journal" 1000
                                           "2026-06-22" "EUR" "0.8")
                             (price-datoms 6 2 "prices.journal" 2000
                                           "2026-06-23" "EUR" "0.9")
                             (price-datoms 12 3 "archive.journal" 1000
                                           "2026-06-24" "EUR" "1.0")))]
    (projection/project! directory nil initial)
    (projection/project!
     directory 17
     [[18 2 "entity/file" "archive.journal" false]
      [19 2 "entity/position" 500 false]])
    (is (= (str (eid-line 1 1000) "P 2026-06-22 EUR 0.8\n")
           (slurp (io/file directory "prices.journal"))))
    (is (= (str (eid-line 2 500) "P 2026-06-23 EUR 0.9\n\n"
                (eid-line 3 1000) "P 2026-06-24 EUR 1.0\n")
           (slurp (io/file directory "archive.journal"))))))

(deftest malformed-current-prices-fail-at-the-parsing-boundary
  (let [directory (temp-directory)
        journal (io/file directory "prices.journal")]
    (spit journal "; __eid: 1\nP malformed\n")
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Cannot parse this price entity"
         (projection/project!
          directory nil
          [[0 1 "price/value" "0.9" false]])))
    (is (= "; __eid: 1\nP malformed\n" (slurp journal)))))
