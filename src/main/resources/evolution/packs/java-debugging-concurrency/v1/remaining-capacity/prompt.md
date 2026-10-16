# Reserve the last delivery capacity

A dispatch service owns a fixed capacity. reserve(orderId,units) returns true once for a positive reservation that fits. Duplicate IDs, nonpositive quantities and over-capacity requests return false without mutation. cancel(orderId) returns true once and restores exactly those units; unknown IDs return false. remaining() must stay between zero and initial capacity. IDs are nonnull. Repair accounting AND duplicate admission; protecting only the numeric decrement is insufficient. Diagnose with a concrete order history; preserve all-or-nothing updates across the reservation map and remaining capacity.

Deliver a reproduction, diagnosis, correction and verification notes. Record assistance honestly. Public checks are examples, not complete safety proofs. No score or evidence is created by opening or manually completing this task.
