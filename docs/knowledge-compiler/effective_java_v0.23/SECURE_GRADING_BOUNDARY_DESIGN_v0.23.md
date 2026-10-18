# CodeFit Knowledge Compiler — secure grader boundary design (v0.23)

**Status:** Architecture and threat model ONLY. No sandbox has been built, hardened, or run against hostile Java submissions. No learner code is executed by the v0.22/v0.23 calibration tools.

## Trust boundaries

1. Learner input is untrusted byte data; never invoke `javac` or `java` on the application server, browser host, Kubernetes control-plane node, or personal developer machine.
2. A separately operated execution service should receive an immutable exercise definition, a one-time attempt ID, and bounded submission payload. It must not have access to answer keys, reviewer enrollment keys, tokens, production services, metadata endpoints, or other attempts.
3. Compilation and runtime must be inside disposable OS/VM isolation with egress denied, no service account, non-root UID, read-only base image and writable ephemeral workspace; enforce CPU, memory, PID, file count, disk, wall time, output and process-tree limits outside the guest.
4. Hidden tests and reference solutions stay outside learner-modifiable directories. The grading orchestrator must not trust stdout claims such as `TESTS_PASSED=100` from submitted code.
5. Only independently reviewed rubrics plus independently constructed held-out learner cases can enable grading. Review-signature validation by itself does not grant mastery or release.

## Abuse and correctness tests required before implementation approval

| Attack / defect | Expected enforced outcome | Evidence presently available |
|---|---|---|
| Infinite loop / runaway child process | Deadline kills full isolated job tree, not just parent | None; implementation missing |
| Fork bomb, thread storm, heap exhaustion | Hard PID/CPU/memory budget; no host impact | None |
| Socket, DNS, loopback, cloud metadata | No network namespace egress or inbound access | None |
| Filesystem traversal, symlink tricks, `/proc`/`/sys`, device files | Cannot access host/source secrets, writable only within limited disposable scope | None |
| Deserialization/native/JNI/process execution | Confined inside isolated guest with denied privileges and no host mounts | None |
| ANSI/log flooding/huge stdout | Truncate output by bytes and prevent UI injection | None |
| Hidden test or answer key probing | No readable hidden material in the untrusted compartment | None |
| Time-dependent/random/flaky program | Deterministic environment, adjudicated flaky policy | None |
| Compiler error misclassified as failing unit test | Separate error types with bounded safe diagnostics | None |
| Execution service restarted mid-attempt | Attempt idempotency, audit record, cleanup, no XP | None |
| Attempts to forge reviewer approvals | Pin externally checked reviewer roster; verify signatures, identity out of band | v0.23 synthetic cryptographic intake, NOT external identity verification |

## Implementation prerequisites

- A separate isolated runner ownership/design review, including chosen virtualization or jailed-container boundary, threat model signoff, cost controls, observability and capacity limits.
- Independent security penetration test with red-team inputs before processing any arbitrary Java text.
- Proven immutable templates and controlled JDK version, deterministic tests, bounded feedback, explicit scoring semantics for partial credit.
- Human-adjudicated held-out learner answers, error taxonomy and measurement of false accept / false reject.
- Explicit user authorization before integration into CodeFit, learner mastery, XP/rewards, or production.

## Interim pilot recommendation

Use the v0.22 structured-answer experiment for offline demonstrations only. Obtain independently verified review decisions for 28 claim prompts (EJ 19–25) and six pedagogy prompts, then use those decisions to revise or reject material. Do not infer safety or learning effectiveness from a 331-test author-generated Python suite.
