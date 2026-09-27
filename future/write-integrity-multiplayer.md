# Write Integrity (Multiplayer Optimistic Concurrency)

Design notes and implementation status.

## Rust CLI implementation

The CLI stores each rolling digest in its `event_log.datom_hash` column. The
latest row's digest is the workspace's current hash. On first open after
migration, legacy rows are backfilled in sequence order, preserving their
datom contents and sequence numbers.

`status` returns the current hash as `lastHash`. Every mutation requires that
value: JSON mutations take it in their data object; `delete` and
`import-journals` take `--last-hash`. Each command checks it inside
`BEGIN IMMEDIATE` before making changes. A mismatch returns `hash_conflict`
with the expected and current hashes. Successful mutations return the new
`lastHash`.

## 1. The problem

The event-log is the source of truth and is append-only. Before the workspace
hash guard was added, there was no mechanism to detect or prevent conflicting
concurrent writes. If two players read the same entity state, both
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
3. The client includes that hash as `lastHash` in every write call.
4. Inside the `BEGIN IMMEDIATE` transaction, the server compares
   `lastHash` to the current stored hash.
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

The hash is computed as a chain: each datom has a digest of its contents, then
its `datom_hash` combines the previous row's hash with that content digest.
This avoids re-hashing the full event-log on every write.

```
content_hash = sha256(sequence || eid || attr || value_json || retract)
datom_hash = sha256(prev_hash || content_hash)
```

applied for each datom in sequence order, chaining into the next.

The initial hash for a workspace with no event-log rows is `"0"`.

Because the hash is a pure function of the event-log, it is deterministic and
reproducible: given the full event-log, the hash can be recomputed from
scratch and must match the stored value. This is the basis for a future
integrity audit.

## 4. Where the hash lives

Each `event_log` row stores its chain digest in `datom_hash`, so the datom and
its hash are inserted together. The current workspace hash comes from the row
with the greatest sequence. `current_projection_state` remains separate and
tracks only projection progress.

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

Each datom is `[sequence eid attr value retract?]`. `content_hash` is SHA-256
over the UTF-8 representations of `sequence`, `eid`, `attr`, the exact stored
`value_json`, `retract` (`"1"` or `"0"`), and optional `writer_external_id`.
When present, the writer ID is preceded by a `0x01` marker so NULL and the
empty string remain distinct. `datom_hash` is SHA-256 over the previous row's
lowercase hex hash followed by the lowercase hex `content_hash`. The first
row uses `"0"` as its previous hash.

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
transaction, use the same `value_json` and `writer_external_id` values that
are inserted on that row.

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

Each mutation requires `lastHash` and returns the new hash with its result.
Inside the transaction:

```
1. Read current-hash from the latest event-log row, or use `"0"` if empty.
2. If lastHash != current-hash: throw.
3. Allocate sequence, encode the datom, and compute its content and chain hashes.
4. Insert the datom and its `datom_hash` together in one row write.
5. Use that hash as the previous hash for the next datom in the batch.
6. Return the final hash with the result.
```

The sequence is selected while holding the `BEGIN IMMEDIATE` write lock, so
the hash can be computed before inserting the row without a follow-up update.

### All mutation entry points affected

Every public mutation function passes through `mutate!`:

- `append-datoms!`
- `add-simple-entity!` (all `add-*` commands)
- `add-transaction-like!` (transaction, periodic-transaction, budget)
- `update-entity!`
- `delete-entity!`

All of them require the workspace's `lastHash` precondition for multiplayer
safety.

### What does NOT need a hash

- `init!` — an empty event-log has the initial hash `"0"`.
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
      {:state-hash (latest-event-log-datom-hash conn)})))
```

This is the endpoint a client calls before preparing a write. It should be
cheap: a single row read from `event_log`, no entity folding, no
event-log scan.

The CLI gains a `current-hash` command (or folds the hash into `status`
output).

## 8. The full client flow

```
1. Client: GET current-hash        -> "0"
2. Client reads entity state (fold, projection, or UI).
3. Client prepares datoms for the write.
4. Client: POST add-transaction {lastHash: "0", data: {...}}
5. Server (inside BEGIN IMMEDIATE):
   a. current-hash == "0"? yes
   b. allocate eids, encode datoms, append to event_log
   c. compute and insert each row's datom_hash
   d. commit
6. Server returns {eid: ..., lastHash: "a1b2c3..."}
7. Meanwhile, another client also read "0" and prepared a write.
8. Client 2: POST add-commodity {lastHash: "0", data: {...}}
9. Server (inside BEGIN IMMEDIATE, after client 1 committed):
   a. current-hash == "a1b2c3...", expected "0" -> mismatch
   b. throw {:type :hash-conflict :expected "0" :current "a1b2c3..."}
10. Client 2 re-reads state with hash "a1b2c3...", re-prepares, retries.
```

## 9. Migration of existing workspaces

Existing workspaces have event-log rows without `datom_hash`. The migration
adds that column, backfills the chain hashes in sequence order, and removes the
temporary workspace hash table:

1. Add `event_log.datom_hash` and `event_log.writer_external_id`.
2. Compute each row's hash from the previous row and its contents, including
   the writer ID (NULL for legacy rows).
3. Store the hash on each existing row, preserving its datom values and
   sequence number.

If the event-log is empty, the hash is `"0"`.

This is a one-time cost proportional to the event-log size. It runs inside
the migration transaction.
