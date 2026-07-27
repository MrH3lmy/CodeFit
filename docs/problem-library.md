# Problem Library (#144)

The **Problems** screen (`ProblemsController` / `problems.fxml`, reachable from its own sidebar item)
is the first user-facing screen built on top of the problem-solving domain model (#142) and workbook
importer (#143). It offers two views over the exact same data:

- **Blind Order** (default): one row per {@code RoadmapEntry} membership, in roadmap order (stage
  `A → B → C1 → C2 → D1 → D2 → D3`, then position within the stage). A "Next Recommended" card above
  the list highlights the first not-yet-`SOLVED` problem in that order.
- **Topics**: one row per `Problem`, regardless of how many roadmap stages it belongs to, with
  combinable filters (topic, suggested level, minimum quality, platform, current state) and a search
  box that matches title, external code, or platform.

Both views are built by `ProblemLibraryService` from the same `ProblemRepository`/
`RoadmapEntryRepository`/`ProblemProgressRepository` data — switching views, or between Blind Order
and a filtered Topics search, never duplicates a problem or diverges from its progress. Reading
either view is side-effect-free: a problem with no `ProblemProgress` row yet is represented with a
transient `NOT_STARTED` default rather than eagerly inserting one just because it was listed.

## Next-recommended-problem logic

"The next recommended problem skips completed and `ACX` problems" is satisfied by a single rule:
skip anything already in the `SOLVED` state. A workbook status of `ACX` (accepted after retries) is
imported as `SOLVED` the same as a plain `AC` (see `TrainingSheetImportService`), so there is no
separate `ACX` state to check — excluding `SOLVED` already excludes both.

## Filtering

`ProblemLibraryFilter` is an immutable record with one optional field per filter axis and a
`withX(...)` method per field, so the controller can narrow or clear filters one at a time without
rebuilding the whole object. `ProblemLibraryService.applyFilter` requires a row to satisfy every
non-null field; `ProblemLibraryFilter.empty()` matches everything, which is what "Clear Filters"
resets to.

## Row actions

- **Open ↗** opens the problem's URL in the system's default browser via `java.awt.Desktop`, but
  only for `http`/`https` links — any other URI scheme (or a missing/unsupported desktop browse
  capability) shows a status message instead of attempting to open it, which is the "safely" in
  "external URLs are opened safely."
- **Start / Resume** calls `ProblemSolvingSessionService.startOrResume`, creating or resuming the
  problem's persistent solving session (#142). The full solving workspace UI with phase timers is
  #145's scope; this action only guarantees a session exists to resume once that screen lands.

## Empty and import-required states

If there are no problems in the database at all, the screen shows a single explanatory banner
("Import a Training Sheet from Settings…") instead of an empty list or a stack of empty filter
dropdowns. If the library has problems but the current filter combination matches none of them, a
separate, distinct message explains that instead ("No problems match your filters."), so the two
states are never confused with each other.
