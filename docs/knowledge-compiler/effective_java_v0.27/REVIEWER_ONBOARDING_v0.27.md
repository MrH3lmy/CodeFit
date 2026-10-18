# Reviewer onboarding — operational handoff (v0.27)

## Technical independent reviewer
1. Independently receive source access to the *authorized private* Effective Java third-edition material. Do not publish source excerpts.
2. Receive `technical_claim_packet_v0.27.json` and its manifest fingerprint over an owner-controlled channel; verify hash and reviewer subject IDs against the pinned baseline.
3. For each of 28 claims, reproduce its evidence independently, ideally using your own Java 21 setup; include counterexamples and version/platform caveats.
4. Submit `ACCEPT`, `REJECT`, or `NEEDS_REVISION` plus independent rationale and evidence digest; sign using the separately enrolled Ed25519 reviewer identity and v0.23 domain-bound decision schema.
5. Owner, not packet builder, verifies independence/conflicts, adjudicates evidence, and updates the approval record. A revision resets review.

## Pedagogical independent reviewer
1. Receive **only** `blind_pedagogy_packet_v0.27.json`, never the author rubric or private answer keys before first judgment.
2. For each of 6 missions, produce an expected answer, realistic incorrect answer, discriminating follow-up and fairness or accessibility caveat.
3. Submit separate signed judgments with own evidence; do not count correctness of the author's previously generated cases as learner validation.

## Security/owner release
- The manifest must be pinned **out of band**: a hash bundled beside the data does not independently authenticate it.
- No external reviewer has been enrolled in the project. `review_attestation_v23.py` can verify signatures against independently pinned identities, but cannot certify independence or automatically approve content.
- Keep hidden answer keys and copyrighted material out of GitHub and unsolicited exports.
- The Java grader/worker needs a separately audited isolation boundary before any untrusted code is run. Do not execute user code in this authoring environment.

**Completion of these procedures is not claimed.**
