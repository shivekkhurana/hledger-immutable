CREATE TABLE IF NOT EXISTS workspace_state (
    id         INTEGER PRIMARY KEY CHECK (id = 1),
    state_hash TEXT NOT NULL
);
