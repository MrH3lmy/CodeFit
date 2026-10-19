# CodeFit Knowledge Compiler v0.37 — original-only agent handoff

**Unmerged branch; author-side evidence integrity improvement only.** No player runtime, grader, XP, rewards, or mastery changes.

The v0.37 private compiler checkpoint adds an explicit item-boundary PDF text-block locator for 18 existing Effective Java topics (Items 19–25, 34–44). It validates the exact registered 413-page original text-native source fingerprint, excludes page headers/footers and following chapter/item content, and records SHA-256 fingerprints without serializing publisher text.

Two previously overinclusive chapter-transition cases are now delimited: Item 25 before the next chapter introduction, and Item 41 before the following chapter introduction. Items 34 and 42 are anchored after their chapter introductions on their starting pages. **These are extraction-integrity improvements, not independent content approval.**

A versioned **additive** binding record links all 28 existing technical review claims to their new narrow source hashes. Historical claim IDs, older page-range body hashes, six blind pedagogy subjects and all review decisions remain unchanged. A signed independent reviewer/owner may examine the old and new provenance; no hashes were rewritten as a shortcut.

## Verification
- 17 new Python source-locator tests passed.
- Partitioned *available* full historical Python run: **693 pass / 40 modules, no failures or timeouts**. One unrelated historical comparison-PDF module skipped: 9 tests remain deferred and are not counted as passed.
- New Java21 runtime assertions this batch: **0** (source boundary rather than execution behavior).
- Exact private ZIP: 1,141 payload files; independent whole archive SHA256 pin and per-file manifest audit passed. Uploaded to owner's existing private Drive folder, downloaded again and SHA256/CRC/manifest readback verified.
- New unique missions: **0**, new replacement overlays: **0**.

## Scope, provenance and privacy
Five-book structural discovery: 504 units, 157 preliminarily mapped. Effective Java: 90/90 original first-pass author drafts, 419 distinct original mission blueprint slots, 360 **nonadditive** overlays. Source book 2018/Java9-era; JDK21 distinctions still need independent technical examination. Publisher PDF, copyrighted paragraphs, private Drive URLs, signing keys and private scoring guidance are not in this GitHub branch.

**Authentic independent signed reviewer decisions, owner adjudications, external approvals, production graders and verified mastery all remain zero.** See CodeFit issue #253 for reviewer enrollment; nothing was merged or released.
