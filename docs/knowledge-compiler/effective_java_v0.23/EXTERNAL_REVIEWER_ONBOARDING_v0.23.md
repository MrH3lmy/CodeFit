# External reviewer onboarding — owner action required

The compiler authors **cannot independently review themselves**. No trusted external reviewers are currently enrolled. All claim/pedagogy approvals remain **zero**.

1. The CodeFit owner selects an independent senior Java 21 reviewer and a separately selected pedagogy reviewer. Conflict-of-interest, experience and identity verification happen **outside** this compiler and are recorded in the owner's review register.
2. Reviewers generate Ed25519 key pairs privately (e.g., on their own machines). **Never share private keys with the author/compiler**, never commit keys to GitHub, and never upload keys into a shared ZIP. Owner verifies their public keys through a second trusted channel.
3. Owner maintains an external trusted reviewer roster and a **separately pinned SHA-256** of the roster. The roster is not part of v0.23 artifacts; the test-generated keys are ephemeral only. The caller must explicitly pass the trusted roster and pinned digest to `review_attestation_v23.validate_signed_review`.
4. Each technical decision binds `EJ22-...` claim ID and SHA-256 hash of the full original claim object, plus independently produced evidence digest, explanation and signed timestamp. Pedagogy decisions bind an exact blind mission prompt instead. These signed claims can be checked by the verifier, but cryptographic checking is **not** final acceptance.
5. The owner or independent adjudicator confirms review independence, genuine source consultation and actual evidence, then records `ACCEPT`, `REJECT` or `NEEDS_REVISION` with decisions and any appropriate follow-up. A revised claim invalidates its prior acceptance.
6. Finally, a distinct pedagogical review, grading reliability evidence, security signoff and explicit release authorization are required. **Never** automatically activate XP, mastery, rewards or code execution on receipt of signatures.

The v0.23 verifier checks the signature against the roster supplied by the caller; it cannot determine whether the roster itself was trustworthy. The `owner_verified_out_of_band` field is an assertion, **not** an authentication system. Keep the pinned roster and its administration outside the authoring pipeline.
