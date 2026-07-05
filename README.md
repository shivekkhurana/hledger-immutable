# hledger-immutable

`hledger-immutable` is an append-only event-log layer for hledger journals.
Instead of editing `.journal` text as the source of truth, callers append datoms
to a workspace database. The project then deterministically projects those
datoms into ordinary hledger journal files.

The goal is a CLI that agents and applications can call safely: writes are
structured, ids are stable, journal text is reproducible, and projected files
remain readable by normal hledger tooling.

## Model

A workspace is a directory containing:

- `immutable.sqlite`: the append-only event-log database.
- one or more projected `.journal` files.
- `.projections/workspace-<sequence>` directories for historical snapshots made
  with `project --upto`.

The event-log is the source of truth. Projected journals are a deterministic
view of that event-log, with hidden `; __eid:` marker lines that connect
projected text back to stable entity ids.

Core terms:

- `eid`: stable id for a logical entity.
- `entity`: a commodity, account, transaction, posting, tag, directive, or other
  hledger object represented by datoms.
- `entity body`: the hledger text for an entity, excluding the hidden marker.
- `last-projected-datom-sequence-number`: the highest event-log sequence number
  successfully projected into journal files.

## Install

This project runs on [Babashka](https://babashka.org/).

```sh
bb hledger-immutable
```

For local development, the repo also has a pass-through launcher:

```sh
./hledger-immutable
```

Build tasks:

```sh
bb build          # write the local development launcher
bb build:prod     # build standalone macOS and Linux binaries in target/
bb test           # run the test suite
bb ci             # build production binaries and run tests
```

## Quick Start

Create a workspace:

```sh
bb hledger-immutable init -w ./books
```

Add an account declaration:

```sh
bb hledger-immutable add-account \
  -w ./books \
  -d '{"file":"accounts.journal","name":"assets:bank"}'
```

Add a transaction:

```sh
bb hledger-immutable add-transaction \
  -w ./books \
  -d '{"file":"2026.journal","date":"2026-06-24","description":"Lunch","postings":[{"account":"expenses:food","amount":"INR 500"},{"account":"liabilities:card"}]}'
```

Read projected journal entities as JSON:

```sh
bb hledger-immutable read-journal -w ./books -j 2026.journal
```

Check projection status:

```sh
bb hledger-immutable status -w ./books
```

## CLI API

Run command-local help for examples and exact flags:

```sh
bb hledger-immutable <command> -h
```

All commands use named flags. Entity ids and data payloads are not positional.

### Workspace

```sh
hledger-immutable init -w <workspace>
hledger-immutable status -w <workspace>
```

`init` creates or opens the workspace database and applies migrations. `status`
returns the latest event-log sequence, the
`last-projected-datom-sequence-number`, and the pending datom count.

### Add Top-Level Entities

```sh
hledger-immutable add-commodity -w <workspace> -d <json> [--no-project]
hledger-immutable add-price -w <workspace> -d <json> [--no-project]
hledger-immutable add-account -w <workspace> -d <json> [--no-project]
hledger-immutable add-transaction -w <workspace> -d <json> [--no-project]
hledger-immutable add-budget -w <workspace> -d <json> [--no-project]
hledger-immutable add-periodic-transaction -w <workspace> -d <json> [--no-project]
hledger-immutable add-include -w <workspace> -d <json> [--no-project]
hledger-immutable add-alias -w <workspace> -d <json> [--no-project]
hledger-immutable add-decimal-mark -w <workspace> -d <json> [--no-project]
hledger-immutable add-default-commodity -w <workspace> -d <json> [--no-project]
hledger-immutable add-payee -w <workspace> -d <json> [--no-project]
hledger-immutable add-tag-declaration -w <workspace> -d <json> [--no-project]
```

Every add command accepts business-shaped JSON with at least a `file` key, then
the fields for that entity type:

| Command | Required JSON keys |
| --- | --- |
| `add-commodity` | `file`, `name` |
| `add-price` | `file`, `date`, `commodity`, `value` |
| `add-account` | `file`, `name` |
| `add-transaction` | `file`, `date`, `description`, `postings` |
| `add-periodic-transaction` | `file`, `period`, `postings` |
| `add-budget` | same shape as `add-periodic-transaction` |
| `add-include` | `file`, `path` |
| `add-alias` | `file`, `old`, `new` |
| `add-decimal-mark` | `file`, `value` |
| `add-default-commodity` | `file`, `value` |
| `add-payee` | `file`, `name` |
| `add-tag-declaration` | `file`, `name` |

Optional ordering anchors on add payloads:

```json
{"after_eid": 7}
```

```json
{"before_eid": 7}
```

### Transactions and Postings

Transaction payloads can include status, code, top-level tags, postings, and
posting tags:

```json
{
  "file": "2026.journal",
  "date": "2026-06-24",
  "status": "*",
  "code": "T-001",
  "description": "Lunch",
  "tags": [{"name": "project", "value": "ops"}],
  "postings": [
    {
      "account": "expenses:food",
      "amount": "INR 500",
      "tags": [{"name": "receipt"}]
    },
    {"account": "liabilities:card"}
  ]
}
```

Posting `amount`, posting `status`, transaction `status`, transaction `code`,
and tag `value` are optional.

### Child Tags

```sh
hledger-immutable add-tag \
  -w <workspace> \
  -peid <parent-entity-id> \
  --key <key> \
  [--value <value>] \
  [--no-project]
```

Tags attach to an existing top-level entity or posting. Values are optional, so
bare tags render as `reviewed`, not `reviewed:`.

### Update and Delete

```sh
hledger-immutable update-entity -w <workspace> -eid <entity-id> -d <json> [--no-project]
hledger-immutable delete-entity -w <workspace> -eid <entity-id> [--no-project]
```

Patch shape:

```json
{"set": {"commodity/name": "USD"}}
```

```json
{"unset": ["transaction/code"]}
```

Reorder an entity within its ordering scope:

```json
{"after_eid": 7}
```

```json
{"before_eid": 7}
```

`delete-entity` retracts the entity and its descendants.

### Raw Datoms

```sh
hledger-immutable append-datoms -w <workspace> -d <json> [--no-project]
```

The payload is a JSON array of authored datoms:

```json
[[1, "commodity/name", "USD", false]]
```

Authored datoms use:

```text
[eid, attr, value, retract?]
```

Projection receives sequenced datoms:

```text
[sequence, eid, attr, value, retract?]
```

### Projection

```sh
hledger-immutable project -w <workspace>
hledger-immutable project -w <workspace> --upto <sequence>
```

Without `--upto`, projection writes pending datoms to the workspace journals and
advances `last-projected-datom-sequence-number`.

With `--upto`, projection writes a historical snapshot to
`.projections/workspace-<sequence>` and does not advance projection state.

Most mutation commands project by default. Use `--no-project` when you want to
append datoms first and project later.

### Read Projected Journals

```sh
hledger-immutable read-journal \
  -w <workspace> \
  -j <journal> \
  [--limit <count>] \
  [--after-position <position>]
```

The JSON response contains:

```json
{
  "journal": "main.journal",
  "limit": 100,
  "after_position": null,
  "top_level_entity_count": 10,
  "has_more": false,
  "last_entity_position": 10000,
  "entities": []
}
```

Paging uses top-level `entity/position`, not offset.

### Explain hledger Errors

```sh
hledger-immutable explain-hledger-error \
  -w <workspace> \
  -d '{"stderr":"hledger: Error: ./books/main.journal:12-14:\n..."}'
```

This command maps hledger stderr back to projected entity information when the
error points at a framed entity. The first supported diagnostic family is
unbalanced transactions.

## Ordering

New entities get sparse automatic positions based on `eid * 1000`. Top-level
entities use `entity/position`; postings use `posting/position`; tags use
`tag/position`.

Mutation APIs use identity anchors:

```json
{"after_eid": 42}
```

```json
{"before_eid": 42}
```

When a local gap becomes crowded, the planner emits ordinary position datoms to
rebalance affected entities. See [`docs/position-reordering.md`](docs/position-reordering.md)
for the implementation details and churn notes.

## Projectability vs Reportability

`hledger-immutable` protects projectability: the event-log can fold and emit
deterministic journal files.

It does not block writes just because hledger would later reject the accounting
content. For example, an unbalanced transaction can still be projected, but
hledger reports may fail until a later append-only correction fixes the entity.
Use `explain-hledger-error` to map hledger failures back to entities.

## Development

Important paths:

- `src/hledger_immutable/cli.clj`: command definitions, help text, parsing, and
  dispatch.
- `src/hledger_immutable/schemas.clj`: entity types, attributes, input schemas,
  and validation contracts.
- `src/hledger_immutable/mutation.clj`: command-facing write flows.
- `src/hledger_immutable/projector.clj`: projection orchestration and status.
- `src/hledger_immutable/projection.clj`: projection engine and read-journal
  page API.
- `src/hledger_immutable/byte_ops.clj`: byte-level projected-journal storage
  operations.
- `resources/*.sql`: HugSQL SQL resources.
- `test/hledger_immutable/*_test.clj`: regression tests.

Run tests:

```sh
bb test
```

Run command help checks:

```sh
bb hledger-immutable
bb hledger-immutable add-transaction -h
bb hledger-immutable read-journal -h
```
