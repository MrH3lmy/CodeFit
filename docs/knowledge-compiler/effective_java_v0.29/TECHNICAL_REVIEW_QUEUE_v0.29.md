# v0.29 Technical & Pedagogy Review Queue

1. **External owner-controlled dependency** — independent technical and pedagogy reviewer identity, conflicts, lawful source access and signed evidence. Issue: https://github.com/MrH3lmy/CodeFit/issues/253. No reviewer may be invented.
2. **Highest-risk claim** — Item 24 Java 21 outer-reference field elision; verify exact compiler/JDK and constructor-vs-field distinction independently. Then Item 21 batch atomicity and Item 23 record invariants.
3. **Owner adjudication** — issue real decision records with separate human evidence; never infer approval from cryptographically valid `ACCEPT` alone.
4. **Evidence continuity** — raw signed envelopes are not retained in v0.28 ledger; design a private evidence vault plus independent hash pins and adversarial storage/recovery tests. Until then readback signature re-verification remains impossible from the ledger alone.
5. **Java grader** — separately build and audit isolated worker and independently authored held-out tests; do not run untrusted Java without isolation.
6. **Broader corpus** — 347 of 504 structural units still have no preliminary draft mapping; deep semantic content and independent QA remain unfinished beyond Effective Java.
