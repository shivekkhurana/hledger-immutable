-- Rebuildable current-state read model. event_log remains the source of truth.
CREATE TABLE IF NOT EXISTS current_entities (
    eid             INTEGER PRIMARY KEY,
    first_sequence   INTEGER NOT NULL,
    attributes_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS current_projection_state (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    last_sequence INTEGER NOT NULL
);
