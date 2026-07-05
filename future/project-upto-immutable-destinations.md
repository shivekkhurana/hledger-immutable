# Project Upto Immutable Destinations

`project --upto <sequence>` writes into `.projections/workspace-<sequence>`.
Those destinations are intentionally treated as immutable named snapshots, not
scratch directories that are cleaned and rebuilt on every run.

This means a destination that already contains extra files can keep those files.
That behavior is acceptable for now because the sequence-numbered directory is a
projection artifact the caller should not mutate. If a future workflow needs
rebuildable historical destinations, add an explicit command or flag with clear
semantics instead of silently deleting existing files.

Possible future direction:

- Refuse to project into an existing non-empty `.projections/workspace-<sequence>`
  unless its contents already match the requested snapshot.
- Add a separate `--replace-destination` flag for destructive rebuilds.
- Store a small manifest in each snapshot directory with the projected sequence
  number, source workspace path, and generation time.
