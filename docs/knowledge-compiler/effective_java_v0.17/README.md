# CodeFit Knowledge Compiler v0.17 — original serialization drafts

This is an **unmerged, unreviewed educational handoff**, not a complete compiler snapshot or learner release. Its parent is the verified complete private v0.16 checkpoint, not the partial GitHub mirror.

Five original author drafts are grounded in complete private readings of Effective Java **Items 85–89**: alternatives to Java native serialization; caution with Serializable compatibility; custom logical serialized form; defensive readObject; and enum singleton identity. No publisher paragraphs, code or source PDFs are distributed.

**20 original Predict/Debug/Build/Defend missions REPLACE EJ4-085..089 slots**. Cumulative: **71/90** original deep author drafts, **284** replacement overlays, **419 distinct draft slots unchanged**. Five source hashes/locators plus 25 targeted anchors checked. New Java21 fixture: **62 assertions passed** and **15 intentionally invalid javac cases rejected**. Historical and new: **182 Python tests passed across 20 bounded modules**, including nine historical PDF comparison cases; no single-process all-suite pass claimed. Cumulative Java fact assertions: **930** and expected compiler rejects: **173**.

The Java fixture uses only locally constructed known-class streams. It demonstrates that a simple String does not exercise an ObjectInputFilter callback in the same way as an ordinary serialized class; NEVER infer from these tests that native deserialization of arbitrary bytes is secure. No production learner grader or independent semantic/educational review exists.

No CodeFit product runtime, player XP, rewards, mastery, learner release or branch merges were changed. See release gates.
