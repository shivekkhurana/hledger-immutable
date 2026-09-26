# Agent Notes

## Source of truth and terminology

- The ordered SQLite `event_log` is the source of truth. Preserve every
  existing datom and sequence number.
- An `entity` is the logical object represented by datoms; its stable identity
  is its `eid`. Entity attributes are the current folded facts for that eid.
- Sequenced datoms are `[sequence, eid, attr, value, retract]`. Fold them in
  strictly increasing sequence order; a retraction removes only the matching
  current value.
- `entity/file` and `include/file` are legacy logical source-group attributes.
  Never open them as paths or generate journal text. `--file` filters entities
  by source group and follows include relationships.
- Child posting and tag identities remain separate eids in the event log.
- Accounting imbalance is a reporting concern. Do not block append-only writes
  or event-log folding merely because a transaction does not balance.

## Rust architecture

- `src/main.rs` defines the Clap command surface and JSON input/output.
- `src/store.rs` owns SQLx persistence, migrations, and atomic append
  orchestration. Keep SQLite writes inside `BEGIN IMMEDIATE` transactions and
  use bound SQL parameters.
- `src/ledger.rs` owns event-log folding, amount and price calculations,
  filters, and JSON report construction.
- `migrations/` owns SQLx migrations. Existing databases must be adopted
  without rewriting their `entity_ids` or `event_log` rows.
- Keep all command output, errors, and report amounts deterministic JSON. Do
  not add ANSI styling or hledger subprocess calls.
- Keep each command's flags relevant to that command and reject unsupported
  options instead of silently accepting them.

## Tests and build

- Add unit coverage for pure ledger behavior and CLI integration coverage for
  command behavior, JSON contracts, and SQLite compatibility.
- Run `cargo fmt --check` and `cargo test` after Rust changes.
- Build the executable with `cargo build --release`. User-facing run examples
  should invoke `target/release/hledger-immutable` directly.
