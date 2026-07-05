(ns hledger-immutable.byte-ops
  "Constant-memory byte operations for marked hledger journal entities."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedInputStream BufferedOutputStream ByteArrayOutputStream FileOutputStream RandomAccessFile]
           [java.nio ByteBuffer]
           [java.nio.channels Channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.util Arrays]))

(def ^:private marker-prefix "; __eid: ")
(def ^:private marker-meta-separator " __meta: ")
(def ^:private copy-buffer-size (* 128 1024))

(defn- trim-cr
  "Remove a trailing carriage return from a scanned line, when present."
  [text]
  (if (str/ends-with? text "\r")
    (subs text 0 (dec (count text)))
    text))

(defn- split-marker-payload
  "Split marker text into eid text and optional metadata EDN text."
  [payload]
  (if-let [index (str/index-of payload marker-meta-separator)]
    {:raw-eid-text (subs payload 0 index)
     :metadata-text (subs payload (+ index (count marker-meta-separator)))}
    {:raw-eid-text payload}))

(defn- parsed-marker-eid
  "Recover simple numeric eids from marker text, otherwise keep the marker text."
  [raw-eid-text]
  (if (re-matches #"-?\d+" raw-eid-text)
    (Long/parseLong raw-eid-text)
    raw-eid-text))

(defn- parse-marker-eid
  "Return the eid from a raw marker line, or nil when the line is not a marker."
  [line-bytes prefix]
  (let [bytes (.toByteArray line-bytes)
        ;; Lines are accumulated without the trailing LF byte. If the file uses
        ;; CRLF endings, the CR byte is still present here and must not become
        ;; part of the eid.
        length (if (and (pos? (alength bytes))
                        (= 13 (bit-and 0xff (aget bytes (dec (alength bytes))))))
                 (dec (alength bytes))
                 (alength bytes))]
    (when (and (> length (alength prefix))
               ;; Check the marker prefix in bytes without decoding the whole
               ;; line, keeping byte offsets aligned with the original file.
               (Arrays/equals bytes 0 (alength prefix)
                              prefix 0 (alength prefix)))
      (:raw-eid-text
       (split-marker-payload
        (String. bytes
                 (alength prefix)
                 (- length (alength prefix))
                 StandardCharsets/UTF_8))))))

(defn- parse-marker-line
  "Return marker information from a marker line, or nil for ordinary lines."
  [line]
  (when (str/starts-with? line marker-prefix)
    (let [{:keys [raw-eid-text metadata-text]}
          (split-marker-payload (subs (trim-cr line) (count marker-prefix)))]
      {:eid (parsed-marker-eid raw-eid-text)
       :metadata (if metadata-text
                   (try
                     (edn/read-string metadata-text)
                     (catch Exception cause
                       (throw (ex-info "Cannot parse entity marker metadata"
                                       {:line line}
                                       cause))))
                   {})})))

(defn- parse-marker-line-bytes
  "Return marker information from accumulated line bytes."
  [line-bytes]
  (parse-marker-line
   (String. (.toByteArray line-bytes) StandardCharsets/UTF_8)))

(defn- scan-entity-address-bytes
  "Stream a journal and return entity address bytes for requested eids."
  [input file raw-eid-text->eid]
  (let [prefix (.getBytes marker-prefix StandardCharsets/UTF_8)
        wanted (set (keys raw-eid-text->eid))
        line-bytes (ByteArrayOutputStream.)]
    (loop [position 0
           line-start 0
           current nil
           found {}]
      (let [value (.read input)]
        (cond
          (neg? value)
          ;; EOF closes the final open entity because there is no following
          ;; marker line to provide its end offset.
          (cond-> found
            current (assoc (:eid current)
                           {:start (:start current)
                            :end (.length file)}))

          (= 10 value)
          (let [raw-eid-text (parse-marker-eid line-bytes prefix)
                marker? (some? raw-eid-text)
                found (cond-> found
                        (and marker? current)
                        (assoc (:eid current)
                               {:start (:start current)
                                :end line-start}))]
            (.reset line-bytes)
            ;; The marker after a requested entity gives us its end. Once every
            ;; requested entity is closed, later bytes are irrelevant.
            (if (= (count found) (count wanted))
              found
              (recur (inc position)
                     (inc position)
                     (if marker?
                       (when-let [eid (get raw-eid-text->eid raw-eid-text)]
                         {:eid eid :start line-start})
                       current)
                     found)))

          :else
          (do
            ;; Store only the current line while scanning. `position` and
            ;; `line-start` carry the byte offsets, so the whole file never has
            ;; to be resident in memory.
            (.write line-bytes value)
            (recur (inc position) line-start current found)))))))

(defn find-entity-address-bytes
  "Find requested entity address bytes without loading the journal into memory.

  Returns `{eid {:start byte-offset :end byte-offset}}`. An entity address starts
  at its `; __eid:` marker and ends at the next marker or EOF. Missing eids are
  absent."
  [journal-file eids]
  (let [raw-eid-text->eid (into {} (map (juxt str identity)) eids)
        file (io/file journal-file)]
    (if (or (empty? raw-eid-text->eid) (not (.exists file)))
      {}
      (with-open [journal (FileChannel/open
                           (.toPath file)
                           (into-array java.nio.file.OpenOption
                                       [StandardOpenOption/READ]))
                  input (BufferedInputStream.
                         (Channels/newInputStream journal)
                         copy-buffer-size)]
        (scan-entity-address-bytes input file raw-eid-text->eid)))))

(defn- scan-all-entity-address-bytes
  "Stream a journal and return every marked entity address byte range."
  [input file]
  (let [prefix (.getBytes marker-prefix StandardCharsets/UTF_8)
        line-bytes (ByteArrayOutputStream.)]
    (loop [position 0
           line-start 0
           current nil
           found {}]
      (let [value (.read input)]
        (cond
          (neg? value)
          (cond-> found
            current (assoc (:eid current)
                           {:start (:start current)
                            :end (.length file)}))

          (= 10 value)
          (let [raw-eid-text (parse-marker-eid line-bytes prefix)
                marker? (some? raw-eid-text)
                found (cond-> found
                        (and marker? current)
                        (assoc (:eid current)
                               {:start (:start current)
                                :end line-start}))]
            (.reset line-bytes)
            (recur (inc position)
                   (inc position)
                   (if marker?
                     {:eid (parsed-marker-eid raw-eid-text)
                      :start line-start}
                     current)
                   found))

          :else
          (do
            (.write line-bytes value)
            (recur (inc position) line-start current found)))))))

(defn find-all-entity-address-bytes
  "Find every marked entity address byte range in one journal file."
  [journal-file]
  (let [file (io/file journal-file)]
    (if (not (.exists file))
      {}
      (with-open [journal (FileChannel/open
                           (.toPath file)
                           (into-array java.nio.file.OpenOption
                                       [StandardOpenOption/READ]))
                  input (BufferedInputStream.
                         (Channels/newInputStream journal)
                         copy-buffer-size)]
        (scan-all-entity-address-bytes input file)))))

(defn- scan-entity-address-by-line
  [input file target-line]
  (let [line-bytes (ByteArrayOutputStream.)]
    (loop [position 0
           line-start 0
           line-number 1
           current nil]
      (let [value (.read input)]
        (cond
          (neg? value)
          (let [marker (when (pos? (.size line-bytes))
                         (parse-marker-line-bytes line-bytes))
                current (cond
                          marker
                          {:eid (:eid marker)
                           :start line-start
                           :line-start line-number
                           :matched? (= target-line line-number)}

                          (and current (= target-line line-number))
                          (assoc current :matched? true)

                          :else current)]
            (when (and current (:matched? current))
              (assoc current :end (.length file))))

          (= 10 value)
          (let [marker (parse-marker-line-bytes line-bytes)
                completed (when (and marker current (:matched? current))
                            (assoc current :end line-start))
                current (cond
                          completed completed

                          marker
                          {:eid (:eid marker)
                           :start line-start
                           :line-start line-number
                           :matched? (= target-line line-number)}

                          (and current (= target-line line-number))
                          (assoc current :matched? true)

                          :else current)]
            (.reset line-bytes)
            (cond
              (and current (:end current))
              current

              (and marker (> line-number target-line))
              nil

              :else
              (recur (inc position)
                     (inc position)
                     (inc line-number)
                     current)))

          :else
          (do
            (.write line-bytes value)
            (recur (inc position) line-start line-number current)))))))

(defn find-entity-address-by-line
  "Find the marked entity containing a 1-based journal line number.

  Returns `{:eid eid :entity-address-bytes {:start n :end n}}`, or nil when
  the line is outside any framed entity."
  [journal-file line-number]
  (when-not (pos-int? line-number)
    (throw (ex-info "line-number must be a positive integer"
                    {:line-number line-number})))
  (let [file (io/file journal-file)]
    (when (.exists file)
      (with-open [journal (FileChannel/open
                           (.toPath file)
                           (into-array java.nio.file.OpenOption
                                       [StandardOpenOption/READ]))
                  input (BufferedInputStream.
                         (Channels/newInputStream journal)
                         copy-buffer-size)]
        (when-let [{:keys [eid start end]}
                   (scan-entity-address-by-line input file line-number)]
          {:eid eid
           :entity-address-bytes {:start start :end end}})))))

(defn- page-entry
  [marker start]
  (let [position (get-in marker [:metadata "entity/position"])]
    (when-not (integer? position)
      (throw (ex-info "Cannot page an entity marker without entity/position"
                      {:eid (:eid marker)
                       :metadata (:metadata marker)})))
    {:eid (:eid marker)
     :position position
     :entity-address-bytes {:start start}}))

(defn- after-position?
  [after-position {:keys [position]}]
  (or (nil? after-position)
      (> position after-position)))

(defn- close-page-entry
  [entry end]
  (assoc-in entry [:entity-address-bytes :end] end))

(defn- scan-positioned-entity-address-page
  "Stream a journal and return a page of positioned entity address bytes."
  [input file {:keys [limit after-position]}]
  (let [line-bytes (ByteArrayOutputStream.)]
    (loop [position 0
           line-start 0
           current nil
           entries []]
      (let [value (.read input)]
        (cond
          (neg? value)
          {:entries (cond-> entries
                      current (conj (close-page-entry current (.length file))))
           :has-more? false}

          (= 10 value)
          (let [marker (parse-marker-line-bytes line-bytes)
                entries (cond-> entries
                          (and marker current)
                          (conj (close-page-entry current line-start)))]
            (.reset line-bytes)
            (if marker
              (let [candidate (page-entry marker line-start)]
                (if (after-position? after-position candidate)
                  (if (= limit (count entries))
                    {:entries entries :has-more? true}
                    (recur (inc position)
                           (inc position)
                           candidate
                           entries))
                  (recur (inc position) (inc position) nil entries)))
              (recur (inc position) (inc position) current entries)))

          :else
          (do
            (.write line-bytes value)
            (recur (inc position) line-start current entries)))))))

(defn find-positioned-entity-address-page
  "Find a position-cursor page of entity address bytes without slurping.

  Returns `{:entries [{:eid eid :position n :entity-address-bytes {:start n
  :end n}}] :has-more? boolean}`. The cursor is exclusive: an entity is included
  when its marker metadata position is greater than `after-position`."
  [journal-file {:keys [limit after-position] :as opts}]
  (let [file (io/file journal-file)]
    (cond
      (not (.exists file))
      {:entries [] :has-more? false}

      (not (and (integer? limit) (pos? limit)))
      (throw (ex-info "A positioned entity address page requires a positive integer limit"
                      {:limit limit}))

      (not (or (nil? after-position) (integer? after-position)))
      (throw (ex-info "after-position must be nil or an integer"
                      {:after-position after-position}))

      :else
      (with-open [journal (FileChannel/open
                           (.toPath file)
                           (into-array java.nio.file.OpenOption
                                       [StandardOpenOption/READ]))
                  input (BufferedInputStream.
                         (Channels/newInputStream journal)
                         copy-buffer-size)]
        (scan-positioned-entity-address-page input file opts)))))

(defn- read-entity-storage
  "Read the framed journal text at one entity address as UTF-8 text."
  [journal-file {:keys [start end]}]
  (let [length (- end start)
        content (byte-array length)]
    (with-open [journal (RandomAccessFile. (io/file journal-file) "r")]
      (.seek journal start)
      (.readFully journal content))
    (String. content StandardCharsets/UTF_8)))

(defn- unframe-entity-body
  "Remove the `; __eid:` marker and return only the entity body."
  [framed-entity]
  (let [marker-end (inc (.indexOf ^String framed-entity "\n"))
        without-marker (subs framed-entity marker-end)]
    (str/replace without-marker #"\n+$" "")))

(defn- unframe-entity
  "Remove marker framing and return `{:body string :metadata map}`."
  [framed-entity]
  (let [marker-end (.indexOf ^String framed-entity "\n")
        marker-line (subs framed-entity 0 marker-end)
        marker (parse-marker-line marker-line)
        without-marker (subs framed-entity (inc marker-end))]
    {:body (str/replace without-marker #"\n+$" "")
     :metadata (:metadata marker)}))

(defn read-entity-body
  "Read one entity body from a known entity address."
  [journal-file entity-address-bytes]
  (unframe-entity-body
   (read-entity-storage journal-file entity-address-bytes)))

(defn read-entity
  "Read one framed entity as `{:body string :metadata map}`."
  [journal-file entity-address-bytes]
  (unframe-entity
   (read-entity-storage journal-file entity-address-bytes)))

(defn read-entity-bodies
  "Read entity bodies for an `{eid entity-address-bytes}` map."
  [journal-file eid->entity-address-bytes]
  (into {}
        (map (fn [[eid entity-address-bytes]]
               [eid (read-entity-body journal-file entity-address-bytes)]))
        eid->entity-address-bytes))

(defn read-entities
  "Read entity bodies and marker metadata for an `{eid entity-address-bytes}` map."
  [journal-file eid->entity-address-bytes]
  (into {}
        (map (fn [[eid entity-address-bytes]]
               [eid (read-entity journal-file entity-address-bytes)]))
        eid->entity-address-bytes))

(defn- write-fully!
  "Write a ByteBuffer to a channel, retrying until every byte is accepted."
  [channel buffer]
  (while (.hasRemaining buffer)
    (.write channel buffer)))

(defn- copy-range!
  "Copy one byte range from source to target using a fixed-size buffer."
  [source target start end]
  (.position source start)
  (let [buffer (ByteBuffer/allocate copy-buffer-size)]
    (loop [remaining (- end start)]
      (when (pos? remaining)
        (.clear buffer)
        ;; Limit the buffer to the remaining range length. Without this, the
        ;; final read could copy bytes beyond the requested edit boundary.
        (.limit buffer (int (min remaining copy-buffer-size)))
        (let [read (.read source buffer)]
          (when (neg? read)
            (throw (ex-info "Journal ended while copying a byte range"
                            {:start start :end end :remaining remaining})))
          (.flip buffer)
          (write-fully! target buffer)
          (recur (- remaining read)))))))

(defn- replacement-bytes
  "Encode replacement text for a byte mutation, using empty bytes for deletion."
  [replacement]
  (if (nil? replacement)
    (byte-array 0)
    (.getBytes ^String replacement StandardCharsets/UTF_8)))

(defn- move-replacing!
  "Replace the target path with a prepared temporary file, preferring atomic move."
  [source target]
  (try
    (Files/move source target
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    ;; Babashka does not expose AtomicMoveNotSupportedException (or its
    ;; FileSystemException parent) as a resolvable class. Identify the exact
    ;; subtype and rethrow every other exception.
    (catch Exception cause
      (if (= "java.nio.file.AtomicMoveNotSupportedException"
             (.getName (class cause)))
        (Files/move source target
                    (into-array java.nio.file.CopyOption
                                [StandardCopyOption/REPLACE_EXISTING]))
        (throw cause)))))

(defn- rewrite-mutations!
  "Apply multiple byte mutations with one constant-memory file rewrite.

  Each mutation is `{:start n :end n :replacement string-or-nil}`. Nil deletes the
  range. Ranges refer to the original file and must not overlap."
  [journal-file mutations]
  (let [file (io/file journal-file)
        file-size (.length file)
        mutations (sort-by :start mutations)
        parent (.toPath (.getParentFile (.getAbsoluteFile file)))
        temporary (Files/createTempFile parent
                                        ".hledger-byte-ops-"
                                        ".tmp"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [source (.getChannel (RandomAccessFile. file "r"))
                  target (.getChannel (RandomAccessFile. (.toFile temporary) "rw"))]
        (loop [cursor 0
               remaining mutations]
          (if-let [{:keys [start end replacement] :as mutation} (first remaining)]
            (do
              (when-not (and (integer? start)
                             (integer? end)
                             (<= cursor start end file-size)
                             (or (nil? replacement) (string? replacement)))
                (throw (ex-info "Invalid or overlapping byte mutation"
                                {:cursor cursor
                                 :file-size file-size
                                 :mutation mutation})))
              (copy-range! source target cursor start)
              (write-fully! target (ByteBuffer/wrap (replacement-bytes replacement)))
              (recur end (next remaining)))
            (copy-range! source target cursor file-size))))
      (move-replacing! temporary (.toPath file))
      (finally
        (Files/deleteIfExists temporary)))))

(defn- append-text!
  "Append UTF-8 text with one buffered write and flush."
  [journal-file content]
  (with-open [output (BufferedOutputStream.
                      (FileOutputStream. (io/file journal-file) true)
                      copy-buffer-size)]
    (.write output (.getBytes ^String content StandardCharsets/UTF_8))))

(defn- apply-byte-mutations!
  "Apply all byte mutations with one logical write.

  Pure EOF insertions use one buffered append. Any update or deletion uses one
  streaming rewrite, with EOF insertions included in that same rewrite."
  [journal-file mutations]
  (when (seq mutations)
    (let [file (io/file journal-file)
          file-size (.length file)
          append-only? (every? (fn [{:keys [start end replacement]}]
                                 (and (= file-size start end)
                                      (string? replacement)))
                               mutations)]
      (if append-only?
        (append-text! file (apply str (map :replacement mutations)))
        (rewrite-mutations! file mutations)))))

(defn- frame-entity-body
  "Wrap an entity body with its hidden entity marker and optional trailing blank line."
  ([eid body blank-after?]
   (frame-entity-body eid body {} blank-after?))
  ([eid body metadata blank-after?]
   (str marker-prefix eid
        (when (seq metadata)
          (str marker-meta-separator
               (binding [*print-namespace-maps* false]
                 (pr-str metadata))))
        "\n"
        body "\n"
        (when blank-after? "\n"))))

(defn plan-entity-mutations
  "Prepare byte mutations for desired entity bodies without changing the journal.

  `eid->entity-address-bytes` is usually produced by `find-entity-address-bytes`.
  `entity-mutations` is an ordered sequence of `{:eid eid :body string-or-nil}`.
  A nil body deletes an existing entity and ignores a missing entity."
  [journal-file eid->entity-address-bytes entity-mutations]
  (let [file (io/file journal-file)
        {:keys [mutations new-entities]}
        (reduce
         (fn [changes {:keys [eid body metadata]}]
           (if-let [entity-address-bytes (get eid->entity-address-bytes eid)]
             (let [old-storage (read-entity-storage file entity-address-bytes)
                   old-entity (unframe-entity old-storage)
                   old-body (:body old-entity)
                   metadata (or metadata {})]
               (if (and (= old-body body)
                        (= (:metadata old-entity) metadata))
                 changes
                 (update changes :mutations conj
                         (assoc entity-address-bytes
                                :replacement
                                (when body
                                  (frame-entity-body
                                   eid
                                   body
                                   metadata
                                   (str/ends-with? old-storage "\n\n")))))))
             (if body
               (update changes :new-entities conj
                       (frame-entity-body eid body (or metadata {}) false))
               changes)))
         {:mutations [] :new-entities []}
         entity-mutations)
        deleted-bytes (reduce + 0
                              (map (fn [{:keys [start end replacement]}]
                                     (if (nil? replacement) (- end start) 0))
                                   mutations))
        content-remains? (or (pos? (- (.length file) deleted-bytes))
                             (some (comp seq :replacement) mutations))
        append-text (when (seq new-entities)
                      (str (when content-remains? "\n")
                           (str/join "\n" new-entities)))
        mutations (cond-> mutations
                    append-text (conj {:start (.length file)
                                       :end (.length file)
                                       :replacement append-text}))]
    {:file file :mutations mutations}))

(defn plan-ordered-entity-mutations
  "Prepare a whole-file rewrite from ordered `{:eid eid :body string}` entries.

  This is intentionally semantic-free: callers decide which entity bodies belong
  in the file and in what order."
  [journal-file ordered-entity-bodies]
  (let [file (io/file journal-file)
        replacement (when (seq ordered-entity-bodies)
                      (str/join "\n"
                                (map (fn [{:keys [eid body metadata]}]
                                       (frame-entity-body eid body (or metadata {}) false))
                                     ordered-entity-bodies)))
        current (when (.exists file) (slurp file))]
    {:file file
     :mutations (cond
                  (= current replacement)
                  []

                  (nil? replacement)
                  (if (.exists file)
                    [{:start 0 :end (.length file) :replacement nil}]
                    [])

                  :else
                  [{:start 0 :end (if (.exists file) (.length file) 0)
                    :replacement replacement}])}))

(defn flush-mutations!
  "Publish a mutation plan returned by `plan-entity-mutations`."
  [{:keys [file mutations]}]
  (apply-byte-mutations! file mutations)
  (when (and (.exists file) (zero? (.length file)))
    (Files/deleteIfExists (.toPath file))))
