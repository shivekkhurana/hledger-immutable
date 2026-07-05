-- :name create-migrations-table :!
CREATE TABLE IF NOT EXISTS migrations (
    id         TEXT PRIMARY KEY NOT NULL,
    applied_at INTEGER NOT NULL
                       DEFAULT (CAST(strftime('%s', 'now') AS INTEGER))
);

-- :name applied-migration-ids :?
SELECT id
FROM migrations
ORDER BY id;

-- :name record-migration :!
INSERT INTO migrations (id)
VALUES (:id);

-- :name migration-001-create-workspace-projection-state :!
-- :doc Creates the singleton projection checkpoint table with Unix epoch seconds for projected_at.
CREATE TABLE workspace_projection_state (
    last_projected_datom_sequence_number INTEGER NOT NULL,
    projected_at                         INTEGER NOT NULL
                                         DEFAULT (CAST(strftime('%s', 'now') AS INTEGER))
);

-- :name migration-002-create-entity-ids :!
-- :doc Creates the permanent eid allocator and foreign-key anchor with Unix epoch seconds for created_at.
CREATE TABLE entity_ids (
    eid        INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL
                       DEFAULT (CAST(strftime('%s', 'now') AS INTEGER))
);

-- :name migration-003-create-event-log :!
-- :doc Creates the append-only event-log with Unix epoch seconds for created_at and a foreign key to allocated eids.
CREATE TABLE event_log (
    sequence   INTEGER PRIMARY KEY AUTOINCREMENT,
    eid        INTEGER NOT NULL,
    attr       TEXT NOT NULL,
    value_json TEXT NOT NULL,
    retract    INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
                       DEFAULT (CAST(strftime('%s', 'now') AS INTEGER)),
    FOREIGN KEY (eid) REFERENCES entity_ids(eid)
);

-- :name migration-004-index-event-log-by-eid :!
-- :doc Speeds up loading one entity's ordered event-log history for amend and delete operations.
CREATE INDEX event_log_eid_idx ON event_log (eid);

-- :name migration-005-index-event-log-by-attribute-value :!
-- :doc Speeds up finding entities by attribute/value for position collision checks and descendant lookups.
CREATE INDEX event_log_attr_value_eid_idx ON event_log (attr, value_json, eid);
