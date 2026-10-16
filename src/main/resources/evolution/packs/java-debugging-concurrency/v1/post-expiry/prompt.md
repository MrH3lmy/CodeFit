# Expired leases release exactly once

A lease registry is driven by caller-supplied logical time, never wall-clock time. acquire(id,expiresAt,now) accepts a fresh never-used ID only if expiresAt>now. expire(now) removes all active leases whose deadline<=now and returns the number removed in THIS call. release(id) removes one live lease once. active() reports live count. An expired or released ID cannot be reused. Repair inclusive deadline boundaries and double-expiration accounting; ensure compound mutations are atomic. Inputs are nonnegative logical ticks and nonnull IDs.

Deliver a reproduction, diagnosis, correction and verification notes. Record assistance honestly. Public checks are examples, not complete safety proofs. No score or evidence is created by opening or manually completing this task.
