# Independent historical coverage moments (#285)

The coverage matrix previously showed revision IDs but could visually combine one chosen skill revision with other skills' latest revisions on a page. The data was real, but the layout did not identify their different as-of times, evidence epochs and policy versions. It was not a synchronized cross-skill assessment.

The matrix now labels each cell with its exact saved revision ID, full UTC `asOf` timestamp, epoch ID, policy version and interpretation status. These are existing allowlisted Java `PracticeAnalytics` fields; no new IPC or database contract is needed. Accessible button names include these fields and the table references the historical-context explanation. Long opaque epoch IDs wrap at narrow widths.

The view explicitly treats cells as independent historical saved moments. This read projection has no immutable reconcile-command batch ID, so it does not infer a batch from nearby or identical timestamps. A future synchronized comparison must carry an actual Java-owned receipt/batch identity in a separately reviewed API. No implementation of that feature is implied here.

Revision choice and coverage states are unchanged: selected skill uses its chosen in-range revision; other skills use their latest revision on the current page within the filter, even if incompatible. Unsupported/incompatible records remain unavailable instead of silently using an adjacent valid revision. Missing records remain NOT_EVALUATED with metadata unavailable. Saved exclusion/contradiction/unsupported coverage remains Java-owned. No TypeScript mastery inference is introduced.

## Regression fixture and privacy

The `mixed` AnalyticsFixture mode uses the existing Java mastery service and explicit epoch-reset repository to save four genuine historical estimates at different times/epochs. A test-only inserted unsupported historical policy header is explicitly INCOMPATIBLE with no interpreted capability; its old valid neighboring revision is retained. This models unavailable historical implementations, not valid evidence under an invented policy. It changes only isolated marked disposable test profiles.

Java and real-Java Node tests assert exact distinct metadata, incompatibility and whole-table read immutability. Packaged Electron tests assert each cell's exact metadata and accessible name, missing filtered cells, explicit refresh, and dark/light layouts at 1280px/760px. Screenshots use only synthetic no-evidence records.

No production Java, schema, profile ownership, publication or evaluation behavior changes. Analytics remains query-only and bounded through existing session/snapshot fencing. No SQL candidate activation or new curriculum is involved. No statement about current skill or synchronized assessment is inferred from the presence of saved estimates.

Final commands, counts and CI links are in the accompanying draft PR. #291 is a separate investigation and is not claimed fixed by this change.
