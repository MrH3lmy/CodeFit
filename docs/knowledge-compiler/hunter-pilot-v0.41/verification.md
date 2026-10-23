# PR #271 finalization: preview-only verification

Baseline: main `d6f05e55b805c09e7b69f5ac96dc64baa33cb609`, including merged #272.
The existing PR head `c6b2af92e51320a413255b84a6982a3afa78c731` was reconciled with
main by a normal merge, without conflicts or history rewriting. No existing
Ascension, JavaFX, IPC, evaluator, exposure, progression or persistence code was
changed. The adapter production code and pinned input are unchanged from that
head; this finalization adds a cross-boundary regression and documentation.

## Provenance and reproducibility

| Material | SHA-256 | Verification scope |
| --- | --- | --- |
| Supplied public input and identical bundled resource | `76377d04ecb0ff45690df32bbc001f66bfaa672e1935327e1c09b2b047a46f52` | Recomputed from both files; exact byte equality and compiled-in/resource pins checked |
| Deterministic public DTO export | `95ff42f24a88ac85400a4cda43daf9e0caabab8e4e324fa21c992f78a2ecb874` | Recomputed after exports into two independent temporary working directories and repeated export in each |
| Author-reported private v0.41 checkpoint | `a4a1e168f2b00c26677e7f75052d5585cf4425f1641af697dd7927a7ae25e865` | Locator in the authorized handoff; private archive was not available for independent recomputation |

The four source-section hashes are pinned exactly in the supplied JSON and tested
against modification. Their source bodies are deliberately absent, so this review
cannot recompute them or verify the reported v0.40 parent archive. No remote backup
or independently verified source-body claim is made. Hash integrity is not content
accuracy, copyright clearance, technical review, pedagogical review or approval.

Manual inspection of every distributable input field found original-author titles,
IDs, prerequisites, stage labels/IDs, restrictions and hash locators only. There
are no publisher excerpts, book code, PDF bytes, prompts, hints, answers, grader
keys, private URLs, signing keys or learner records in the preview assets. The
export omits the private-checkpoint locator. Targeted credential/private-link/PDF
signature scanning found no matches in the changed files; this is a bounded scan,
not a universal copyright or secret-detection guarantee.

## Stage and prerequisite completeness

All four skills have exactly PREDICT, DEBUG, BUILD and DEFEND, with sixteen unique
source-item-matched stage IDs. That is complete **preview stage metadata**; stage
briefs, instructions, answer formats, difficulty and checks are not supplied and
are not invented. Missing or duplicated stages, cycles, altered IDs, spoofed review
or eligibility flags, added private fields and changed bytes fail closed.

All four compiler skills remain unmapped. The six distinct external prerequisites
remain unresolved: `java.control.flow`, `java.exceptions.checked`,
`java.collections.iteration`, `java.numeric.integer.overflow`, `java.api.contracts`,
`java.api.preconditions.v1`. The internal local-scope → enhanced-iteration
relationship is preserved. The [mapping matrix](README.md#catalog-boundary-and-mapping-audit)
records all four IDs. No suffix stripping or related-skill substitution occurs.

## Security and dependency review

- No changes to `pom.xml`, desktop manifests or lockfile; no new dependencies.
  The adapter uses the existing Jackson core parser, immutable Java records and
  existing graph/ref types. It does not depend on SQL, networking, execution,
  desktop IPC, evidence, grading or progression services.
- OSV query on 2026-10-23 for the parser dependency
  `com.fasterxml.jackson.core:jackson-core:2.22.4` returned no advisory IDs. This
  checks that dependency/version, not every transitive Java dependency or native
  runtime component. Prior CodeQL results in the earlier README are historical;
  no fresh CodeQL run is claimed by this finalization.
- `npm audit --json` found one **existing low-severity development dependency**
  advisory, [GHSA-g7r4-m6w7-qqqr](https://github.com/advisories/GHSA-g7r4-m6w7-qqqr),
  for esbuild 0.27.3's Windows development server. There were zero moderate/high/
  critical advisories. This PR neither changes that dependency nor runs a dev
  server; packaged Electron loads local built assets. Track an independent
  toolchain upgrade and Windows validation; do not conflate this finding with a
  clean whole-repository vulnerability scan.
- Loader inputs/collections/strings/depth are bounded, strict and checksum-pinned;
  error codes are redacted. There is no arbitrary classpath lookup or source URL.
- Developer CLI writes only its build output, atomically publishes the file pair,
  refuses conflicting/partial output, and supports identical replay. It is not
  registered with Electron, not a service for untrusted path arguments, and not a
  security boundary against another process controlling the same build directory.
- PublicPreview is a data record, not an approval token. Future consumers must use
  the validated loader and strict wire schema, never trust a caller-constructed DTO
  to authorize content or learning effects.
- Isolated SQLite tests prove loading/rejecting/exporting does not initialize an
  absent profile or change seeded learner bytes. The added
  `KnowledgePreviewAscensionBoundaryTest` compares the actual Java skill projection
  before/after loading drafts and proves none of the draft IDs enters its nodes,
  prerequisites or estimates. No learner profile is used for development/testing.

## Verification commands and final CI

The focused run passed **67 tests, zero failures/errors/skips**:

```sh
xvfb-run -a mvn -B -Dtest=KnowledgePreviewTest,KnowledgePreviewReadOnlyIntegrationTest,KnowledgePreviewAscensionBoundaryTest test
xvfb-run -a mvn -B test
cd desktop
npm ci
npm run build
npm run prepare:java
npm test
npm run package
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" \
  sh scripts/offline-linux.sh npm run test:e2e
npm audit --json
```

Use Java 21 and a functioning X11/Chromium sandbox. No assertion or database
isolation extension is weakened. The complete Java suite includes the existing
learning/evaluator/P2P/JavaFX regressions; the complete desktop suite includes real
Ascension/profile switching, cancellation, restart and isolated deck workflows.
The [PR #271 handoff](https://github.com/MrH3lmy/CodeFit/pull/271) records the final
commit, completed full-suite totals and GitHub workflow links on that exact head.
Do not use the earlier 1,893-test branch report as verification of the merged tree.

Earlier head workflow runs `39946630321` and `39946630289` initially required action
but subsequently completed successfully. Final-head CI must run again after this
merge; no approval bypass or weakening of workflow requirements is part of this PR.

## Release restrictions and next step

Keep UNREVIEWED, zero independent approvals, and production import, grading,
mastery, XP and rewards disabled. Four skill mappings and six prerequisites,
independent technical/pedagogical review and owner adjudication (#253), actual
original briefs, JDK21 content confirmation and evaluator readiness (#269) remain
separate gates. Adapter review is not content approval or permission to run a pilot.

After independent preview-only PR review, use the separate
[frontend-boundary proposal](frontend-boundary.md) to scope a Gate 2 read API and
visibly segregated draft view. No API or frontend feature is implemented here.
Keep #261 open. Do not merge automatically.
