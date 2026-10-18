# v0.17 independent technical and instructional review queue

| Item | Technical claim to challenge | Mandatory negative control | Release-specific obligation |
| --- | --- | --- | --- |
| 85 | Prefer bounded cross-trust DTO formats; filters are only defense in depth | Allow-all ObjectInputStream; gadget/resource graphs; String filter non-invocation | Threat model, strict limits, endpoint inventory and version-specific filter tests |
| 86 | Serializable is a compatibility and invariant commitment, not just a marker | Matching serialVersionUID with invalid field values | Constructor bypass, superclass and migration review |
| 87 | Custom logical form can decouple implementation graph from durable contract | Invalid/huge length; duplicate entries; leaked derived caches | Explicit read/write symmetry, size bounds, compatibility guarantees |
| 88 | Deserialized mutable reference fields need copy-before-validate | Inverted Date interval and external alias | Object ownership/invariant adversarial testing; do not allow untrusted native bytes |
| 89 | Enum singleton preserves in-JVM identity; readResolve has subtle boundaries | No readResolve identity loss, mutable field reference leak | Old-data migration and enum constant rename compatibility |

For every mission: named reviewer and versioned decision, one accepted answer, two plausibly wrong answers with recorded results, fixed resource budget, fairness and clear failure feedback. This document is a review assignment, not approval.
