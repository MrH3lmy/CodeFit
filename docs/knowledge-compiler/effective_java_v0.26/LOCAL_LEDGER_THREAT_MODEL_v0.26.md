# v0.26 — Local receipt ledger threat/assurance matrix

| Threat / failure | Mechanism or test | Residual risk |
| --- | --- | --- |
| Replayed receipt after process restart | SQLite UNIQUE nonce + original work-order and attempt constraints | Lost/restored database can forget history |
| Two simultaneous duplicate callbacks | `BEGIN IMMEDIATE` plus UNIQUE constraints; thread and OS-process tests | Not safe across independent database copies |
| Reused attempt ID with a different signed nonce | UNIQUE attempt and work-order constraints | Caller must issue a genuinely new attempt ID to retry |
| Signing-key substitution | Public key digest separately pinned and immutable ledger metadata | Operator enrollment and transport authenticity external |
| Invalid or forged signed worker receipt | Reuse v0.25 Ed25519 and bound-policy verifier | A malicious but legitimately signed worker can lie |
| Unexpected altered ledger metadata | Initialization and intake reject settings mismatch | Same-UID/root compromise and DB rollback not protected |
| Insecure local directory, link or DB file | Operator-owned private directory/file checks | No hardened filesystem boundary or distributed secret management |
| Learner code execution / arbitrary payload | No code runner or grading API in ledger | Actual secure worker not built or audited |
| Accidental reward or mastery from `TESTS_PASSED` | Only quarantined non-release return type and snapshot | Future integrators must enforce owner-controlled release gating |
| Ledger unavailable or corrupt | Intake refuses to accept and release | No HA/automatic recovery, backups, or retention policy |

**Security status:** INTERNAL AUTHOR-SIDE VALIDATION ONLY; independent review and real worker isolation remain mandatory. No external trusted worker key is enrolled.
