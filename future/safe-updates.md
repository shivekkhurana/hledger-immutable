# Safe Updates

Notes to think through.

## 1. The problem

`BEGIN IMMEDIATE` gives the workspace one physical writer at a time, so the
database and event-log stay ordered. It does not, by itself, decide whether the
second writer's intent is still valid.

Example:

```text
2026-06-27 narration
  acc1    USD 100
  acc2    USD -80
  acc3
```

Two players read the same transaction:

- Player 1 changes the `acc2` posting amount from `USD -80` to `USD -90`.
- Player 2 changes the same posting account from `acc2` to `acc6`.

If those are independent patch-style edits, both may be valid. The event-log
can serialize them and the projected entity body can become:

```text
2026-06-27 narration
  acc1    USD 100
  acc6    USD -90
  acc3
```

But if both players edit the same fact, such as the posting amount, the second
write should not silently overwrite or ignore the first. It should be rejected
as a logical conflict.

## 2. The core idea

Add a safe single-attribute update command that behaves like compare-and-set
for one entity attribute.

The caller supplies:

- `eid`: the entity being amended.
- `attr`: the entity attribute being amended.
- `expect`: the previous value the caller saw.
- `value`: the new value the caller wants to assert.

The update succeeds only if the currently folded entity still has exactly the
expected value for that attribute.

This is fact-level optimistic concurrency. It is narrower than a workspace
state hash: unrelated changes elsewhere in the workspace do not block the
write.

## 3. Command shape

Possible CLI shape:

```sh
safe-update-entity \
  --workspace <workspace-directory> \
  --eid 42 \
  --attr posting/amount \
  --expect "USD -80" \
  --value "USD -90"
```

Alternative name:

```sh
update-entity-safe
```

The important part is not the exact command name. The important part is that
the command is explicit about being a safe compare-and-set update, not a normal
blind patch.

## 4. Semantics

Inside one `BEGIN IMMEDIATE` transaction:

1. Fold the current entity attributes from the event-log.
2. Read the current value for `eid + attr`.
3. Compare it to `expect`.
4. If it does not match, reject the update and append no datoms.
5. If it matches, append the normal retract-then-assert datoms.
6. Validate the affected root entity after applying the change.
7. Project after the transaction when projection is enabled.

For a normal value replacement:

```clojure
[eid attr old-value true]
[eid attr new-value false]
```

For an unset-style safe update, the same idea can be extended later by
expecting a value and then appending only the retraction. For asserting a
previously missing value, the expected value could be represented explicitly as
`nil` or a separate `--expect-missing` flag. That should be decided before
adding the command, because CLI ambiguity around `nil` values is easy to make
painful.

## 5. Conflict behavior

Reject conflicts. Do not silently discard the incoming fact.

Silent discard makes the caller believe their write succeeded. A safe update
should return a structured conflict that lets UI/API callers refresh the
entity, show the current value, and let the user decide whether to reapply
their edit.

Example error shape:

```json
{
  "error": "conflict",
  "eid": 42,
  "attr": "posting/amount",
  "expected": "USD -80",
  "current": "USD -90"
}
```

This gives the desired behavior:

```text
same eid + same attr changed first    -> reject second writer
same eid + different attr changed first -> allow, then validate root entity
```

## 6. Transaction-like entities

Posting and tag edits are usually child entity edits, but validation has to
happen at the affected top-level parent entity.

For example, changing `:posting/amount` on a posting eid should compare the
posting fact, append posting datoms if safe, then validate the parent
transaction or periodic transaction after the folded entity graph is updated.

This follows the projection model: child identities live in the event-log, but
the rendered entity body is the top-level parent. Safe update conflict checks
can be child-level; domain validation must be root-level.

## 7. Why not a hash here

A Convex-style hash or workspace state hash is useful when the rule is "reject
the write if anything in the workspace changed since the caller read it."

Safe updates want a smaller rule:

```text
reject only if this exact fact changed
```

For datoms, the previous value is already a clear fact-version token. Hashing
`eid + attr + value` would mostly hide the same information behind another
layer. Comparing the expected value directly is easier to debug, print in CLI
errors, and test.

The older workspace-hash idea can still exist as a stricter multiplayer mode,
but safe single-attribute updates are a better fit for patch-style UI edits.

## 8. Open questions

- Should the command be named `safe-update-entity` or `update-entity-safe`?
- Should safe updates support unset and assert-if-missing in the first version?
- Should `expect` compare string values after schema coercion, or compare the
  exact stored datom value?
- Should ordinary `update-entity` eventually become unsafe/internal, with UI
  clients using safe updates by default?
- What is the exact JSON/API error envelope for conflicts?
