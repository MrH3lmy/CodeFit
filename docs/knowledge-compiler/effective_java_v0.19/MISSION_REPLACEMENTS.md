# v0.19 mission replacements — Predict / Debug / Build / Defend

Each row describes four **original draft mission overlays**; they *replace* existing draft IDs, not add distinct missions. Rubrics are conceptual only; graders and independent review are absent.

| Item | Replace original slots | Predict | Debug | Build | Defend |
| --- | --- | --- | --- | --- | --- |
| EJ12 | EJ4-012-01 through EJ4-012-04 | Compare inherited/record/human display and sensitive output | Remove debug-string parsing and leaking token | Masked diagnostic + accessor + separate versioned codec | Logging, export and format compatibility decision |
| EJ13 | EJ4-013-01 through EJ4-013-04 | Predict container/element aliasing | Repair a snapshot sharing mutable internals | Constructor-based nested immutable snapshot with mutation isolation | Clone, constructor, serialization and conversion trade-offs |
| EJ14 | EJ4-014-01 through EJ4-014-04 | BigDecimal HashSet/TreeSet discrepancy and overflow | Repair subtraction comparator and timestamp key collision | Settlement key with order-law checks and stable tie breakers | Natural order vs named business comparators |
| EJ15 | EJ4-015-01 through EJ4-015-04 | public final array vs private defensive copy | Eliminate public mutable API and unsafe getter | Narrow facade, safe snapshot and module-boundary smoke sample | Classpath/module-path/reflection risk review |
| EJ16 | EJ4-016-01 through EJ4-016-04 | Public state and shallow record hazards | Replace unchecked public limit and unsafe array return | Record defensive copy and checked mutable update | SDK record vs class vs internal carrier |

Candidate grading evidence needed per mission: expected accepted solution, two plausible incorrect ones, invariant-based tests, false-positive and false-negative review, stable resource limits. A passing author fixture is not player-mastery evidence.
