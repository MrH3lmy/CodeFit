# One slot, two arrivals

A connection gate permits at most limit active users. enter() returns true exactly when a slot is claimed, leave() releases one and rejects leaving an empty gate; used() is the live count. Two callers can currently pass a capacity check before either increments used. Repair the whole check-and-increment boundary. Do not implement this as an unbounded atomic increment followed by a late decrement: that exposes a forbidden intermediate state. Constructor limit is nonnegative. Propose a history in which the starter exceeds capacity.

Deliver a reproduction, diagnosis, correction and verification notes. Record assistance honestly. Public checks are examples, not complete safety proofs. No score or evidence is created by opening or manually completing this task.
