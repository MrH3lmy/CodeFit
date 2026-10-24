# Future Ascent preview boundary — proposal, not an implemented API

PR #271 remains a Java metadata library and developer export tool. This document
proposes a separate reviewed Gate 2 slice under #261/#256 after the preview adapter
is reviewed. It does not add a preload method, renderer asset, learner import or
learning permission. Main includes the evidence-backed Ascension Tree from #272.

## Four distinct authorities

| Surface | Authority and meaning | Forbidden inference |
| --- | --- | --- |
| Author-draft syllabus preview | Pinned `PublicPreview`, `UNREVIEWED`, four skills and sixteen stage IDs | Not a SkillRef, approval, assignment, challenge or earned node |
| Independently approved curriculum definition | Future exact revision plus independent technical/pedagogical decisions and owner adjudication (#253) | Approval does not itself authorize a mission, grader or learner result |
| Authorized learning mission | Existing versioned ChallengeSpec/catalog and presentation/exposure authorization; evaluator readiness gate #269 | Static metadata cannot reveal prompts/keys, start a presentation or grant success |
| Learner skill evidence | Existing exact SkillGraph, evidence, durable evaluation and mastery owners; frozen learner read boundary | Preview IDs, XP, completion and approval are not mastery evidence |

Show drafts in a separately labeled **Author drafts — unreviewed** view, not as
nodes appended to the actual Ascension Tree. Do not style draft stage IDs as
assigned missions, earned badges, locks that XP can unlock, or available graders.
A connection to a learner profile must not imply draft eligibility. Display the
four missing skill mappings and six external prerequisites as unresolved; never
strip `.v1`, guess aliases or infer versions from compiler identifiers.

## Proposed finite read contract

Names below are reserved for design discussion only, not registered commands:

```ts
readCurriculumPreview(): Promise<
  | { ok: true; result: {
      v: 1;
      kind: "author-draft-curriculum-preview";
      source: "bundled-author-metadata";
      learnerBound: false;
      preview: PublicPreviewV1; // exact allowlisted Java export schema below
    } }
  | { ok: false; code: "UNAVAILABLE" | "INVALID_CATALOG" | "CANCELLED" | "TIMEOUT" }
>;
```

`PublicPreviewV1` must strictly mirror `codefit-public-knowledge-preview/0.1`:
revision and public revision hash, PUBLIC_PREVIEW, UNREVIEWED,
JDK21_VERIFICATION_PENDING, zero approvals, all production import/grading/XP/reward/
mastery flags false, caveat, four skills (IDs/titles/source locators/prerequisites/
stage labels and IDs), empty exact mappings, ten explicit blockers. Reject extra
fields and altered restrictions. A future approved schema is a separate version,
not a renderer toggle or a nullable author-draft restriction.

Use a distinct finite Java capability that calls only `KnowledgePreview.loadBundled()`.
No arbitrary path, URL, SQL, graph selector or input bytes from the renderer. No
learner-profile capture is necessary for bundled public metadata; do not route
this through `learner.skills.read` or attach a profile/session identity that would
imply personal evidence. Main must still enforce owning WebContents/main frame,
zero arguments, bundled page origin and operation identity/generation. Reuse the
existing supervised Java process patterns, bounded strict framing and cancellation;
do not introduce a server or a second SQLite writer. A proposed 32 KiB response
cap is ample for this pinned fixture and must be tested against the actual wire
encoding before adoption.

Read failure shows unavailable metadata, never a static fallback catalog. No
background source fetch, watcher or automatic import. All data must come from the
pinned Java adapter; do not copy JSON into `desktop/public`, React constants or
unrestricted generic IPC. Omit the private checkpoint hash, paths, source bodies,
prompts, hints, solutions, reviewer material, hidden checks and learner facts.

## Independent gates and validation for the next PR

1. Owner confirms this exact original metadata revision is safe for its proposed
   audience. This is display permission, not technical/pedagogical approval.
2. Review the distinct typed command, redaction, bounds and operation lifecycle.
3. Add strict schema, unauthorized-frame, cancellation, renderer restart and stale
   response tests; assert zero database/source access and no startup initialization.
4. Test simultaneous draft and learner views: no ID collision, no merging nodes,
   no inherited current/history indicators, no actions reaching mission/evaluation
   services. Preserve the existing dark/light, 1280/760, keyboard and reduced-motion
   behavior. Capture actual packaged screenshots for independent visual review.
5. Keep #253 and #269 as separate gates. A technical hash check, successful build,
   valid signature or this API must never change review/learning eligibility.

No mission descriptions, difficulty, reviewer approvals, prerequisite mappings or
grading mechanisms are supplied by this proposal. These remain missing rather
than synthesized. The eventual reviewed adapter must reuse CodeFit's existing
learning engine, not create another skill/evidence/reward system.
