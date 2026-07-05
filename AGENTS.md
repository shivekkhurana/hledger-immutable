# Agent Notes

## Journal entity terminology

Use these terms consistently when explaining or changing the projection/byte-op
writer code:

- `entity`: the logical object represented by datoms, such as a commodity,
  price, or future transaction.
- `eid`: the stable id of a logical entity.
- `entity body`: the hledger text stored in the journal for one entity, without
  the hidden `; __eid:` marker line.
- `entity address bytes`: the byte range where an entity currently lives inside
  a journal file, usually represented as `{:start n :end n}`.
- `workspace-directory`: the directory that contains the projected hledger
  journal files for one immutable workspace. A workspace can contain many
  `.journal` files; each entity chooses its target journal filename through its
  folded `:entity/file` attribute.
- `event-log`: the ordered vector of sequenced datoms passed into projection.
  Treat the batch as an event log, not just a generic vector of datoms:
  each datom starts with its event-log sequence number, and those sequence
  numbers must be strictly increasing and newer than the caller's checkpoint.
  Sequenced datoms use `[sequence eid attr value retract?]`; authored datoms
  use `[eid attr value retract?]`.
- `last-projected-datom-sequence-number`: the checkpoint value returned by `project!`;
  it is the highest datom sequence number that has been successfully projected
  into the journal files.
- `entity attributes`: datom attributes folded into one logical entity. Shared
  entity attributes are `:entity/type`, `:entity/file`, and
  `:entity/position`; hledger body attributes remain domain-specific, such as
  `:commodity/name` or `:price/value`.
- `entity position`: the optional sparse integer ordering key stored as
  `:entity/position`. Use gaps of 1000 by default. If a file's positions become
  crowded later, rebuild positions by emitting ordinary `:entity/position`
  datoms for the affected entities.

Projection direction is one-way:

- The immutable event-log is the source of truth. Projected hledger journals are
  a deterministic view of that event-log.
- Do not assume an hledger journal can be converted back into the same event-log
  deterministically. A journal can be imported later by creating a new event-log
  with new ids, but it cannot recover the original datom history, posting ids,
  tag ids, parent moves, retractions, or sequence numbers.
- Child identities such as posting eids and tag eids live in the event-log, not
  in projected hledger text. When a child entity changes, projection should
  identify and rewrite the affected top-level parent entity body rather than
  trying to edit the child directly in the journal.

The `; __eid:` marker is journal storage machinery. It lets the byte-op writer
connect a logical `eid` to the entity body and entity address bytes in a journal
file. Prefer domain terms like `entity`, `entity body`, and `entity address
bytes` over generic terms like `block` or `address` unless discussing the
low-level file format directly.

Architectural split:

- Keep the main flow visible. Avoid hiding write/read orchestration behind a
  catch-all workspace namespace. Current production responsibilities are:
  `schemas.clj` for type registries and validation contracts, `query.clj` for
  folded read-model queries, `mutation.clj` for command-facing write flows,
  `projector.clj` for checkpoint-aware projection orchestration, `core.clj` for
  folding/rendering/parsing logical entities, and `db.*` namespaces for
  persistence.
- `schemas.clj` owns stable entity-type facts: top-level types, child types,
  parent attributes, body attributes, input schemas, position attributes, and
  amendable attributes. Do not redeclare those registries in production code.
- `mutation.clj` owns event-log append orchestration. Batch event-log writes
  should happen inside one existing SQLite `BEGIN IMMEDIATE` transaction; lower
  table helpers should stay transaction-free unless they encode real persistence
  policy.
- `core.clj` owns logical entity work: validating the event log and datoms,
  folding datoms, parsing current entity bodies, and rendering desired entity
  bodies.
- `byte_ops.clj` should stay dumb: finding entity address bytes, reading/wrapping
  entity bodies, planning byte mutations, and flushing mutations to disk.
- `doctor.clj` classifies and explains hledger stderr. Storage lookups such as
  line-to-entity mapping belong in `byte_ops.clj`, not in whole-file scans inside
  `doctor.clj`.

## Projectability and reportability

- `projectable` means the event-log can fold and emit deterministic `.journal`
  files. This is the invariant `hledger-immutable` should protect.
- `reportable` means hledger can parse, finalise, balance, and report on the
  projected journals. Accounting imbalance is a reportability problem, not a
  write-time event-log blocker, unless the user explicitly changes that policy.
- Do not run hledger subprocess checks in the hot write path by default. If a
  projected journal becomes unreportable, prefer append-only correction through
  the write API plus `explain-hledger-error` diagnostics over manual journal
  edits.

## Positioning and ordering

- Automatic positions use `eid * 1000`. Top-level entities use
  `entity/position`; postings use `posting/position`; tags use `tag/position`.
- Mutation-facing reordering is identity anchored. Use `after_eid` and
  `before_eid`; do not reintroduce mutation-side `after_position`.
- `read-journal --after-position` is only a pagination cursor over projected
  top-level `entity/position` values.
- Rebalance crowded positions by emitting ordinary position datoms through the
  event-log. See `docs/position-reordering.md` before changing planner behavior.

## CLI and agent-facing output

- The CLI is agent-first. Keep help and command output deterministic plain text
  or JSON; do not add ANSI color.
- Each command owns its options, examples, and `-h` / `--help` text. Reject
  command-irrelevant flags instead of silently accepting extras.
- Preserve the current flag style: no positional arguments for entity ids or
  data payloads, and render multi-character short aliases with semantic
  placeholders such as `-eid <entity-id>` and `-peid <parent-entity-id>`.
- Add commands should project by default. Use `--no-project` only when the
  caller intentionally wants to append datoms without updating journals.
- `project --upto <sequence>` is a historical snapshot mode. It writes to
  `.projections/workspace-<sequence>`, does not advance
  `last-projected-datom-sequence-number`, and may ignore incomplete trailing
  entities from the prefix. Normal projection stays strict.
- `add-tag` creates a child tag attached to a parent entity via
  `-peid` / `--parent-entity-id`. hledger tag values are optional, so bare tags
  must render without a trailing colon.

## Storage and database

- The workspace database is `immutable.sqlite` inside the workspace-directory.
  Current SQLite defaults are WAL mode, `busy_timeout = 5000`, foreign keys on,
  and `synchronous = NORMAL`.
- Keep SQL in HugSQL resource files under `resources/`; call generated HugSQL
  SQL-vector functions directly. Avoid string-building SQL or pass-through
  helper layers that add no policy.
- The table vocabulary is `entity_ids`, `event_log`, and
  `workspace_projection_state`. `event_log(sequence)` is the ordered source of
  truth; `entity_ids` permanently allocates eids; projection state stores
  `last_projected_datom_sequence_number`.

## Development workflow

- Prefer `bb test` as the full verification loop. Use focused checks such as
  `bb hledger-immutable ...`, command-local help output, `bb build`, and
  `bb ci` when they match the change.
- Keep `future/` notes narrow and temporary. Verify them against current `src/`,
  `resources/`, and `test/` before treating them as truth, and prune stale notes
  once code or docs supersede them.
- For concurrency design, `BEGIN IMMEDIATE` serializes physical SQLite writers
  but does not solve logical conflicts. Prefer fact-level compare-and-set
  semantics for future safe-update work over broad workspace-hash invalidation.
