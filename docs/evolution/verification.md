# Foundation verification and acceptance

Scope: #209 architecture deliverable, audited main `9cf3e5f554829300bca1346511b4bca38ce92e46`.
This change contains Markdown only. No application code, dependency, schema, seed, data migration,
peer protocol or existing issue state is changed. Child issue bodies are refined separately with links
to this foundation; none of #210–#218 is implemented or closed.

## #209 acceptance mapping

| Acceptance criterion | Evidence in this PR | Assessment |
| --- | --- | --- |
| File-linked reuse/gap matrix | [Audit](existing-system-audit.md): actual services/repositories/models and test links; reusable behavior, reliability limits, gaps, modifications and protected behavior for each requested capability | Covered |
| Exact V1 user journey and failure states | [Lifecycle](progression-lifecycle.md): ten steps, ordinal estimate rules, policy branches, interruption/replay/uncertainty/agency; [Java slice](java-debugging-concurrency.md): multiple concrete challenges and evidence-to-decision example | Covered as design, not executable feature |
| ADR for bounded local architecture, truth ownership, versions and privacy | [ADR](adr-0001-local-evolution.md): responsibilities, source-of-truth matrix, additive storage ownership, atomic boundaries, version/correction/deletion rules and P2P exclusions | Covered; proposed for review |
| No duplicate engine/schema proposal | [Audit](existing-system-audit.md), [contracts](domain-contracts.md) and ADR: reuse card scheduling, assessments, original attempt history, runner and XP balance; new storage only for graph, provenance/evaluation and explainable projections | Covered; follow-up integration guards are explicit |
| Small follow-up sequence referencing real classes | [Roadmap](implementation-roadmap.md): all #210–#218 reviewed, dependency correction for executable baseline, source gaps, shared migration ownership and #210 next | Covered |

All requested broader deliverables map through the [documentation index](README.md). Confirmed existing
behavior is confined to the audit and identified references; proposed interfaces, policy thresholds,
reward amounts and hypothetical learner results are explicitly labeled. Human review is still required
before treating these contracts as accepted or merging this PR.

## Existing checks

Results on 2026-10-15:

- Full existing Maven suite: **BUILD SUCCESS**, 1,273 tests, 0 failures, 0 errors, 0 skipped
  (171 test classes; 3m37s). This includes existing Java runner, import-to-practice, assessment isolation,
  mastery/review, migrations, database isolation, JavaFX/FXML, peer privacy/protocol and two-process
  peer integration tests. No skipped-display or missing-JDK caveat applies to this completed run.
- All 100 relative file links in the new documentation resolve; the root README links to its index.
- `git diff --cached --check` passes. The staged diff is limited to README and `docs/evolution/*.md`.
- The repository-root `codefit.db` was absent before and after testing; existing isolated-test guards
  passed. No learner database was opened or migrated by this work.

 Toolchain setup was external
to the repository: Apache Maven 3.9.11 and Temurin JDK 21.0.12.1. The preinstalled Java 21 lacked
`javac`; initial attempts therefore could not compile. Maven also required the session HTTP proxy and
system Java CA trust to resolve dependencies. No project configuration was changed to work around these.

Equivalent successful invocation in this environment (paths are session-local):

```sh
JAVA_HOME=/workspace/tools/jdk-21.0.12.1+1 \
PATH=/workspace/tools/jdk-21.0.12.1+1/bin:$PATH \
/workspace/tools/apache-maven-3.9.11/bin/mvn \
  -s /workspace/tools/maven-settings.xml -B -U \
  -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts test
```

The normal repository/CI command remains `mvn -B test` with a complete Java 21 JDK. Proxy settings
and tool downloads are outside version control. No new tests are added for a documentation-only change.

## What is not verified here

- No Evolution runtime exists yet, so the full proposed progression loop, concurrency rubric,
  restart-safe submission/evidence/reward transaction and migrations remain follow-up acceptance work.
- The example learner results and proposed thresholds/reward amounts are not empirical evidence.
  No pilot, causal learning-gain claim, confidence calibration or cross-domain inference was performed.
- Passing the existing runner tests does not establish security sandboxing or exhaustive thread safety.
- Automated UI tests are not a manual usability/accessibility study. No manual multi-device network,
  NAT traversal or future #179 forwarding behavior is claimed by this documentation PR.
- Architectural review is a human gate: the proposed contracts, reserved content filtering, local
  evaluation marker and peer aggregate exclusions need review before follow-up implementation.

## Risks, migration implications and next work

No learner migration is needed for this PR. Future changes require additive isolated upgrade tests;
legacy unknown assistance/grade/time provenance cannot be recovered by a migration. Avoid unbounded
startup backfills, a second attempt/XP store, hardcoded migration numbers, and implicit peer export
through existing aggregate queries. New original content also needs browsing/route exclusions so
held-out tasks are not leaked by `ProblemLibraryService` Topics. The existing workspace finish and
reward APIs are not a substitute for an atomic idempotent Evolution completion path.

Implement #210 next after foundation review. It establishes stable skill identities/versions and the
validated minimal graph, which #211 evidence mappings and #213 content need. Follow the revised
sequence in the [roadmap](implementation-roadmap.md); do not start #212's executable baseline with
a second grading path or subjective Java self-ratings. Keep #179's networking and sharing contracts
unchanged, and do not merge this PR without review.
