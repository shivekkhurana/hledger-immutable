-- :name event-log-for-entity :?
SELECT sequence, eid, attr, value_json, retract
FROM event_log
WHERE eid = :eid
ORDER BY sequence;

-- :name event-log-eids-for-attribute-value :?
SELECT DISTINCT eid
FROM event_log
WHERE attr = :attr
  AND value_json = :value-json
ORDER BY eid;

-- :name event-log-eids-for-attribute :?
SELECT DISTINCT eid
FROM event_log
WHERE attr = :attr
ORDER BY eid;

-- :name event-log-after :?
SELECT sequence, eid, attr, value_json, retract
FROM event_log
WHERE sequence > :last-projected-datom-sequence-number
ORDER BY sequence;

-- :name event-log-through :?
SELECT sequence, eid, attr, value_json, retract
FROM event_log
WHERE sequence > :last-projected-datom-sequence-number
  AND sequence <= :upto
ORDER BY sequence;

-- :name insert-datoms :!
INSERT INTO event_log (eid, attr, value_json, retract)
VALUES :tuple*:datoms;

-- :name latest-event-log-sequence :?
SELECT max(sequence) AS sequence
FROM event_log;

-- :name pending-event-log-count :?
SELECT count(*) AS count
FROM event_log
WHERE sequence > :last-projected-datom-sequence-number;
