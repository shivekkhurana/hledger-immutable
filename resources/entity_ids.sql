-- :name allocate-entity-id :? :1
-- :doc Allocate and return a new stable entity id.
INSERT INTO entity_ids DEFAULT VALUES
RETURNING eid;

-- :name eid-exists? :? :1
-- :doc Return a row when the entity id has been allocated.
SELECT eid
FROM entity_ids
WHERE eid = :eid;
