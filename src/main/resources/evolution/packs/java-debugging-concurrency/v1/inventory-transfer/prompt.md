# Orders crossing the warehouse boundary

A warehouse maintains free and held stock. reserve(id,units) succeeds once per order ID for a positive amount that fits. cancel(id) returns an order to free stock once. A canceled ID may never be reused: a late duplicate from the message broker must not create a second order. free()+held() must equal initial stock; no negative quantities. All state transitions must be atomic to other API calls. IDs nonnull, arithmetic within int. Investigate reserve/cancel replay and concurrent oversell. This is a transfer task: apply an invariant and message-lifecycle reasoning in a new context, rather than editing a telemetry increment.

Deliver a reproduction, diagnosis, correction and verification notes. Record assistance honestly. Public checks are examples, not complete safety proofs. No score or evidence is created by opening or manually completing this task.
