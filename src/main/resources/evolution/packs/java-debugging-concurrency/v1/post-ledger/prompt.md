# Reconcile signed ledger revisions

A local ledger receives out-of-order revisions for an account entry. apply(id,revision,amount) accepts only a positive revision newer than that entry's last accepted revision. amount is a signed absolute balance contribution, NOT a delta. total() is the sum of current contributions. A newer revision must replace the old contribution; duplicate or stale revisions return false without mutation. Keys nonnull, all arithmetic fits long. Repair the map/total transition and demonstrate a failing revision history.

Deliver a reproduction, diagnosis, correction and verification notes. Record assistance honestly. Public checks are examples, not complete safety proofs. No score or evidence is created by opening or manually completing this task.
