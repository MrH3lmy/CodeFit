# Versioning, invalidation and release gates

## Current boundary

This directory is an author-only review packet outside `src/main/resources`. There is no runtime loader, seed, navigation, GraphSelection command, assessment mapping registration, grader, PostgreSQL connection or learner data mutation. Runtime has no SQL graph to select. A test's publication into its isolated database is not a production release. Existing pilot graph and evidence remain unchanged.

The existing graph definition lifecycle ACTIVE/RETIRED is independent from approval and assessment support. A future candidate can be retired without erasing the original graph; replacement refs must be explicit. Missing/unsupported graph or policy refs fail closed; do not fall back to a Java pilot or pick MAX(version).

## Integrity and immutability

`manifest.json` lists exact relative file names, byte lengths and SHA-256 digests. `manifest.sha256` pins its exact UTF-8 bytes; a test hard-codes that manifest digest. JSON is UTF-8, LF, two-space indentation with one final newline. Digests apply to bytes, not a guessed canonical JSON rendering. Rehashing an edited v1 must also fail the fixed pin check. A SHA proves byte equality, not correct teaching, safe execution, provenance rights or reviewer independence.

A change to a behavior/definition requires a new SkillRef definition version. A changed graph membership/edge requires a new GraphRef version. A packet wording/assessment/provenance change requires a new packet version and manifest pin even if exact semantic refs are intentionally retained; reviewers must explicitly decide whether retaining those refs is justified. Keep old packets and historical exact refs readable. Do not overwrite v1 to manufacture a clean review.

Review records are separately pinned to the reviewed manifest. Actual returned decisions are new append-only artifacts, not edits to the original pending templates. Any material content, mapping, rubric, evaluator or dependency change invalidates prior applicable approval until qualified humans determine re-review scope. Revocation/withdrawal must remain inspectable and block affected release. No previously evaluated evidence is silently remapped or rewritten.

## Separate mandatory gates before playability

1. Owner confirms rights, attribution, license and intended audience.
2. Independently enrolled PostgreSQL/database and pedagogy reviewers return exact-digest-bound decisions; owner adjudicates conflicts and all blocking findings. #253's other corpus remains separate.
3. Separate reviewed catalog-publication change publishes exact definitions/DAG alongside v1 Java graph without implicit selection/migration. No legacy evidence rewrite; approved lifecycle metadata and explicit opt-in selection use existing #222 contracts.
4. Publish exact evidence mappings only with validated target/behavior scope; define family correlation, assistance, exposure, transfer/retention pools and correction semantics. Proposed document IDs are not runtime mappings.
5. Independently implement and audit a compatible SQL assessment mechanism under #269 governance or an explicitly scoped successor: trusted harness, pinned PostgreSQL build, offline distribution decisions, least privilege, isolation from learner files/network, bounded execution/cancellation, crash cleanup, immutable artifacts/results and evaluator-reference/mutant validation. Do not reuse the Java runner by assumption or add a second general grading architecture.
6. Publish reviewed challenge/rubric/check identities through existing catalog/authorization/exposure and submission/evidence contracts. Unsupported required checks prevent verified completion. Human-reviewed explanations require actual scoped review provenance.
7. Explicitly support SQL in mastery/adaptation compatibility policies only after exact behavior mapping, independent-family sufficiency and contradiction/freshness semantics are reviewed. No frontend scoring, XP proof or automatic recommendations.
8. Validate populated-history migrations (only if separately justified), exclusive ownership, backup/rollback/platform gates, privacy/P2P boundaries and full regressions. New graphs do not make real-profile desktop writes permissible.
9. Owner approves a separately reviewed release and any study protocol. This PR runs no learner pilot and makes no efficacy claim.

Roll-forward/rollback is an explicit pointer change among eligible published exact versions. Returning to an older graph never erases newer history or creates new independent evidence. A graph can be inspectable and still unsupported for estimation or assessment. No gate in this list is completed merely by passing this packet's tests.
