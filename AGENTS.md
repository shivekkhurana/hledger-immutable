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

- `core.clj` owns logical entity work: validating the event log and datoms,
  folding datoms, parsing current entity bodies, and rendering desired entity
  bodies.
- `byte_ops.clj` should stay dumb: finding entity address bytes, reading/wrapping
  entity bodies, planning byte mutations, and flushing mutations to disk.
