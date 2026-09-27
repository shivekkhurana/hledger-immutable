# hledger-immutable

`hledger-immutable` is a Rust JSON accounting CLI over an append-only SQLite
event log. It calculates hledger-style accounts, balances, balance sheets,
income statements, registers, and commodity lists directly from datoms. It does
not invoke hledger. Reports operate on the event log; the optional importer
reads source journals without modifying them.

The existing event log remains the source of truth. Rust opens
`immutable.sqlite` in the workspace and adopts the legacy `entity_ids` and
`event_log` schema without rewriting existing rows. `entity/file` and
`include/file` remain logical source-group datoms: `--file` selects a group and
follows its include relationships. They are not filesystem paths in this CLI.

## Run from source during development

`cargo run` launches the binary defined by `src/main.rs` and recompiles it when
source files change. Cargo builds incrementally, so use this while iterating
instead of manually rebuilding the release executable:

```sh
cargo run -- --workspace ./books status
cargo run -- --help
```

## Build the executable

Install a current stable Rust toolchain, then build the optimized executable
from the repository root:

```sh
cargo build --release
```

The binary is `target/release/hledger-immutable` on macOS and Linux, or
`target/release/hledger-immutable.exe` on Windows. The examples below use
the macOS/Linux path; substitute the `.exe` path on Windows.

## Run commands

Report and write commands print JSON to stdout, including errors. Help is
terminal-friendly text: running the binary without a subcommand or with
`--help` prints the command table and descriptions. A command-local `--help`
shows that command's options and documentation. The workspace directory
defaults to the current directory; pass `--workspace` to select another one.

```sh
./target/release/hledger-immutable --workspace ./books
./target/release/hledger-immutable add-transaction --help
./target/release/hledger-immutable --workspace ./books status
./target/release/hledger-immutable --workspace ./books accounts --file main.journal
./target/release/hledger-immutable --workspace ./books balance --file main.journal -X USD --monthly -p 2025
./target/release/hledger-immutable --workspace ./books add-transaction --writer-external-id ui-user-42 --data '{"lastHash":"HASH_FROM_STATUS","file":"personal.journal","date":"2026-09-27","description":"Lunch","postings":[{"account":"Expenses:Food","amount":"INR 500"},{"account":"Assets:Cash"}]}'
./target/release/hledger-immutable --workspace ./books update --data '{"eid":42,"attr":"posting/amount","lastHash":"HASH_FROM_STATUS","value":"INR 550"}'
./target/release/hledger-immutable --workspace ./books delete --eid 42 --last-hash HASH_FROM_STATUS
```

Read commands are `accounts`, `commodities`, `print`, `balance` (`bal`),
`balancesheet` (`bs`), `incomestatement` (`is`), and `register` (`reg`). Reports
support native multi-commodity amounts, dated price conversion (`-X`, `-V`),
posting cost annotations (`-B`), date ranges, period grouping, historical
balances, account include/exclude queries, depth, and tag filters (`--tag
NAME` or `--tag NAME=VALUE`). `--file`/`-F` follows source-group includes.
Custom reports can be built on the JSON results.

Write commands include transactions, accounts, commodities, prices, include
relationships, safe single-attribute updates, and logical entity deletion.
`status` returns the current workspace `lastHash`. Every mutation requires the
hash returned by the latest read: add and update commands take `lastHash` in
their JSON data, while `delete` and `import-journals` take `--last-hash`. If any
event-log write happened after the hash was read, the command returns a
structured `hash_conflict` and appends no datoms. Successful mutations return
the new `lastHash`. Hash checks and appends run in the same SQLite transaction.
The hash is initialized from the existing event log when an older workspace is
opened. `delete` appends retractions for the entity and owned postings and tags.
Writes do not rewrite event-log history or block writes because a transaction
is unbalanced.

Every mutation also accepts optional `--writer-external-id ID`. The value is
stored on every datom produced by that command, including retractions, and is
included in the datom hash chain for audit attribution.

## Import existing journal files

To import a folder of existing journals, point `import-journals` at the source
folder. It reads `.journal` files recursively, stores each file's relative path
as its logical source group, and imports `include` relationships. The source
files are opened only for reading and are never rewritten. The import is
all-or-nothing and is allowed only when the workspace event log is empty.

```sh
./target/release/hledger-immutable --workspace ./books import-journals --source ~/Wip/hledger/journals --last-hash HASH_FROM_STATUS
```

The importer handles transactions and postings, account and commodity
declarations, prices, includes, aliases, decimal marks, default commodities,
payees, and tag declarations. It preserves transaction/posting status, codes,
and tags. Unsupported journal directives or malformed input stop the import
before any datoms are written. Inspect the JSON result for the number of source
files, entities, transactions, datoms, and the resulting sequence number.

## Build and test

Build and run the complete Rust unit and CLI integration suite with:

```sh
cargo test
```

Tests cover datom folding, amount parsing and inference, account/date/tag/group
filters, period calculations, multi-currency valuation, command JSON output,
SQLite write atomicity, and adopting a populated legacy event-log database.
