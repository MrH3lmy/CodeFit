# An acknowledged job returns twice

A durable-queue model tracks pending IDs, in-flight IDs and completed IDs. enqueue(id) returns true only on first-ever admission. claim() moves the oldest pending ID to in-flight, or returns null. ack(id) succeeds exactly once and only for an in-flight ID. pending()+inFlight()+done() equals the count of admitted IDs; duplicate enqueue or acknowledgment cannot lose or duplicate work. IDs nonnull. Repair the queue/set transition boundaries and reproduce duplicate acknowledgment. This delayed task is only retention evidence if the future selector verifies elapsed time and exposure; the cohort label alone proves neither.

Deliver a reproduction, diagnosis, correction and verification notes. Record assistance honestly. Public checks are examples, not complete safety proofs. No score or evidence is created by opening or manually completing this task.
