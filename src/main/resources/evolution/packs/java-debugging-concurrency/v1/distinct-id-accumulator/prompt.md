# Exactly one charge per event

A billing accumulator processes nonnegative event amounts. add(id,amount) adds a previously unseen ID and returns true; duplicates return false even if the second payload differs. total() is the sum of first accepted payloads, size() is the unique accepted ID count. Negative amounts return false and must not consume the ID. IDs are nonnull, totals fit long. Repair a seen-check/insert/sum race. Neither a thread-safe set nor an atomic sum alone protects the compound exactly-once transition.

Deliver a reproduction, diagnosis, correction and verification notes. Record assistance honestly. Public checks are examples, not complete safety proofs. No score or evidence is created by opening or manually completing this task.
