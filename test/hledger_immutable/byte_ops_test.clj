(ns hledger-immutable.byte-ops-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [hledger-immutable.byte-ops :as byte-ops])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream RandomAccessFile]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]))

(defn- temp-journal [content]
  (let [directory (.toFile
                   (Files/createTempDirectory
                    "hledger-byte-ops-test-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        journal (io/file directory "prices.journal")]
    (spit journal content)
    journal))

(defn- line-bytes [line]
  (let [output (ByteArrayOutputStream.)]
    (.write output (.getBytes ^String line StandardCharsets/UTF_8))
    output))

(deftest parse-marker-eid-parses-only-marker-lines
  (let [prefix (.getBytes "; __eid: " StandardCharsets/UTF_8)]
    (is (= "price/é"
           (#'hledger-immutable.byte-ops/parse-marker-eid
            (line-bytes "; __eid: price/é")
            prefix)))
    (is (= "42"
           (#'hledger-immutable.byte-ops/parse-marker-eid
            (line-bytes "; __eid: 42\r")
            prefix)))
    (is (nil? (#'hledger-immutable.byte-ops/parse-marker-eid
               (line-bytes "commodity EUR")
               prefix)))))

(deftest scan-entity-address-bytes-finds-requested-entity-ranges
  (let [content (str "; __eid: 1\ncommodity EUR\n\n"
                     "; __eid: 2\ncommodity café\n\n"
                     "; __eid: 3\ncommodity USD\n")
        journal (temp-journal content)
        input (ByteArrayInputStream.
               (.getBytes ^String content StandardCharsets/UTF_8))
        addresses (#'hledger-immutable.byte-ops/scan-entity-address-bytes
                   input
                   journal
                   {"2" :target})]
    (is (= {:target {:start (.indexOf content "; __eid: 2")
                     :end (alength (.getBytes ^String
                                               (subs content
                                                     0
                                                     (.indexOf content "; __eid: 3"))
                                               StandardCharsets/UTF_8))}}
           addresses))))

(deftest finds-exact-entity-address-bytes-with-utf8-content
  (let [content (str "; __eid: 1\nP 2026-06-22 EUR 0.8 ; café\n\n"
                     "; __eid: 2\nP 2026-06-23 EUR 0.9\n")
        journal (temp-journal content)
        addresses (byte-ops/find-entity-address-bytes journal [1 2 99])]
    (is (= #{1 2} (set (keys addresses))))
    (is (= "P 2026-06-22 EUR 0.8 ; café"
           (byte-ops/read-entity-body journal (get addresses 1))))
    (is (= "P 2026-06-23 EUR 0.9"
           (byte-ops/read-entity-body journal (get addresses 2))))
    ;; Character and byte positions differ after é, so this verifies that the
    ;; returned address is genuinely byte based.
    (is (= (alength (.getBytes ^String content StandardCharsets/UTF_8))
           (get-in addresses [2 :end])))))

(deftest finds-entity-address-by-line
  (let [journal (temp-journal
                 (str "preamble\n"
                      "; __eid: 1\ncommodity EUR\n\n"
                      "; __eid: 2\ncommodity USD"))]
    (is (nil? (byte-ops/find-entity-address-by-line journal 1)))
    (is (= 1 (:eid (byte-ops/find-entity-address-by-line journal 2))))
    (is (= "commodity EUR"
           (byte-ops/read-entity-body
            journal
            (:entity-address-bytes
             (byte-ops/find-entity-address-by-line journal 3)))))
    (is (= 2 (:eid (byte-ops/find-entity-address-by-line journal 5))))
    (is (= "commodity USD"
           (byte-ops/read-entity-body
            journal
            (:entity-address-bytes
             (byte-ops/find-entity-address-by-line journal 6)))))
    (is (nil? (byte-ops/find-entity-address-by-line journal 99)))
    (is (nil? (byte-ops/find-entity-address-by-line
               (io/file (.getParentFile journal) "missing.journal")
               1)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"positive integer"
         (byte-ops/find-entity-address-by-line journal 0)))))

(deftest closes-an-early-target-at-the-next-marker
  (let [journal (temp-journal
                 (str "; __eid: 1\ncommodity EUR\n\n"
                      "; __eid: 2\ncommodity USD\n"
                      (apply str (repeat 10000 "; unrelated tail\n"))))
        address (get (byte-ops/find-entity-address-bytes journal [1]) 1)]
    (is (= "commodity EUR"
           (byte-ops/read-entity-body journal address)))))

(deftest finds-a-late-entity-in-a-large-streamed-journal
  (let [journal (temp-journal "")]
    (with-open [writer (io/writer journal)]
      (doseq [eid (range 5000)]
        (.write writer (str "; __eid: " eid "\ncommodity C" eid "\n\n"))))
    (let [address (get (byte-ops/find-entity-address-bytes journal [4999]) 4999)]
      (is (= "commodity C4999"
             (byte-ops/read-entity-body journal address))))))

(deftest write-fully-drains-the-entire-buffer
  (let [target-file (temp-journal "")
        bytes (.getBytes "abcdef" StandardCharsets/UTF_8)
        buffer (ByteBuffer/wrap bytes)]
    (with-open [channel (.getChannel (RandomAccessFile. target-file "rw"))]
      (#'hledger-immutable.byte-ops/write-fully! channel buffer))
    (is (= "abcdef" (slurp target-file)))
    (is (false? (.hasRemaining buffer)))))

(deftest copy-range-copies-only-the-requested-bytes
  (let [source-file (temp-journal "0123456789")
        target-file (temp-journal "")]
    (with-open [source (.getChannel (RandomAccessFile. source-file "r"))
                target (.getChannel (RandomAccessFile. target-file "rw"))]
      (#'hledger-immutable.byte-ops/copy-range! source target 2 7))
    (is (= "23456" (slurp target-file)))))

(deftest replacement-bytes-encodes-text-and-nil-deletions
  (is (= "café"
         (String. (#'hledger-immutable.byte-ops/replacement-bytes "café")
                  StandardCharsets/UTF_8)))
  (is (zero? (alength (#'hledger-immutable.byte-ops/replacement-bytes nil)))))

(deftest move-replacing-swaps-a-prepared-temp-file-into-place
  (let [target (temp-journal "old")
        source (io/file (.getParentFile target) "replacement.tmp")]
    (spit source "new")
    (#'hledger-immutable.byte-ops/move-replacing!
     (.toPath source)
     (.toPath target))
    (is (= "new" (slurp target)))
    (is (false? (.exists source)))))

(deftest rewrite-mutations-applies-sorted-updates-and-deletions
  (let [journal (temp-journal "aaBBccDD")]
    (#'hledger-immutable.byte-ops/rewrite-mutations!
     journal
     [{:start 6 :end 8 :replacement nil}
      {:start 2 :end 4 :replacement "XX"}])
    (is (= "aaXXcc" (slurp journal)))))

(deftest append-text-adds-text-to-the-end
  (let [journal (temp-journal "first")]
    (#'hledger-immutable.byte-ops/append-text! journal "\nsecond")
    (is (= "first\nsecond" (slurp journal)))))

(deftest applies-multiple-byte-mutations-in-one-rewrite
  (let [journal (temp-journal
                 (str "; __eid: 1\nP 2026-06-22 EUR 0.8\n\n"
                      "; __eid: 2\nP 2026-06-23 EUR 0.9\n\n"
                      "; __eid: 3\nP 2026-06-24 EUR 1.0\n"))
        addresses (byte-ops/find-entity-address-bytes journal [1 3])]
    (#'hledger-immutable.byte-ops/apply-byte-mutations!
     journal
     [(assoc (get addresses 1)
             :replacement "; __eid: 1\nP 2026-06-22 EUR 0.85\n\n")
      (assoc (get addresses 3) :replacement nil)])
    (is (= (str "; __eid: 1\nP 2026-06-22 EUR 0.85\n\n"
                "; __eid: 2\nP 2026-06-23 EUR 0.9\n\n")
           (slurp journal)))))

(deftest appends-all-content-in-one-operation
  (let [journal (temp-journal "; __eid: 1\ncommodity EUR\n")
        size (.length journal)]
    (#'hledger-immutable.byte-ops/apply-byte-mutations!
     journal
     [{:start size
       :end size
       :replacement "\n; __eid: 2\ncommodity USD\n"}])
    (is (= (str "; __eid: 1\ncommodity EUR\n\n"
                "; __eid: 2\ncommodity USD\n")
           (slurp journal)))))

(deftest inserts-at-eof-with-other-byte-mutations
  (let [journal (temp-journal
                 (str "; __eid: 1\ncommodity EUR\n\n"
                      "; __eid: 2\ncommodity GBP\n"))
        address (get (byte-ops/find-entity-address-bytes journal [1]) 1)
        size (.length journal)]
    (#'hledger-immutable.byte-ops/apply-byte-mutations!
     journal
     [(assoc address :replacement "; __eid: 1\ncommodity USD\n\n")
      {:start size
       :end size
       :replacement "\n; __eid: 3\ncommodity INR\n"}])
    (is (= (str "; __eid: 1\ncommodity USD\n\n"
                "; __eid: 2\ncommodity GBP\n\n"
                "; __eid: 3\ncommodity INR\n")
           (slurp journal)))))

(deftest unframe-entity-body-removes-the-marker-frame
  (is (= "commodity EUR"
         (#'hledger-immutable.byte-ops/unframe-entity-body
          "; __eid: 1\ncommodity EUR\n\n"))))

(deftest frame-entity-body-adds-the-marker-frame
  (is (= "; __eid: 1\ncommodity EUR\n"
         (#'hledger-immutable.byte-ops/frame-entity-body 1 "commodity EUR" false)))
  (is (= "; __eid: 1\ncommodity EUR\n\n"
         (#'hledger-immutable.byte-ops/frame-entity-body 1 "commodity EUR" true))))

(deftest plan-entity-mutations-describes-changes-without-mutating-the-file
  (let [content "; __eid: 1\ncommodity EUR\n"
        journal (temp-journal content)
        entity-address-bytes (byte-ops/find-entity-address-bytes journal [1])
        plan (byte-ops/plan-entity-mutations
              journal
              entity-address-bytes
              [{:eid 1 :body "commodity USD"}
               {:eid 2 :body "commodity INR"}
               {:eid 3 :body nil}])]
    (is (= content (slurp journal)))
    (is (= journal (:file plan)))
    (is (= [{:start 0
             :end (.length journal)
             :replacement "; __eid: 1\ncommodity USD\n"}
            {:start (.length journal)
             :end (.length journal)
             :replacement "\n; __eid: 2\ncommodity INR\n"}]
           (:mutations plan)))))

(deftest flush-mutations-deletes-empty-files
  (let [journal (temp-journal "; __eid: 1\ncommodity EUR\n")]
    (byte-ops/flush-mutations!
     {:file journal
      :mutations [{:start 0
                   :end (.length journal)
                   :replacement nil}]})
    (is (false? (.exists journal)))))

(deftest plan-then-flush-publishes-entity-mutations
  (let [journal (temp-journal "; __eid: 1\ncommodity EUR\n")]
    (byte-ops/flush-mutations!
     (byte-ops/plan-entity-mutations
      journal
      (byte-ops/find-entity-address-bytes journal [1 2])
      [{:eid 1 :body "commodity EUR ; updated"}
       {:eid 2 :body "commodity USD"}]))
    (is (= (str "; __eid: 1\ncommodity EUR ; updated\n\n"
                "; __eid: 2\ncommodity USD\n")
           (slurp journal)))))

(deftest plan-ordered-entity-mutations-rewrites-a-file-in-the-given-order
  (let [journal (temp-journal
                 (str "; __eid: 1\ncommodity EUR\n\n"
                      "; __eid: 2\ncommodity USD\n"))]
    (byte-ops/flush-mutations!
     (byte-ops/plan-ordered-entity-mutations
      journal
      [{:eid 2 :body "commodity USD"}
       {:eid 1 :body "commodity EUR"}]))
    (is (= (str "; __eid: 2\ncommodity USD\n\n"
                "; __eid: 1\ncommodity EUR\n")
           (slurp journal)))))
