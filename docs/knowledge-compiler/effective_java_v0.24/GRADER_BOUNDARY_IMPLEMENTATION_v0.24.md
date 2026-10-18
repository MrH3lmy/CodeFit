# v0.24 — Fail-closed Java learner execution admission (nonproduction)

## Implemented, verified facts

`grader_boundary_v24.py` is a **protocol validator only**. It receives a strict JSON envelope, accepts only three preselected Build mission identifiers already present in v0.22, limits Java source to four flat `.java` filenames with bounded UTF-8 content, rejects ambiguous/duplicate JSON keys, forbidden control/bidirectional formatting characters, path traversal, oversized input, and excess files. Accepted attempts produce a SHA-256 work-order fingerprint bound to both exact source and a fixed v0.24 resource/isolation policy. Returned objects contain no source text or answers. The entry point `execute_attempt()` **always raises IsolationUnavailable**. No user-provided code is compiled, interpreted, started, analyzed for correctness, or executed.

An ambient Java 21 installation **does not provide OS isolation**. This environment lacks `nsjail`, `bwrap`, `firecracker`, `docker`, and `podman`; the capability probe always reports `isolation_verified=false` even when those executables are present, because their existence cannot establish confinement. The module exposes no unsafe fallback and does not claim it is a secure runner.

## External isolated execution service — acceptance conditions (none proven here)

1. Pin independently reviewed JDK image and toolchain by immutable image digest, log its digest outside the worker, and prohibit host compiler execution for learner input.
2. Require separate low-privilege execution identity, immutable/read-only root filesystem, no privileged mounts or secrets, disposable writable space, and network namespace with no egress, loopback relay or metadata endpoint access.
3. Enforce at host supervisor/cgroup or stronger boundary: wall deadline, CPU, memory, process count, open files, disk and output bytes; terminate the entire process tree and discard the worker after every attempt.
4. Keep hidden tests and author solutions outside learner-readable guest mounts and away from the learner's compiler classpath. An untrusted process reporting its own tests as passed is not evidence.
5. Verify off-guest worker receipt integrity, attempt idempotency and collision behavior, response-size controls, JVM escape probes, cleanup evidence, and audit logs with **no pasted learner secrets/source**.
6. Run adversarial input suites including process spawning, symlink traversal, network access, log flooding, JVM memory exhaustion, infinite loops, Java agents/JNI/reflection/deserialization, tool invocation and wrapper bypasses, outside the trusted compilation host.
7. Require independently adjudicated grading on unseen submissions, meaningful misconception detection, privacy/copyright review, signed external technical/pedagogy decisions, and explicit product authorization before releasing or connecting XP/rewards/mastery.

## Cautions

- A SHA-256 fingerprint detects accidental or malicious work-order modification; it does **not** authenticate a host runner or provide isolation. A real owner-controlled signed execution receipt must be added later.
- The chosen resource limits in `POLICY` are **design parameters**, not observed enforcement. They require load tests and external security signoff before being trusted.
- The 49 Python boundary tests are author-only unit tests, not penetration testing or real learner-grading evidence.
- No external reviewer is enrolled in v0.24 and no independent approval is granted. All 419 original mission slots remain drafts, and no Java production grader exists.
