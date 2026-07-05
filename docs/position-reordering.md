# Position Reordering

This document explains how entity position changes work, why they can create
extra event-log datoms, and how the current planner tries to delay that cost.

## Position Model

Each ordered entity has an integer position attribute:

- top-level entities use `entity/position`;
- postings use `posting/position`;
- tags use `tag/position`.

New entities get automatic positions from their eid:

```text
position = eid * 1000
```

Multiples of `1000` are reserved for automatic positions. Manual reordering
uses the integers between those automatic positions.

```text
1000          2000          3000
 A             B             C

1001..1999 are available between A and B.
2001..2999 are available between B and C.
```

The mutation API does not accept a numeric `after_position` movement command.
Reordering is identity-based:

```json
{"after_eid": 10}
{"before_eid": 10}
```

`read-journal --after-position` still exists, but it is only a pagination cursor
for projected journal reads.

## Normal One-Change Placement

The planner in `position.clj` receives the current ordered entities in one
ordering scope, the target eid, the position attribute, and either `after_eid`
or `before_eid`.

If the target is already immediately after or before the anchor, the planner
returns no changes.

Otherwise, when the surrounding gap has room, the planner assigns only the
target a new position.

For `after_eid`, the new entity should become the first entity after the anchor.
To preserve future room next to the anchor, the planner places the target near
the right boundary of the gap.

```text
Before:

1000                                      2000
 A                                         B

insert X after A

After:

1000                              1900    2000
 A                                 X       B
```

For `before_eid`, the anchor is on the right, so the planner places the target
near the left boundary.

```text
Before:

1000                                      2000
 A                                         B

insert X before B

After:

1000    1100                              2000
 A       X                                 B
```

This asymmetric choice is intentional. It keeps most of the remaining gap on
the side future repeated same-anchor inserts will need.

## Why Not Always Midpoint?

Midpoint placement burns repeated same-anchor space quickly.

Repeated `after_eid A` with midpoint in a `1000..2000` gap:

```text
1500
1250
1125
1062
1031
1015
1007
1003
1001
```

After that, there is no integer between `1000` and `1001`, so the next insert
requires rebalancing.

With the current biased placement, repeated same-anchor inserts get many more
one-change operations before the first rebalance. In the current tests and
measurement script, a fresh `1000..2000` gap produced:

```text
after_eid:  49 one-change inserts before rebalance
before_eid: 58 one-change inserts before rebalance
```

The exact count is an implementation detail of the current 90/10 biased
placement and integer rounding rules. The important property is that it delays
rebalance compared with midpoint for text-like repeated movement near the same
anchor.

## Rebalance On Exhausted Gaps

Eventually a gap can still become crowded. For example:

```text
1000 1001 1002 1003        2000
 A    P    Q    R           B

insert X after A
```

There is no useful room immediately after `A`. The planner expands away from
the anchor until it finds the smallest local window that can be re-spaced.

```text
Before:

1000 1001 1002             2000
 A    P    Q                B

After:

1000     1250     1500     1750     2000
 A        X        P        Q        B
```

Only the target and the entities inside the smallest needed window receive new
positions. The anchor and the outer boundary stay fixed.

For multi-entity rebalance windows, the planner spaces entities evenly between
the fixed boundaries and skips reserved multiples of `1000`.

## Event-Log Cost

Position changes are ordinary event-log datoms.

For an existing entity position change, mutation emits:

```clojure
[eid "entity/position" old-position true]
[eid "entity/position" new-position false]
```

For a new entity, its chosen position is part of the normal add datoms:

```clojure
[eid "entity/position" new-position false]
```

That means a rebalance can add many datoms. If a rebalance moves 50 existing
entities, it emits 100 position datoms for those existing entities, plus the
target entity's own position assertion or movement datoms.

This is why the planner tries hard to use one-change placement while there is
room. The edge case is repeated movement around the same anchor:

```text
insert after A
insert after A
insert after A
...
```

or:

```text
insert before B
insert before B
insert before B
...
```

Those patterns keep consuming the same local gap. Once the gap is exhausted,
rebalance creates extra position datoms. In a pathological workload, repeated
rebalance can overpopulate the event-log with position churn even though the
logical journal order remains correct.

## Implementation Path

The main implementation pieces are:

- `position/plan-position-changes` plans pure position changes for one ordering
  scope.
- `mutation/root-position-plan` uses the planner for top-level add flows.
- `mutation/plan-amend-position-datoms` uses the planner for `update-entity`
  movement.
- `mutation/position-change-datoms` converts planned changes into ordinary
  retract/assert datoms for existing entities.

Projection does not have special rebalance behavior. It simply folds the
event-log and renders the latest entity positions.

## Current Safety Checks

The tests cover:

- biased `after_eid` and `before_eid` placement;
- smallest-window rebalance;
- repeated continuous rebalancing in both directions;
- no duplicate positions during repeated same-anchor inserts;
- no reserved multiples of `1000` for manually planned positions;
- mutation-side rejection of unsupported `after_position` input;
- projected journal order after rebalance.

