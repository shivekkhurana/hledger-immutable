(ns hledger-immutable.cli
  (:require [babashka.cli :as cli]
            [cheshire.core :as json]
            [clojure.string :as str]
            [hledger-immutable.doctor :as doctor]
            [hledger-immutable.mutation :as mutation]
            [hledger-immutable.projection :as projection]
            [hledger-immutable.projector :as projector]))

(def help-option
  {:key :help
   :alias :h
   :description "Show this help text."})

(defn- option-fragment
  [{:keys [key alias long? negated? optional? placeholder required?]}]
  (let [option (if negated?
                 (str "--no-" (name key))
                 (if (and (not long?) alias)
                   (str "-" (name alias))
                   (str "--" (name key))))
        fragment (if placeholder
                   (str option " <" placeholder ">")
                   option)]
    (if (or optional? (not required?))
      (str "[" fragment "]")
      fragment)))

(defn- command-line
  [{:keys [cmds options]}]
  (str/join " "
            (into ["  hledger-immutable" (str/join " " cmds)]
                  (map option-fragment options))))

(defn- option-usage
  [{:keys [key alias negated? placeholder description]}]
  (let [long-option (if negated?
                      (str "--no-" (name key))
                      (str "--" (name key)))
        alias (when-not negated? alias)
        option-text (cond-> (if alias
                              (str "-" (name alias) ", " long-option)
                              long-option)
                      placeholder (str " <" placeholder ">"))]
    (format "  %-34s %s" option-text description)))

(defn- example-lines
  [items]
  (mapcat (fn [{:keys [description command]}]
            [(str "  " description)
             (str "    " command)])
          items))

(declare command-help)

(defn- require-option
  [opts {:keys [key]}]
  (or (get opts key)
      (throw (ex-info "Missing required option"
                      {:option key}))))

(defn- parse-json-object
  "Parse JSON object with keyword keys for business-shaped input."
  [text]
  (let [value (json/parse-string text true)]
    (when-not (map? value)
      (throw (ex-info "--data must be a JSON object"
                      {:data text})))
    value))

(defn- parse-json-raw
  "Parse JSON value with string keys for patch or raw input."
  [text]
  (json/parse-string text))

(defn- parse-json-array
  "Parse JSON array."
  [text]
  (let [value (json/parse-string text)]
    (when-not (sequential? value)
      (throw (ex-info "--data must be a JSON array"
                      {:data text})))
    (mapv vec value)))

(defn- parse-option-value
  [parse value]
  (case parse
    :json-object (parse-json-object value)
    :json-raw (parse-json-raw value)
    :json-array (parse-json-array value)
    value))

(defn- normalize-options
  [{:keys [options]} opts]
  (reduce
   (fn [result {:keys [key required? default parse] :as option}]
     (let [present? (contains? opts key)]
       (cond
         present? (assoc result key (parse-option-value parse (get opts key)))
         required? (assoc result key (require-option opts option))
         (contains? option :default) (assoc result key default)
         :else result)))
   {}
   options))

(defn- validate-command-options
  [{:keys [options]} opts]
  (let [allowed-keys (conj (set (map :key options)) :help)
        unknown-keys (seq (remove allowed-keys (keys opts)))]
    (when unknown-keys
      (throw (ex-info "Unknown option for command"
                      {:options (vec unknown-keys)})))))

(defn- prn-command
  [f]
  (fn [opts]
    (prn (f opts))))

(defn- println-json-command
  [f]
  (fn [opts]
    (println (json/generate-string (f opts)))))

(defn- add-command
  [f]
  (prn-command
   (fn [{:keys [workspace data project]}]
     (f {:workspace workspace
         :data data
         :project? project}))))

(def workspace-option
  {:key :workspace
   :alias :w
   :required? true
   :placeholder "workspace"
   :description "Workspace directory containing immutable.sqlite and projected journals."})

(def business-data-option
  {:key :data
   :alias :d
   :required? true
   :parse :json-object
   :placeholder "json"
   :description "JSON object with business fields for this entity type."})

(def patch-data-option
  {:key :data
   :alias :d
   :required? true
   :parse :json-raw
   :placeholder "json"
   :description "JSON object with set, unset, after_eid, or before_eid."})

(def datoms-data-option
  {:key :data
   :alias :d
   :required? true
   :parse :json-array
   :placeholder "json"
   :description "JSON array of [eid, attr, value, retract?] datom vectors."})

(def hledger-error-data-option
  {:key :data
   :alias :d
   :required? true
   :parse :json-raw
   :placeholder "json"
   :description "JSON object with captured hledger stderr."})

(def no-project-option
  {:key :project
   :negated? true
   :default true
   :description "Append datoms without updating projected journal files."})

(def entity-id-option
  {:key :entity-id
   :alias :eid
   :coerce :long
   :required? true
   :placeholder "entity-id"})

(def parent-entity-id-option
  {:key :parent-entity-id
   :alias :peid
   :coerce :long
   :required? true
   :placeholder "parent-entity-id"
   :description "Stable eid of the top-level entity or posting to tag."})

(def tag-key-option
  {:key :key
   :required? true
   :placeholder "key"
   :description "Tag key to add."})

(def tag-value-option
  {:key :value
   :placeholder "value"
   :description "Optional tag value."})

(def add-options
  [workspace-option business-data-option no-project-option])

(def update-options
  [workspace-option
   (assoc entity-id-option
          :description "Stable eid of the entity to update.")
   patch-data-option
   no-project-option])

(def delete-options
  [workspace-option
   (assoc entity-id-option
          :description "Stable eid of the entity to delete, including descendants.")
   (assoc no-project-option
          :description "Append retraction datoms without updating projected journal files.")])

(def append-options
  [workspace-option datoms-data-option no-project-option])

(def add-tag-options
  [workspace-option parent-entity-id-option tag-key-option tag-value-option no-project-option])

(def command-definitions
  [{:cmds ["init"]
    :run (prn-command
          (fn [{:keys [workspace]}]
            (projector/init! workspace)))
    :summary "Create or open a workspace database and apply migrations."
    :options [workspace-option]
    :examples [{:description "Initialize ./books as an immutable workspace."
                :command "hledger-immutable init -w ./books"}]}

   {:cmds ["add-commodity"]
    :run (add-command mutation/add-commodity!)
    :summary "Add a commodity entity from business-shaped JSON."
    :options add-options
    :examples [{:description "Add a commodity and project it."
                :command "hledger-immutable add-commodity -w ./books -d '{\"file\":\"main.journal\",\"name\":\"EUR\"}'"}]}

   {:cmds ["add-price"]
    :run (add-command mutation/add-price!)
    :summary "Add a price entity from business-shaped JSON."
    :options add-options
    :examples [{:description "Add a price entry."
                :command "hledger-immutable add-price -w ./books -d '{\"file\":\"prices.journal\",\"date\":\"2026-06-24\",\"commodity\":\"USD\",\"value\":\"INR 83.50\"}'"}]}

   {:cmds ["add-account"]
    :run (add-command mutation/add-account!)
    :summary "Add an account entity from business-shaped JSON."
    :options add-options
    :examples [{:description "Add an account declaration."
                :command "hledger-immutable add-account -w ./books -d '{\"file\":\"accounts.journal\",\"name\":\"assets:bank\"}'"}]}

   {:cmds ["add-transaction"]
    :run (add-command mutation/add-transaction!)
    :summary "Add a transaction with postings and tags from business-shaped JSON."
    :options add-options
    :examples [{:description "Add a transaction with two postings and a tag."
                :command "hledger-immutable add-transaction -w ./books -d '{\"file\":\"2026.journal\",\"date\":\"2026-06-24\",\"description\":\"Lunch\",\"postings\":[{\"account\":\"expenses:food\",\"amount\":\"INR 500\"},{\"account\":\"liabilities:card\"}]}'"}]}

   {:cmds ["add-budget"]
    :run (add-command mutation/add-budget!)
    :summary "Add a periodic transaction (budget) from business-shaped JSON."
    :options add-options
    :examples [{:description "Add a monthly budget."
                :command "hledger-immutable add-budget -w ./books -d '{\"file\":\"budget.journal\",\"period\":\"monthly\",\"description\":\"Budget\",\"postings\":[{\"account\":\"expenses:rent\",\"amount\":\"INR 30000\"}]}'"}]}

   {:cmds ["add-periodic-transaction"]
    :run (add-command mutation/add-periodic-transaction!)
    :summary "Add a periodic transaction from business-shaped JSON."
    :options add-options
    :examples [{:description "Add a periodic transaction."
                :command "hledger-immutable add-periodic-transaction -w ./books -d '{\"file\":\"budget.journal\",\"period\":\"monthly\",\"postings\":[{\"account\":\"expenses:rent\",\"amount\":\"INR 30000\"}]}'"}]}

   {:cmds ["add-include"]
    :run (add-command mutation/add-include!)
    :summary "Add an include directive from business-shaped JSON."
    :options add-options
    :examples [{:description "Add an include directive."
                :command "hledger-immutable add-include -w ./books -d '{\"file\":\"main.journal\",\"path\":\"prices.journal\"}'"}]}

   {:cmds ["add-alias"]
    :run (add-command mutation/add-alias!)
    :summary "Add an account alias from business-shaped JSON."
    :options add-options
    :examples [{:description "Add an alias."
                :command "hledger-immutable add-alias -w ./books -d '{\"file\":\"main.journal\",\"old\":\"bank\",\"new\":\"assets:bank:hdfc\"}'"}]}

   {:cmds ["add-decimal-mark"]
    :run (add-command mutation/add-decimal-mark!)
    :summary "Add a decimal-mark directive from business-shaped JSON."
    :options add-options
    :examples [{:description "Add a decimal-mark directive."
                :command "hledger-immutable add-decimal-mark -w ./books -d '{\"file\":\"main.journal\",\"value\":\".\"}'"}]}

   {:cmds ["add-default-commodity"]
    :run (add-command mutation/add-default-commodity!)
    :summary "Add a default commodity directive from business-shaped JSON."
    :options add-options
    :examples [{:description "Add a default commodity."
                :command "hledger-immutable add-default-commodity -w ./books -d '{\"file\":\"main.journal\",\"value\":\"INR 1,000.00\"}'"}]}

   {:cmds ["add-payee"]
    :run (add-command mutation/add-payee!)
    :summary "Add a payee directive from business-shaped JSON."
    :options add-options
    :examples [{:description "Add a payee."
                :command "hledger-immutable add-payee -w ./books -d '{\"file\":\"main.journal\",\"name\":\"Amazon\"}'"}]}

   {:cmds ["add-tag-declaration"]
    :run (add-command mutation/add-tag-declaration!)
    :summary "Add a tag declaration from business-shaped JSON."
    :options add-options
    :examples [{:description "Add a tag declaration."
                :command "hledger-immutable add-tag-declaration -w ./books -d '{\"file\":\"main.journal\",\"name\":\"project\"}'"}]}

   {:cmds ["add-tag"]
    :run (prn-command
          (fn [{:keys [workspace parent-entity-id key value project]}]
            (mutation/add-tag!
             {:workspace workspace
              :data (cond-> {:parent-eid parent-entity-id
                              :key key}
                      (some? value)
                      (assoc :value value))
              :project? project})))
    :summary "Add a tag to an existing top-level entity or posting."
    :options add-tag-options
    :examples [{:description "Add a valued tag to eid 3."
                :command "hledger-immutable add-tag -w ./books -peid 3 --key project --value ops"}
               {:description "Add a valueless tag without projecting."
                :command "hledger-immutable add-tag -w ./books -peid 3 --key reviewed --no-project"}]}

   {:cmds ["update-entity"]
    :run (prn-command
          (fn [{:keys [workspace entity-id data project]}]
            (mutation/update-entity!
             {:workspace workspace
              :eid entity-id
              :data data
              :project? project})))
    :summary "Apply a set/unset patch to an existing entity."
    :options update-options
    :examples [{:description "Rename commodity eid 1."
                :command "hledger-immutable update-entity -w ./books -eid 1 -d '{\"set\":{\"commodity/name\":\"USD\"}}'"}
               {:description "Move entity eid 3 after eid 7 in its ordering scope."
                :command "hledger-immutable update-entity -w ./books -eid 3 -d '{\"after_eid\":7}'"}
               {:description "Move entity eid 3 before eid 7 in its ordering scope."
                :command "hledger-immutable update-entity -w ./books -eid 3 -d '{\"before_eid\":7}'"}]}

   {:cmds ["delete-entity"]
    :run (prn-command
          (fn [{:keys [workspace entity-id project]}]
            (mutation/delete-entity!
             {:workspace workspace
              :eid entity-id
              :project? project})))
    :summary "Retract an entity and all its descendants."
    :options delete-options
    :examples [{:description "Delete transaction eid 3 and its postings and tags."
                :command "hledger-immutable delete-entity -w ./books -eid 3"}]}

   {:cmds ["append-datoms"]
    :run (prn-command
          (fn [{:keys [workspace data project]}]
            (mutation/append-datoms!
             {:workspace workspace
              :datoms data
              :project? project})))
    :summary "Append raw authored datoms to the event-log."
    :options append-options
    :examples [{:description "Append a single assertion datom."
                :command "hledger-immutable append-datoms -w ./books -d '[[1,\"commodity/name\",\"USD\",false]]'"}]}

   {:cmds ["project"]
    :run (prn-command
          (fn [{:keys [workspace upto]}]
            (projector/project! workspace upto)))
    :summary "Project pending datoms into the workspace, or project journals as of a sequence."
    :options [workspace-option
              {:key :upto
               :coerce :long
               :placeholder "sequence"
               :required? false
               :description "Project the journals as of this sequence into .projections/workspace-<sequence>. Does not advance the checkpoint."}]
    :examples [{:description "Project pending datoms and advance the checkpoint."
                :command "hledger-immutable project -w ./books"}
               {:description "Project the journals as of event-log sequence 42."
                :command "hledger-immutable project -w ./books --upto 42"}]}

   {:cmds ["read-journal"]
    :run (println-json-command
          (fn [{:keys [workspace journal limit after-position]}]
            (projection/read-journal-page
             {:workspace-directory workspace
              :journal journal
              :limit limit
              :after-position after-position})))
    :summary "Read a position-cursor page from the latest projected journal file as JSON."
    :options [(assoc workspace-option
                :description "Workspace directory containing projected journals.")
              {:key :journal
               :alias :j
               :required? true
               :placeholder "journal"
               :description "Simple local .journal filename to read."}
              {:key :limit
               :coerce :long
               :default 100
               :placeholder "count"
               :description "Maximum top-level entity count to return. Defaults to 100."}
              {:key :after-position
               :coerce :long
               :placeholder "position"
               :description "Exclusive entity/position cursor. Omit for the first page."}]
   :examples [{:description "Read the first 100 top-level entities from main.journal."
                :command "hledger-immutable read-journal -w ./books -j main.journal"}
               {:description "Read the next page after position 8000."
                :command "hledger-immutable read-journal -w ./books -j main.journal --limit 100 --after-position 8000"}]}

   {:cmds ["explain-hledger-error"]
    :run (println-json-command
          (fn [{:keys [workspace data]}]
            (doctor/explain-hledger-error
             {:workspace-directory workspace
              :stderr (get data "stderr")})))
    :summary "Explain hledger stderr using projected entity information from a workspace."
    :options [(assoc workspace-option
                :description "Workspace directory containing projected journals.")
              hledger-error-data-option]
    :examples [{:description "Explain captured hledger stderr."
                :command "hledger-immutable explain-hledger-error -w ./books -d '{\"stderr\":\"hledger: Error: ./books/main.journal:12-14:\\n...\"}'"}]}

   {:cmds ["status"]
    :run (prn-command
          (fn [{:keys [workspace]}]
            (projector/status workspace)))
    :summary "Show event-log and last projected datom sequence status for a workspace."
    :options [(assoc workspace-option
                :description "Workspace directory to inspect.")]
    :examples [{:description "Show the highest event-log sequence, last projected datom sequence number, and pending datom count."
                :command "hledger-immutable status -w ./books"}]}])

(defn- command-help
  [{:keys [summary options examples] :as definition}]
  (str/join
   "\n"
   (concat
    [(str "Command: hledger-immutable " (str/join " " (:cmds definition)))
     ""
     summary
     ""
     "Usage:"
     (command-line definition)
     ""
     "Options:"]
    (map option-usage options)
    [(option-usage help-option)
     ""
     "Examples:"]
    (example-lines examples))))

(defn- command-spec
  [{:keys [options]}]
  (into
   {(:key help-option) (select-keys help-option [:alias :coerce])}
   (map (fn [{:keys [key] :as option}]
          [key (select-keys option [:alias :coerce])])
        options)))

(defn- command
  [definition]
  (fn [{:keys [opts]}]
    (validate-command-options definition opts)
    (if (:help opts)
      (println (command-help definition))
      ((:run definition) (normalize-options definition opts)))))

(defn- usage
  []
  (str/join
   "\n"
   (concat ["Usage:"]
           (map command-line command-definitions)
           [""
            "Run `hledger-immutable <command> -h` or `hledger-immutable <command> --help` for command documentation and examples."])))

(def dispatch-table
  (conj
   (mapv (fn [{:keys [cmds] :as definition}]
           {:cmds cmds :fn (command definition) :spec (command-spec definition)})
         command-definitions)
   {:cmds [] :fn (fn [_] (println (usage)))}))

(defn -main
  [& args]
  (try
    (cli/dispatch dispatch-table args)
    (catch Throwable cause
      (binding [*out* *err*]
        (println "error:" (ex-message cause))
        (when-let [data (ex-data cause)]
          (prn data)))
      (System/exit 1))))
