# v0.30 — private evidence vault protocol

Owner-side prototype only. The private module retains exact signed-envelope bytes, checks Ed25519 signatures, reviewer role, subject fingerprint, receipt timestamp and nonce uniqueness. Each capture adds a locally SHA-256-linked journal record. Capture occurs before v0.28 SQLite quarantine intake; read-only reconciliation requires every retained envelope to match every ledger row and an independently pinned chain tip.

Create a dedicated owner-controlled POSIX 0700 directory outside public source and source-free ZIP. Files must be private 0600 regular files. Pin reviewer roster SHA-256 and audit chain tip using a separate trusted channel, not a value read back from the same mutable directory. Capture, SQLite intake and external anchoring are not one transaction; any crash/orphan/mismatch fails closed pending owner investigation. A local hash chain is not tamper-proof and is not an independent audit.

No genuine reviewer enrollment, signature, human approval or externally anchored evidence was produced. Synthetic tests use ephemeral keys and do not establish reviewer independence, content accuracy, grading security or mastery. Never publish publisher excerpts, private signed evidence, keys, identities or answer keys. Never merge, deploy or change CodeFit XP/rewards/mastery.
