# A cursor omits the first unread record

A synchronization endpoint returns the next page AFTER an exclusive cursor. Exercise.page(sortedIds,after,limit) returns at most limit IDs strictly greater than after, preserving input order. IDs are strictly increasing integers; limit<=0 returns empty. The starter treats the exclusive cursor as an array index and skips a record when IDs have gaps. Produce a minimal regression input, locate the faulty boundary rather than the assertion, and implement the value-based rule. This sequential regression family is distinct from concurrency accounting.

Deliver a reproduction, diagnosis, correction and verification notes. Record assistance honestly. Public checks are examples, not complete safety proofs. No score or evidence is created by opening or manually completing this task.
