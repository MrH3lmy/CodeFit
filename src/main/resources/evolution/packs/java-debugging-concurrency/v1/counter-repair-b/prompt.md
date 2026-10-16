# Repair batched telemetry updates

A service loses batched telemetry updates under concurrent workers. Exercise.add(delta) accepts nonnegative deltas; value() must report their sum after the workers finish. The starter does an unsynchronized read/modify/write. Preserve the API, reject negative deltas without changing state, and make updates linearizable. Reproduce an interleaving that loses an update, repair the operation boundary, and explain visibility versus compound atomicity. Inputs stay within signed long range. Variant B batches deltas 2 and 7 shares its underlying family with related variants; it is not independent transfer.

Deliver a reproduction, diagnosis, correction and verification notes. Record assistance honestly. Public checks are examples, not complete safety proofs. No score or evidence is created by opening or manually completing this task.
