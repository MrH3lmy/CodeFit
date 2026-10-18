# CodeFit v0.25 — external worker and reviewer handoff

## Purpose

The repository includes preparatory **non-executing** admission (`grader_boundary_v24.py`) and **non-executing** signed-receipt quarantine (`worker_receipt_v25.py`). Neither is a grader or safe learner-code runtime. Do not connect these modules to mastery, rewards, XP, or production systems.

## Future implementation milestones (not completed here)

1. **Security design and owners:** choose a dedicated VM/container isolation architecture; define and independently audit OS-enforced filesystem, PID, CPU, memory, disk, time, network and process-tree controls, including hostile process and output tests.
2. **Independent worker identity:** owner-controlled enrollment and rotation of worker signing keys; pin keys and policy deployment revisions outside client payloads; require a transactional nonce ledger and audited receipt ingestion.
3. **Deterministic Java 21 grader:** separate private held-out tests from learner submissions; build compile and runtime stages; produce bounded structured receipts without raw test answers; independently reproduce results in an audit environment.
4. **External content review:** 28 technical claims and six blinded pedagogy missions awaiting independent source-grounded review; preserve zero approvals until actual reviewer evidence is obtained.
5. **Pilot accuracy:** recruit real consenting participants, calibrate false pass/fail rates, accommodate alternate valid solutions, audit fairness and error messages, and require separate release authorization before any real learner XP/mastery integration.

## Safety constraints

- Never treat an Ed25519-valid result as security certification or correct answer.
- Never run untrusted Java via this local workspace.
- Never export the purchased PDF, excerpts, answer keys, or private reviewer secrets to GitHub.
- Never merge these experimental knowledge branches or modify live learner progression as part of this handoff.
