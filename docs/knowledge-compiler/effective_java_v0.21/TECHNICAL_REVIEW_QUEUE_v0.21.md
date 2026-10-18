# Independent review queue — CodeFit v0.21

**All tasks open.** The v0.21 checks are internal author challenges, not independent approvals. Independent reviewers should work from the private full source spans, not trust the v0.21 note statements merely because smoke tests pass.

## P0: Material source/Java21 review

1. **EJ-ITEM-024**: independently verify the 2018-era nonstatic-member retention guidance versus the tested Java 21 `javac` omitted `this$0` field in unused-owner variant. Confirm language specification construction rules, compiler-version and toolchain behavior, and implications for memory retention; reject both blanket-retention and blanket-no-retention claims. Validate EJ20-024-Q1 and Q3 revised teaching rubrics.
2. **EJ-ITEM-023**: validate distinction between sealing subtype universe and validating record fields; check shallow immutability and canonical constructor examples; inspect revised Q1 and Q3.
3. **EJ-ITEM-021**: evaluate synchronized per-call delegation versus whole-call invariants when a new interface default is introduced; inspect Q1. Run a genuinely scheduled multithreaded regression if higher assurance needed.

## P1: Pedagogy/compatibility

4. **EJ-ITEM-022**: determine when an enum is right for stable identifiers but not rapidly changing currency or pricing policy; inspect Q3.
5. **EJ-ITEM-019/020/025**: challenge constructor dispatch/self-use, interface/Object conflicts, and multiple package-private top-level declarations. Validate the key accepted and rejected cases and identify misleading distractors.
6. A separate pedagogy reviewer should critique all 28 Q1–Q4 mission briefs for fairness, signal strength, ambiguity, misleading counterexamples and scaffolding. The author revised six; other 22 are not approved.

## Review protocol

For each item, record reviewer identity and independence, private source locator and fingerprint, exact claim checked, independent reproducer, verdict ACCEPT / CHANGE / REJECT, severity, counterexample, JDK-version notes, and explicitly named corrected mission overlay IDs. Keep book text private. Do **not** infer player mastery from author Java smoke checks. Reviewers should not modify the runtime or merge branches.
