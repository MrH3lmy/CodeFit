# Graph selection refresh and packaged lifecycle verification (#287 / #288)

Baseline: merged #286 at 3063b671b1b5494f7eb6149bfbf771348cca91e5.

## Root cause and correction

The React catalog retained its previous view for a successful selection/status response whose `view` was null. That null is intentional: Java returns an immutable receipt separately from a query-only catalog view. Reusing the old view displayed the wrong current graph and revision.

A terminal receipt now immediately invalidates the old view and triggers exactly one bounded read through the existing graph IPC. The original receipt remains independently visible while refreshing, on read failure, and on later manual reads. REJECTED receipts also refresh, since a concurrent winner can have changed the authoritative revision. UNCHANGED refreshes without creating a new command. A status with no receipt preserves pending recovery metadata and does not pretend completion. Errors invalidate the view without discarding a known receipt or pending intent. Existing generation/profile/session fencing rejects late responses; disconnect and unmount clear the session.

No Java production code, policies, schemas or IPC channels change. No select is issued merely to refresh.

## Test-only publication

`src/test/java/com/codefit/desktop/GraphFixture.java` is compiled by a host test helper against the bundled Java engine. It exclusively opens a marked isolated disposable Linux profile and calls the existing immutable catalog repositories. It publishes pilot v2, a retired synthetic graph and a graph containing a retired definition. Nothing is seeded into production or exposed through a curator IPC endpoint. The helper runs before Electron connects; existing owner-lock enforcement is preserved.

The packaged test first seeds real historical evaluations and estimates through the existing AnalyticsFixture. It verifies v1 → v2, receipt/header agreement, invalidation while the read response is withheld, unsupported practice and estimation, original-presentation resumption, restart, original receipt recovery and explicit rollback. Whole-table hashes exclude only selection metadata and the deliberately rejected skill-command receipt; historical records and all other rows must remain identical. Real-profile sentinel bytes stay unchanged.

Post-commit fault injection reinstates the exact main-owned metadata intent from the actual committed receipt before restarting Electron, representing death between Java commit and intent cleanup. It does not invent an outcome, source, revision or alternate command. A real-Java Node test separately verifies duplicate command replay, payload conflict, restart lookup and exact unavailable-state reasons. Existing Java tests cover true before/after-commit transaction faults and concurrent CAS.

## Safety review and limits

Java remains the exclusive writer. The UI never infers policy eligibility from graph existence; v2 remains unsupported. Existing historical refs, exposure and unreviewed-curriculum boundaries remain intact. The four synthetic screenshot compositions are explicitly test-only catalog metadata, not approved new learning content.

The original constraints remain: disposable Linux profile only; legacy JavaFX and real learner snapshots unchanged; no real-profile writes, assessment execution, evidence remapping, XP/reward reconciliation or adaptive decisions.

Final test counts, CI links and screenshots are recorded in the PR after verification.
