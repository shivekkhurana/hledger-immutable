(ns hledger-immutable.event-log
  (:require [clojure.java.io :as io]
            [hledger-immutable.schemas :as schemas]
            [clojure.string :as str]))

(defn prepend-sequence
  "Prefix an authored datom with the sequence assigned by the event log."
  [sequence-number datom]
  (into [sequence-number] datom))

(defn- valid-filename?
  "Accept only simple local `.journal` filenames, not paths or other files."
  [filename]
  (and (string? filename)
       (str/ends-with? filename ".journal")
       (= filename (.getName (io/file filename)))))

(defn validate-journal-filename
  "Validate a simple local `.journal` filename."
  [filename]
  (when-not (valid-filename? filename)
    (throw (ex-info "Expected a local .journal filename"
                    {:filename filename}))))

(def ^:private description-attributes
  #{"transaction/description" "periodic/description"})

(defn- invalid-description?
  [attr value]
  (and (contains? description-attributes attr)
       (string? value)
       (str/includes? value ";")))

(defn validate-last-projected-datom-sequence-number
  "Validate that the caller supplied nil or an integer sequence number."
  [last-projected-datom-sequence-number]
  (when-not (or (nil? last-projected-datom-sequence-number)
                (integer? last-projected-datom-sequence-number))
    (throw (ex-info
            "project! requires a nil or integer last projected datom sequence number"
            {:last-projected-datom-sequence-number
             last-projected-datom-sequence-number}))))

(defn validate-event-log
  "Validate incoming event-log datoms against the caller's last projected datom
  sequence number."
  [event-log last-projected-datom-sequence-number]
  (doseq [datom event-log]
    (let [[sequence-number eid attr value retract?] datom]
      (cond
        (not (and (vector? datom) (= 5 (count datom))))
        (throw (ex-info "A datom must be a five-item vector"
                        {:datom datom}))

        (not (integer? sequence-number))
        (throw (ex-info "A datom sequence must be an integer"
                        {:datom datom}))

        (nil? eid)
        (throw (ex-info "A datom eid cannot be nil"
                        {:datom datom}))

        (not (string? attr))
        (throw (ex-info "A datom attribute must be a string"
                        {:datom datom}))

        (and (= "entity/file" attr) (not (valid-filename? value)))
        (throw (ex-info "An entity/file value must be a local .journal filename"
                        {:datom datom}))

        (and (= "entity/type" attr)
             (not (schemas/supported-entity-type? value)))
        (throw (ex-info "entity/type datom values must be supported entity type strings"
                        {:datom datom}))

        (invalid-description? attr value)
        (throw (ex-info "Descriptions cannot contain semicolons"
                        {:datom datom}))

        (not (schemas/valid-datom-value? attr value))
        (throw (ex-info "Datom values must match their attribute schema"
                        {:datom datom}))

        (not (instance? Boolean retract?))
        (throw (ex-info "A datom retraction flag must be boolean"
                        {:datom datom})))))

  (let [event-log-sequence-numbers (mapv first event-log)]
    (cond
      (not (or (< (count event-log-sequence-numbers) 2)
               (apply < event-log-sequence-numbers)))
      (throw (ex-info "Event-log sequence numbers must be strictly increasing"
                      {:event-log-sequence-numbers event-log-sequence-numbers}))

      (and (seq event-log-sequence-numbers)
           (some? last-projected-datom-sequence-number)
           (<= (first event-log-sequence-numbers)
               last-projected-datom-sequence-number))
      (throw (ex-info "project! received an already-processed datom"
                      {:last-projected-datom-sequence-number
                       last-projected-datom-sequence-number
                       :event-log-sequence-number
                       (first event-log-sequence-numbers)})))))
