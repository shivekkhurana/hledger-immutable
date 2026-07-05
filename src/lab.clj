(ns lab
  "REPL playground for driving hledger-immutable through the public CLI."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hledger-immutable.cli :as cli]))

(def workspace
  "Scratch workspace used by this lab.

  The path is under java.io.tmpdir so it is separate from any real books."
  (str (System/getProperty "java.io.tmpdir")
       "./lab"))

(defn json
  [value]
  (json/generate-string value))

(defn- delete-recursively!
  [file]
  (when (.exists file)
    (when (.isDirectory file)
      (doseq [child (.listFiles file)]
        (delete-recursively! child)))
    (io/delete-file file)))

(defn reset-workspace!
  "Delete the lab workspace so the eid notes below line up from a clean run."
  []
  (let [directory (io/file workspace)
        tmpdir (System/getProperty "java.io.tmpdir")]
    (when-not (str/starts-with? (.getCanonicalPath directory)
                                (.getCanonicalPath (io/file tmpdir)))
      (throw (ex-info "Refusing to delete a non-temp lab workspace"
                      {:workspace workspace
                       :tmpdir tmpdir})))
    (delete-recursively! directory)
    (.mkdirs directory)
    (.getPath directory)))

(defn cli-call!
  "Print a narration and call the CLI exactly as a UI adapter would."
  [narration & args]
  (println)
  (println (str ";; " narration))
  (println (str "$ hledger-immutable " (str/join " " args)))
  (apply cli/-main args))

(defn add!
  [narration command data]
  (cli-call! narration command "-w" workspace "-d" (json data)))

(defn update!
  [narration eid data]
  (cli-call! narration "update-entity" "-w" workspace "-eid" (str eid)
             "-d" (json data)))

(defn delete!
  [narration eid]
  (cli-call! narration "delete-entity" "-w" workspace "-eid" (str eid)))

(defn read-journal!
  ([journal]
   (read-journal! journal 100 nil))
  ([journal limit after-position]
   (let [args (cond-> ["read-journal" "-w" workspace "-j" journal
                       "--limit" (str limit)]
                after-position
                (into ["--after-position" (str after-position)]))]
     (apply cli-call!
            (str "Read " journal " as a UI position-cursor page.")
            args))))

(comment
  ;; Rich comment lab: two months of a small services business.
  ;;
  ;; Eval one form at a time. The command prints the same EDN or JSON shape that
  ;; the command-line program prints, while the narration above each command
  ;; explains what business event the UI is simulating.
  ;;
  ;; Keep this sequence deterministic by starting with a clean temp workspace.

  (reset-workspace!)

  (cli-call! "Create or open the immutable workspace database."
             "init" "-w" workspace)

  ;; -------------------------------------------------------------------------
  ;; Files and standing journal directives
  ;; -------------------------------------------------------------------------

  (add! "eid 1: main.journal includes account declarations."
        "add-include"
        {:file "main.journal"
         :path "accounts.journal"})

  (add! "eid 2: main.journal includes the multi-currency price journal."
        "add-include"
        {:file "main.journal"
         :path "prices.journal"})

  (add! "eid 3: use dot as the decimal mark."
        "add-decimal-mark"
        {:file "main.journal"
         :value "."})

  (add! "eid 4: INR is the default display commodity for the books."
        "add-default-commodity"
        {:file "main.journal"
         :value "INR 1,000.00"})

  (add! "eid 5: declare a project tag used by client work and internal ops."
        "add-tag-declaration"
        {:file "main.journal"
         :name "project"})

  (add! "eid 6: declare a client tag."
        "add-tag-declaration"
        {:file "main.journal"
         :name "client"})

  (add! "eid 7: declare a channel tag for payment rails and marketplaces."
        "add-tag-declaration"
        {:file "main.journal"
         :name "channel"})

  (add! "eid 8: declare a tax tag."
        "add-tag-declaration"
        {:file "main.journal"
         :name "tax"})

  (add! "eid 9: declare Stripe as a known payee."
        "add-payee"
        {:file "main.journal"
         :name "Stripe"})

  (add! "eid 10: declare Wise as a known payee."
        "add-payee"
        {:file "main.journal"
         :name "Wise"})

  (add! "eid 11: declare AWS as a known payee."
        "add-payee"
        {:file "main.journal"
         :name "AWS"})

  ;; -------------------------------------------------------------------------
  ;; Commodities and prices
  ;; -------------------------------------------------------------------------

  (add! "eid 12: INR operating currency."
        "add-commodity"
        {:file "commodities.journal"
         :name "INR"})

  (add! "eid 13: USD client billing currency."
        "add-commodity"
        {:file "commodities.journal"
         :name "USD"})

  (add! "eid 14: EUR contractor and vendor currency."
        "add-commodity"
        {:file "commodities.journal"
         :name "EUR"})

  (add! "eid 15: GBP marketplace settlement currency."
        "add-commodity"
        {:file "commodities.journal"
         :name "GBP"})

  (add! "eid 16: January opening USD price."
        "add-price"
        {:file "prices.journal"
         :date "2026-01-01"
         :commodity "USD"
         :value "INR 83.10"})

  (add! "eid 17: January opening EUR price."
        "add-price"
        {:file "prices.journal"
         :date "2026-01-01"
         :commodity "EUR"
         :value "INR 90.40"})

  (add! "eid 18: January opening GBP price."
        "add-price"
        {:file "prices.journal"
         :date "2026-01-01"
         :commodity "GBP"
         :value "INR 105.20"})

  (add! "eid 19: February opening USD price."
        "add-price"
        {:file "prices.journal"
         :date "2026-02-01"
         :commodity "USD"
         :value "INR 83.70"})

  (add! "eid 20: February opening EUR price."
        "add-price"
        {:file "prices.journal"
         :date "2026-02-01"
         :commodity "EUR"
         :value "INR 91.15"})

  (add! "eid 21: February opening GBP price."
        "add-price"
        {:file "prices.journal"
         :date "2026-02-01"
         :commodity "GBP"
         :value "INR 106.05"})

  ;; -------------------------------------------------------------------------
  ;; Account declarations
  ;; -------------------------------------------------------------------------

  (add! "eid 22: local INR bank account."
        "add-account"
        {:file "accounts.journal"
         :name "assets:bank:hdfc"})

  (add! "eid 23: Wise USD balance."
        "add-account"
        {:file "accounts.journal"
         :name "assets:bank:wise:usd"})

  (add! "eid 24: Wise EUR balance."
        "add-account"
        {:file "accounts.journal"
         :name "assets:bank:wise:eur"})

  (add! "eid 25: petty cash."
        "add-account"
        {:file "accounts.journal"
         :name "assets:cash"})

  (add! "eid 26: open client receivables."
        "add-account"
        {:file "accounts.journal"
         :name "assets:receivable:clients"})

  (add! "eid 27: company credit card."
        "add-account"
        {:file "accounts.journal"
         :name "liabilities:credit-card"})

  (add! "eid 28: GST or VAT tax payable."
        "add-account"
        {:file "accounts.journal"
         :name "liabilities:tax:gst"})

  (add! "eid 29: opening equity."
        "add-account"
        {:file "accounts.journal"
         :name "equity:opening-balances"})

  (add! "eid 30: consulting income."
        "add-account"
        {:file "accounts.journal"
         :name "income:consulting"})

  (add! "eid 31: SaaS subscription income."
        "add-account"
        {:file "accounts.journal"
         :name "income:saas"})

  (add! "eid 32: software subscriptions."
        "add-account"
        {:file "accounts.journal"
         :name "expenses:software"})

  (add! "eid 33: cloud hosting."
        "add-account"
        {:file "accounts.journal"
         :name "expenses:cloud"})

  (add! "eid 34: contractor costs."
        "add-account"
        {:file "accounts.journal"
         :name "expenses:contractors"})

  (add! "eid 35: foreign exchange and conversion fees."
        "add-account"
        {:file "accounts.journal"
         :name "expenses:fx-fees"})

  (add! "eid 36: meals and meetings."
        "add-account"
        {:file "accounts.journal"
         :name "expenses:meals"})

  (add! "eid 37: travel."
        "add-account"
        {:file "accounts.journal"
         :name "expenses:travel"})

  (add! "eid 38: bank charges."
        "add-account"
        {:file "accounts.journal"
         :name "expenses:bank-fees"})

  ;; -------------------------------------------------------------------------
  ;; Recurring plan
  ;; -------------------------------------------------------------------------

  (add! "eids 39-42: monthly software budget with a root project tag."
        "add-budget"
        {:file "budget.journal"
         :period "monthly"
         :description "Baseline operating software budget"
         :tags [{:name "project" :value "ops"}]
         :postings [{:account "expenses:software"
                     :amount "INR 15000"}
                    {:account "assets:bank:hdfc"}]})

  ;; -------------------------------------------------------------------------
  ;; January transactions
  ;; -------------------------------------------------------------------------

  (add! "eids 43-47: seed opening balances across INR, USD, and EUR accounts."
        "add-transaction"
        {:file "2026-01.journal"
         :date "2026-01-01"
         :status "*"
         :code "OPEN-2026"
         :description "Opening balances"
         :postings [{:account "assets:bank:hdfc"
                     :amount "INR 500000"}
                    {:account "assets:bank:wise:usd"
                     :amount "USD 12000"}
                    {:account "assets:bank:wise:eur"
                     :amount "EUR 3500"}
                    {:account "equity:opening-balances"}]})

  (add! "eids 48-52: issue a USD consulting invoice to Acme."
        "add-transaction"
        {:file "2026-01.journal"
         :date "2026-01-05"
         :status "!"
         :code "INV-ACME-001"
         :description "Acme consulting invoice"
         :tags [{:name "client" :value "acme"}
                {:name "project" :value "migration"}]
         :postings [{:account "assets:receivable:clients"
                     :amount "USD 7200"}
                    {:account "income:consulting"}]})

  (add! "eids 53-57: receive part of the Acme invoice through Wise."
        "add-transaction"
        {:file "2026-01.journal"
         :date "2026-01-12"
         :status "*"
         :code "RCPT-ACME-001"
         :description "Acme partial payment via Wise"
         :tags [{:name "client" :value "acme"}
                {:name "channel" :value "wise"}]
         :postings [{:account "assets:bank:wise:usd"
                     :amount "USD 5000"}
                    {:account "assets:receivable:clients"}]})

  (add! "eids 58-63: pay a EUR contractor for the migration project."
        "add-transaction"
        {:file "2026-01.journal"
         :date "2026-01-15"
         :status "*"
         :code "BILL-MARIA-001"
         :description "Maria frontend implementation"
         :tags [{:name "project" :value "migration"}
                {:name "client" :value "acme"}]
         :postings [{:account "expenses:contractors"
                     :amount "EUR 1800"
                     :tags [{:name "tax" :value "reverse-charge"}]}
                    {:account "assets:bank:wise:eur"}]})

  (add! "eids 64-68: pay the January AWS bill in USD."
        "add-transaction"
        {:file "2026-01.journal"
         :date "2026-01-20"
         :status "*"
         :code "AWS-JAN"
         :description "AWS January hosting"
         :tags [{:name "project" :value "ops"}
                {:name "channel" :value "card"}]
         :postings [{:account "expenses:cloud"
                     :amount "USD 640"}
                    {:account "liabilities:credit-card"}]})

  (add! "eids 69-72: local client meeting paid from petty cash."
        "add-transaction"
        {:file "2026-01.journal"
         :date "2026-01-24"
         :status "*"
         :description "Client lunch with Acme"
         :tags [{:name "client" :value "acme"}]
         :postings [{:account "expenses:meals"
                     :amount "INR 4200"}
                    {:account "assets:cash"}]})

  (add! "eids 73-77: convert part of the USD balance to INR and record fees."
        "add-transaction"
        {:file "2026-01.journal"
         :date "2026-01-29"
         :status "*"
         :code "WISE-FX-JAN"
         :description "Convert USD proceeds to INR"
         :tags [{:name "channel" :value "wise"}]
         :postings [{:account "assets:bank:hdfc"
                     :amount "INR 250000"}
                    {:account "expenses:fx-fees"
                     :amount "INR 850"}
                    {:account "assets:bank:wise:usd"}]})

  ;; -------------------------------------------------------------------------
  ;; February transactions
  ;; -------------------------------------------------------------------------

  (add! "eids 78-82: issue a GBP marketplace invoice for February SaaS usage."
        "add-transaction"
        {:file "2026-02.journal"
         :date "2026-02-03"
         :status "!"
         :code "INV-MARKET-002"
         :description "Marketplace SaaS subscription revenue"
         :tags [{:name "client" :value "marketplace"}
                {:name "project" :value "subscriptions"}]
         :postings [{:account "assets:receivable:clients"
                     :amount "GBP 2400"}
                    {:account "income:saas"}]})

  (add! "eids 83-88: receive the marketplace settlement net of fees."
        "add-transaction"
        {:file "2026-02.journal"
         :date "2026-02-10"
         :status "*"
         :code "RCPT-MARKET-002"
         :description "Marketplace payout after platform fee"
         :tags [{:name "client" :value "marketplace"}
                {:name "channel" :value "stripe"}]
         :postings [{:account "assets:bank:hdfc"
                     :amount "INR 248000"}
                    {:account "expenses:bank-fees"
                     :amount "INR 6400"}
                    {:account "assets:receivable:clients"}]})

  (add! "eids 89-93: buy annual design software on the card."
        "add-transaction"
        {:file "2026-02.journal"
         :date "2026-02-14"
         :status "*"
         :code "FIGMA-ANNUAL"
         :description "Figma annual team plan"
         :tags [{:name "project" :value "ops"}
                {:name "channel" :value "card"}]
         :postings [{:account "expenses:software"
                     :amount "USD 540"}
                    {:account "liabilities:credit-card"}]})

  (add! "eids 94-99: book February GST payable for local revenue."
        "add-transaction"
        {:file "2026-02.journal"
         :date "2026-02-20"
         :status "!"
         :code "GST-FEB"
         :description "GST payable on local SaaS sales"
         :tags [{:name "tax" :value "gst"}
                {:name "project" :value "subscriptions"}]
         :postings [{:account "income:saas"
                     :amount "INR 118000"}
                    {:account "liabilities:tax:gst"
                     :amount "INR 18000"}
                    {:account "assets:receivable:clients"}]})

  (add! "eids 100-104: reimburse founder travel from the INR bank account."
        "add-transaction"
        {:file "2026-02.journal"
         :date "2026-02-22"
         :status "*"
         :code "TRAVEL-BLR"
         :description "Bangalore customer visit"
         :tags [{:name "project" :value "sales"}]
         :postings [{:account "expenses:travel"
                     :amount "INR 36500"
                     :tags [{:name "client" :value "prospect-nova"}]}
                    {:account "assets:bank:hdfc"}]})

  (add! "eids 105-109: pay the February AWS bill in USD."
        "add-transaction"
        {:file "2026-02.journal"
         :date "2026-02-25"
         :status "*"
         :code "AWS-FEB"
         :description "AWS February hosting"
         :tags [{:name "project" :value "ops"}
                {:name "channel" :value "card"}]
         :postings [{:account "expenses:cloud"
                     :amount "USD 710"}
                    {:account "liabilities:credit-card"}]})

  ;; -------------------------------------------------------------------------
  ;; Edits, retractions, deletion, raw datoms, and ordering checks
  ;; -------------------------------------------------------------------------

  (update! "Edit eid 48: mark the Acme invoice cleared and clarify the text."
           48
           {"set" {"transaction/status" "*"
                   "transaction/description" "Acme consulting invoice, accepted"}})

  (update! "Retract eid 48 transaction/code by unsetting it."
           48
           {"unset" ["transaction/code"]})

  (update! "Edit child posting eid 59: contractor bill was EUR 1850, not 1800."
           59
           {"set" {"posting/amount" "EUR 1850"}})

  (update! "Edit child tag eid 61: move the contractor bill from migration to support."
           61
           {"set" {"tag/value" "support"}})

  (update! "Move price eid 19 to a dedicated corrections journal."
           19
           {"set" {"entity/file" "price-corrections.journal"}})

  (update! "Reorder account eid 38 directly after the credit-card account eid 27."
           38
           {"after_eid" 27})

  (delete! "Delete mistaken travel reimbursement eid 100 and cascade its postings and tag."
           100)

  (add! "eids 110-113: re-enter the travel reimbursement with the correct project."
        "add-transaction"
        {:file "2026-02.journal"
         :date "2026-02-22"
         :status "*"
         :code "TRAVEL-BLR"
         :description "Bangalore customer visit"
         :tags [{:name "project" :value "customer-development"}]
         :postings [{:account "expenses:travel"
                     :amount "INR 36500"}
                    {:account "assets:bank:hdfc"}]})

  (cli-call! "Raw escape hatch: append a stale retraction for eid 13; folding should no-op because the value does not match."
             "append-datoms" "-w" workspace "-d"
             (json [[13 "commodity/name" "US DOLLAR" true]]))

  (cli-call! "Show event-log head, last-projected-datom-sequence-number, and pending count."
             "status" "-w" workspace)

  ;; -------------------------------------------------------------------------
  ;; Journal pages that a UI can inspect
  ;; -------------------------------------------------------------------------

  (read-journal! "main.journal")
  (read-journal! "accounts.journal")
  (read-journal! "commodities.journal")
  (read-journal! "prices.journal")
  (read-journal! "price-corrections.journal")
  (read-journal! "budget.journal")
  (read-journal! "2026-01.journal" 5 nil)
  (read-journal! "2026-02.journal" 5 nil)

  ;; Use the previous page's last_entity_position as the cursor.
  (read-journal! "2026-01.journal" 2 64000)
  (read-journal! "2026-02.journal" 2 105000)

  ;; Historical projection: replace 40 with a sequence number you saw in status
  ;; or command output. This writes to .projections/workspace-40 and does not
  ;; advance the latest projection checkpoint.
  (cli-call! "Project the journals as of event-log sequence 40."
             "project" "-w" workspace "--upto" "40"))
