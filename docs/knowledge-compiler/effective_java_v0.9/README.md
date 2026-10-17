# CodeFit Knowledge Compiler — Effective Java 44–48 (v0.9)

**Parent:** verified unmerged v0.8 handoff. **Branch only:** no merge, runtime, XP, reward or mastery changes.

This release contains five **original**, source-informed deep author drafts for Effective Java Items **44–48**: functional interfaces, stream readability, collectors and purity, sequence return types, and safe parallel streams. It includes **20 original predict/debug/build/defend mission overlays**, all replacing their existing EJ4-044..048 draft IDs. They do **not** add 20 new unique mission slots.

## Source and validation

A private, text-native 413-page PDF was read across the complete five item spans. SHA-256 of each item body and its page locators matched the pre-existing private source registry. **25 short technical signal probes** matched. No publisher paragraphs, examples, book code or source PDFs are included here.

**Java 21:** one original smoke-fixture class passed **81 assertions**; **12 intentionally invalid snippets** were rejected by javac. Cumulative ledger: **31/90 deep original drafts; 124 revised overlays; 419 distinct draft mission slots; 85 Python tests; 372 Java 21 runtime smoke checks; 54 expected javac rejections.** These counts do not imply independent accuracy review or verified player proficiency.

## Important technical nuances

- Favor standard functional interfaces without erasing domain contracts, checked failures or primitive performance considerations.
- Streams are lazy and single-use; use closeable lifecycles for I/O and avoid stateful audit side effects in pipelines.
- Collectors require explicit duplicate-key, null and ordering policy; parallel reductions demand associativity and noninterference.
- For public sequence APIs prefer Collection where honest and feasible; Java 21 Stream still does not extend Iterable.
- Parallel streams offer **no guaranteed speedup or preserved forEach order**; only representative correctness/performance measurements can justify deployment. Historic benchmark numbers are not Java 21 promises.

## Agent handoff

Treat these as **draft candidate educational assets**, not approved questions. Independently review each claim and mission rubric and design held-out tests before considering isolated learner-grader experimentation. Keep CodeFit production, reward/XP, mastery and unrelated active PRs untouched. No automatic merge.
