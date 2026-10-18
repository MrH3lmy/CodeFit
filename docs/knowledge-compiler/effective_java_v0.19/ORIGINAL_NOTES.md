# Original technical author notes — v0.19 (pending review)

These are original teaching interpretations, **not publisher excerpts**, independently unapproved and not eligible for player mastery.

## EJ12 — Diagnostics without an accidental serialization contract
- Override opaque inherited object descriptions when useful for diagnostics, while masking tokens, credentials and sensitive identifiers.
- Clearly say whether the format is documented/stable or changeable. Do not parse an undocumented debug representation; use typed accessors and an independently versioned codec for machine interchange.
- Large/cyclic or ORM-associated object graphs should not be eagerly dumped into logs; constrain size and cardinality.
- Java 21 record-generated string output can reveal sensitive components; a record does not provide an automatic privacy policy.
- Negative controls: parsing toString as a protocol, logging an entire secret, assuming generated record output is safe.

## EJ13 — Copying and mutable ownership
- Cloneable changes Object.clone behavior but does not itself declare a publicly callable clone method.
- A shallow object copy shares mutable references; even an array copy still aliases mutable elements.
- For new APIs prefer a copy constructor or conversion factory with a documented ownership graph and snapshot semantics.
- Legacy clone support may require restoration of mutable internals; watch out for bypassed constructors, final-field invariants, overridable methods and concurrent source mutations.
- Java 21 records/List.copyOf are shallow with respect to mutable component objects and collections; no general deep-copy guarantee.
- Negative controls: shared backing bucket arrays, copy of only top-level list, treating a record as deeply immutable.

## EJ14 — Correct ordering and sorted-set identity
- Natural order must satisfy sign symmetry, transitivity and equal-result consistency across all comparable values.
- Prefer Long.compare, Integer.compare, comparator combinators and stable tie breakers; arithmetic subtraction may overflow.
- A sorted set or map treats compare==0 as key equivalence even when equals differs (e.g. differently scaled BigDecimal).
- Domain ordering requires stable comparator keys; changing a sorted key after insertion can break lookup.
- Java 21 does not change these laws. Benchmark any old comparator-performance numbers anew rather than relying on 2018 measurements.
- Negative controls: subtraction comparator, timestamp-only payment key, equating TreeSet equivalence with equals.

## EJ15 — Narrow API surface, defensive state ownership
- Public/protected declarations are long-term extension and compatibility obligations. Keep implementation details private or package-scoped where sensible.
- public static final array is still mutable; copying the array or returning a correctly immutable snapshot is essential.
- An unmodifiable view of a privately mutated backing collection is not a snapshot; immutable collection elements also need ownership review.
- Java 21 JPMS exports, opens and module-path deployment differ from ordinary classpath visibility. A module descriptor alone is not a security sandbox.
- Negative controls: exposing writable static arrays, making internals public just for testing, claiming JPMS always protects reflection.

## EJ16 — Public fields vs accessors and records
- Public writable instance fields prevent invariant checks and lock API users to current representation.
- Intentionally defined accessors and validated updates give public mutable classes a controlled contract; internal package-private carriers may be simpler.
- Public final scalar fields are less dangerous than mutable public fields but remain compatibility commitments.
- A Java 21 record is convenient for immutable-value shapes, yet an array component requires copying on construction and on access.
- Negative controls: unvalidated setter, getter leaking owned array, pretending record components are deeply immutable.

Production context: checkout, payment routing, risk-rule snapshots, settlement ordering, merchant SDK and configuration isolation. None of these examples constitutes actual CodeFit runtime functionality.
