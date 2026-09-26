-- Keep the legacy event-log table layout byte-for-byte compatible. Existing
-- workspaces already have these tables; IF NOT EXISTS lets SQLx adopt them.
CREATE TABLE IF NOT EXISTS entity_ids (
    eid        INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s', 'now') AS INTEGER))
);

CREATE TABLE IF NOT EXISTS event_log (
    sequence   INTEGER PRIMARY KEY AUTOINCREMENT,
    eid        INTEGER NOT NULL,
    attr       TEXT NOT NULL,
    value_json TEXT NOT NULL,
    retract    INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL DEFAULT (CAST(strftime('%s', 'now') AS INTEGER)),
    FOREIGN KEY (eid) REFERENCES entity_ids(eid)
);

CREATE INDEX IF NOT EXISTS event_log_eid_idx ON event_log (eid);
CREATE INDEX IF NOT EXISTS event_log_attr_value_eid_idx
    ON event_log (attr, value_json, eid);
