# Independent reviewer packet

Status: **PENDING** technical review, **PENDING** pedagogical review, **0** independent approvals. No reviewer has been enrolled by this authoring work. AI-assisted author checks are not independent review. The separate JSON records are unfilled decision templates, not an approval system.

## Enrollment and reproducible decision records

An owner enrolls reviewers independent from the original authoring process, checks database/PostgreSQL experience for technical scope and assessment/instructional experience for pedagogy, and records conflicts. Owner sends the manifest SHA-256 via an authenticated out-of-band channel; hashes delivered alongside altered content do not authenticate an author. Reviewer verifies each manifest entry, then records exact reviewed manifest digest, identity/credentials, conflict disclosure, scope, decision time, ACCEPT/REJECT/NEEDS_REVISION, reproducible evidence references and findings. Do not write PENDING with an invented identity, signature or date.

Technical and pedagogical decisions must be separate. Owner adjudication is independent of either technical test success or a valid signature. This packet implements no new attestation verification. Use the independently reviewed governance machinery when available; #253 remains open and its Effective Java review scope is not silently extended. Preserve original pending packet; actual decision records should be append-only artifacts bound to its digest in a separate reviewed change. Do not put reviewer keys, private answer keys, learner data or restricted publisher content in public records.

## Technical checklist

- [ ] Review all six exact definitions, 18 behaviors and six edges; confirm each endpoint and scope.
- [ ] Reproduce PostgreSQL 17 isolation claims against independently chosen exact minor/build. Verify RC statement snapshots, RR snapshot behavior, SSI serialization abort/retry semantics and differences from SQL-standard minimum guarantees.
- [ ] Challenge FD/key/decomposition assumptions, NULL/UNIQUE/CHECK semantics, deferrable constraints, and cross-row invariant limits. Flag nullable-uniqueness business assumptions explicitly.
- [ ] Distinguish transaction rollback, savepoint recovery, partial autocommit and uncertain commit acknowledgement; no external-effect atomicity by assertion.
- [ ] Evaluate barrier schedules for lost update, write skew, predicates and deadlocks; require independent safety assertions and correct-solution alternatives. No schedule assumed deterministic solely because threads started together.
- [ ] Evaluate lock ordering, zero-row version conflicts, phantom/absent-row gaps, whole-transaction retries and side-effect deduplication. Verify SQLSTATE/error classification and limits.
- [ ] Reject universal index/timing/plan assertions. Require workload/settings/statistics/parameter pins, bag/NULL equivalence and write/storage costs.
- [ ] Test idempotency fingerprint conflicts, concurrent duplicate reservation, committed result replay, outbox crash windows and consumer deduplication limits. Precisely bound any exactly-once business-effect claim.
- [ ] Confirm no existing Java/SQLite evaluation is represented as PostgreSQL verification and no executable SQL or DB access is added.
- [ ] Review provenance/licensing, release blockers and mapping proposal; identify any behavior too broad for a single assessment.

## Pedagogical checklist

- [ ] Independently establish expected learner entry knowledge; decide whether missing SQL basics requires a curated prerequisite track.
- [ ] Check each behavior can be observed without rewarding vocabulary recognition or activity counts.
- [ ] Critique each dependency: necessary preparation vs optional helpful context; avoid forcing indexes before every locking exercise.
- [ ] Produce independent response examples and misconception probes from the stated business requirements, before consulting future author solutions. There are no hidden solution keys in this packet.
- [ ] Separate declarative constraint checks, controlled histories, performance observations and reviewed explanations; ensure a passing subset cannot imply broad mastery.
- [ ] Require accessible feedback, clear failure vs environment-error outcomes and supported correct alternatives. Do not infer inability from a failed or unsupported run.
- [ ] Assess workload/complexity and whether three behaviors per skill should be split in a future version. No effort/difficulty values are invented here.
- [ ] Evaluate independent transfer contexts and delayed retention plans, assistance/familiarity capture and rehearsal exclusions.
- [ ] Identify unfair hidden assumptions about PostgreSQL tooling, domain knowledge, hardware, language or access to infrastructure.
- [ ] Verify learners retain control over skipping, pausing and changing direction; no approval, unlock or mastery implied by viewing.

## Questions requiring decisions

1. Is PostgreSQL 17 the appropriate first implementation target, and which maintained minor/build and platform can be independently validated?
2. Are schema reasoning and index workload interpretation sufficiently narrow, or should later graph versions split them after pilot evidence?
3. Which SQL prerequisites need their own reviewed graph rather than an entry precondition?
4. Can a future trusted harness support valid alternative SQL transaction forms without unsafe instrumentation or false-positive concurrency claims?
5. Which explanations need human assessment, and what actual authorized review contract will record scope/provenance?
6. Which independent assessment families can demonstrate transfer and retention without leaking held-out tasks?
7. What runtime isolation, DB privileges, process cleanup and resource controls are acceptable for potentially adversarial SQL? No existing runner is assumed sufficient.
8. Which public distribution license and author attribution does the owner authorize?

Reference reading for reviewers (context, not copied text or proof of review): PostgreSQL 17 documentation, transaction isolation (`https://www.postgresql.org/docs/17/transaction-iso.html`), explicit locking (`https://www.postgresql.org/docs/17/explicit-locking.html`), constraints (`https://www.postgresql.org/docs/17/ddl-constraints.html`) and EXPLAIN (`https://www.postgresql.org/docs/17/using-explain.html`). Reviewers must verify claims against their exact tested build; no test in this PR executes PostgreSQL.
