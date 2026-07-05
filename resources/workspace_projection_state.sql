-- :name latest-sequence-number :? :1
-- :doc Return the saved projection cursor.
SELECT last_projected_datom_sequence_number
FROM workspace_projection_state
WHERE rowid = 1
LIMIT 1;

-- :name save-latest-sequence-number :!
-- :doc Persist the latest successfully projected datom sequence number.
INSERT OR REPLACE INTO workspace_projection_state
  (rowid, last_projected_datom_sequence_number, projected_at)
VALUES
  (1, :latest-sequence-number, unixepoch());
