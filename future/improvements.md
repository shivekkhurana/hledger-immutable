# Future Improvements

This is the live index for open design notes in this repo.

Older versions of this file were a larger scratchpad. As work landed, those
sections were either implemented and removed or split into narrower notes. Keep
this file as the entry point, and keep detailed design in focused files under
`future/`.

## Active notes

### `future/write-integrity-multiplayer.md`

Workspace-level optimistic concurrency using a rolling event-log hash.

This is broader than safe updates. It rejects a write if anything in the
workspace changed since the caller read the state hash.

### `future/project-upto-immutable-destinations.md`

Historical projection destinations under `.projections/workspace-<sequence>`.

The current behavior treats those destinations as immutable snapshots. A future
workflow may add explicit replacement/refusal semantics or snapshot manifests.

## Maintenance rule

`future/` is planning state, not archival documentation. When a feature ships,
either delete its note or replace it with a short pointer to the real code,
tests, docs, or CLI surface.
