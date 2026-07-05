# Write Integrity (Multiplayer Optimistic Concurrency)

Notes to think through.

## 1. The problem

The event-log is the source of truth and is append-only. There is currently no
mechanism to detect or prevent conflicting concurrent writes. Any caller can
append datoms at any time. If two players read the same entity state, both
decide to amend it, and both append their datom batches, the second write
silently clobbers the first's intent. The event-log records both batches, but
neither caller knew the other was acting.

`BEGIN IMMEDIATE` serializes the physical writes (the database will not
corrupt), but it does not provide logical write integrity. Each transaction
sees a consistent snapshot and appends independently; there is no compare-and-
swap gate. This is the gap that makes the system unsafe for multiplayer use.

## 2. The core idea

Optimistic concurrency control via a workspace state hash:

1. The workspace has a single state hash, initially `"0"` for a fresh
   workspace.
2. Before writing, a client requests the current hash.
3. The client includes that hash as `expected-hash` in the write call.
4. Inside the `BEGIN IMMEDIATE` transaction, the server compares
   `expected-hash` to the current stored hash.
5. If they match: append the datoms, compute the new hash from the appended
   datoms, store it, and return the new hash.
6. If they do not match: reject the transaction, return the current hash so
   the client can re-read state and retry.

This is a classic compare-and-swap (CAS) loop. It guarantees that a write only
succeeds if the caller's view of the workspace was current at the moment of
the write.

## 3. What the hash represents

The hash is a content fingerprint of the entire event-log. It changes every
time datoms are appended. Two workspaces with identical event-logs must
produce identical hashes.

The hash is computed as a rolling digest: each time a batch of datoms is
appended, the new hash is derived from the previous hash and the newly
appended datom rows. This avoids re-hashing the full event-log on every write.

```
new_hash = sha256(prev_hash || sequence || eid || attr || value_json || retract)
```

applied for each datom in sequence order, chaining into the next.

The initial hash for a workspace with no event-log rows is `"0"`.

Because the hash is a pure function of the event-log, it is deterministic and
reproducible: given the full event-log, the hash can be recomputed from
scratch and must match the stored value. This is the basis for a future
integrity audit.

## 4. Where the hash lives

A new singleton table `workspace_state`:

```sql
CREATE TABLE workspace_state (
    state_hash TEXT NOT NULL,
    updated_at INTEGER NOT NULL
                     DEFAULT (CAST(strftime('%s', 'now') AS INTEGER))
);
```

This is a single-row table like `workspace_projection_state`. It stores the
current content hash of the event-log. It is separate from
`workspace_projection_state` because the hash is about write integrity, not
projection progress. The two evolve independently: a write changes the state
hash immediately, while the last-projected-datom-sequence-number only changes
when projection runs.

Following the AGENTS.md namespace conventions:

- Physical table: `workspace_state`
- Namespace: `hledger-immutable.db.table.workspace-state`
- SQL resource: `resources/workspace_state.sql`
- Migration: a new numbered migration in `resources/migrations.sql`

The table namespace owns `current-state-hash` (query) and
`save-state-hash!` (write) and nothing else. It must not own CAS logic,
hash computation, or workspace orchestration.

## 5. Hash computation

A pure function, not a database concern. It belongs in a new
`hash.clj` namespace (or `event_log.clj`, since it is a property of the
event-log).

Signature:

```clojure
(defn compute-state-hash
  "Given a previous hash and a vector of sequenced datoms in sequence order,
  return the new rolling hash."
  [prev-hash sequenced-datoms])
```

Each datom is `[sequence eid attr value retract?]`. The hash input for each
datom is the UTF-8 byte concatenation of:

- `prev-hash` (hex string)
- `sequence` (integer, as string)
- `eid` (integer, as string)
- `attr` (string)
- `value_json` (the JSON-encoded string, exactly as stored)
- `retract` (`"1"` or `"0"`)

The output is a lowercase hex SHA-256 digest.

A full recomputation function takes the entire event-log and reduces from
`"0"`:

```clojure
(defn compute-state-hash-from-log
  "Recompute the state hash from scratch over the full event-log."
  [sequenced-datoms]
  (reduce compute-state-hash "0" (partition-by-sequence-or-simply-each-datom))
```

This is used during migration of existing workspaces and for integrity audits.

### value_json consistency

The hash must use the exact `value_json` string stored in the database, not a
re-serialized version. JSON key ordering or whitespace differences would
produce a different hash. The `event_log` table namespace already stores
`value_json` via `(json/generate-string value)`; the hash computation must
feed that same string. When computing the rolling hash inside the append
transaction, use the same `value_json` strings that are being inserted, not
the caller's raw values.

## 6. Integration with mutation paths

All writes flow through `workspace/mutate!`:

```clojure
(defn- mutate!
  [workspace project? f]
  (let [result
        (connection/with-connection
          workspace
          (fn [conn]
            (migrations/ensure! conn)
            (connection/with-transaction conn #(f conn))))]
    (project-after-mutation workspace project? result)))
```

The CAS check and hash update go inside the `f` that runs within
`BEGIN IMMEDIATE`. Because `BEGIN IMMEDIATE` acquires a write lock, only one
mutation transaction runs at a time. The CAS check is therefore race-free:
read the current hash, compare, and if it matches, the write lock guarantees
no other writer can interleave before the hash is updated and the transaction
commits.

The `f` passed to `mutate!` currently returns a result map. The CAS wrapper
adds `expected-hash` validation and `new-hash` to the result:

```
1. Read current-hash from workspace_state.
2. If expected-hash was supplied and expected-hash != current-hash: throw.
3. Run f(conn) — eid allocation, encoding, datom append, etc.
4. Read the just-appended datom rows back (by sequence > prev-latest-sequence)
   to get their exact value_json strings.
5. Compute new-hash = rolling-hash(current-hash, appended-rows).
6. Save new-hash to workspace_state.
7. Return result with :new-hash.
```

Step 4 is important: we must hash the rows as they were actually stored, not
as the caller described them. The `sequence` values are assigned by
SQLite autoincrement and are not known until after the insert.

### All mutation entry points affected

Every public mutation function passes through `mutate!`:

- `append-datoms!`
- `add-simple-entity!` (all `add-*` commands)
- `add-transaction-like!` (transaction, periodic-transaction, budget)
- `update-entity!`
- `delete-entity!`

All of them gain an `:expected-hash` option. The option is optional in the
first cut (see open questions), but the intent is to make it mandatory for
multiplayer safety.

### What does NOT need a hash

- `init!` — creates the workspace and seeds the initial hash as `"0"`.
- `project!` — read-only with respect to the event-log. Does not append
  datoms. Does not change the state hash.
- `status` — read-only. Can optionally return the current hash.

## 7. New read endpoint: current hash

A lightweight read that returns the current state hash without folding any
entities:

```clojure
(defn current-hash
  "Return the current workspace state hash."
  [workspace]
  (connection/with-connection
    workspace
    (fn [conn]
      (migrations/ensure! conn)
      {:state-hash (workspace-state/current-state-hash conn)})))
```

This is the endpoint a client calls before preparing a write. It should be
cheap: a single row read from `workspace_state`, no entity folding, no
event-log scan.

The CLI gains a `current-hash` command (or folds the hash into `status`
output).

## 8. The full client flow

```
1. Client: GET current-hash        -> "0"
2. Client reads entity state (fold, projection, or UI).
3. Client prepares datoms for the write.
4. Client: POST add-transaction {expected-hash: "0", data: {...}}
5. Server (inside BEGIN IMMEDIATE):
   a. current-hash == "0"? yes
   b. allocate eids, encode datoms, append to event_log
   c. compute new-hash from appended rows
   d. save new-hash = "a1b2c3..."
   e. commit
6. Server returns {eid: ..., new-hash: "a1b2c3..."}
7. Meanwhile, another client also read "0" and prepared a write.
8. Client 2: POST add-commodity {expected-hash: "0", data: {...}}
9. Server (inside BEGIN IMMEDIATE, after client 1 committed):
   a. current-hash == "a1b2c3...", expected "0" -> mismatch
   b. throw {:type :hash-mismatch :expected "0" :current "a1b2c3..."}
10. Client 2 re-reads state with hash "a1b2c3...", re-prepares, retries.
```

## 9. Migration of existing workspaces

Existing workspaces have event-log rows but no `workspace_state` table. The
migration must:

1. Create the `workspace_state` table.
2. Compute the hash from the full existing event-log (ordered by sequence).
3. Insert the computed hash as the single row.

If the event-log is empty, the hash is `"0"`.

This is a one-time cost proportional to the event-log size. It runs inside
the migration transaction.

