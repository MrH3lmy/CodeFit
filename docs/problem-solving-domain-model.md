# Problem-solving training: domain model and schema (#142)

Problem-solving training is a workflow entirely separate from flashcard review (epic #141). This
document describes the persistent data model introduced for it, the invariants each table enforces,
and why the five entities are split the way they are. It's the schema/naming reference the
acceptance criteria for #142 call for; the workbook importer (#143, see
[`problem-solving-workbook-import.md`](problem-solving-workbook-import.md)), Problem Library (#144,
see [`problem-library.md`](problem-library.md)), solving workspace (#145, see
[`problem-solving-workspace.md`](problem-solving-workspace.md)), submission/reflection tracking
(#146), dashboards (#147), and flashcard integration (#148) all build on top of it. The importer
needed one small additive extension to this layer (Connection-scoped overloads, see the importer
doc), and #145 added a `paused` column to `problem_solving_sessions` and a nullable
`session_outcome` column to `problem_attempts` (both additive, both documented in the workspace doc)
— otherwise no table, constraint, or existing method signature described below changed.

## Why five tables, not one

A naive design might store "a problem" as a single row with its roadmap position, current status,
and last submission all inline. That breaks as soon as the same problem needs to appear at more than
one roadmap position, or needs a history of more than one submission — you'd either duplicate the
problem's identity per position, or lose all but the most recent attempt. Instead:

| Table | Model class | Cardinality | Purpose |
|---|---|---|---|
| `problems` | `Problem` | one row per unique problem | Identity: what the problem is, independent of where it's used or how anyone is doing on it. |
| `roadmap_entries` | `RoadmapEntry` | many rows per problem | Membership: which roadmap stage(s) a problem belongs to, its order, set, and mandatory flag at that position. |
| `problem_progress` | `ProblemProgress` | exactly one row per problem | The learner's current standing: state, perceived difficulty, how it was solved, notes. |
| `problem_attempts` | `ProblemAttempt` | many rows per problem | The immutable history of every submission and its verdict/timing. |
| `problem_solving_sessions` | `ProblemSolvingSession` | at most one row per problem | The live, resumable, not-yet-submitted phase-timer state (foundation for #145). |

None of these tables reference `flashcards` or `decks`, and nothing in `flashcards`/`decks`
references them. Deleting or editing flashcard data can never affect a problem-solving record, and
vice versa — this is enforced structurally, not by convention.

## Table definitions

All five tables are created additively in `DatabaseConfig.createProblemSolvingTables` via
`CREATE TABLE IF NOT EXISTS`, the same pattern used for every other CodeFit table. Because they are
brand new tables rather than columns added to existing ones, initializing them over an existing
CodeFit database is inherently backward compatible: no existing table, column, or row is touched.

### `problems`

```sql
CREATE TABLE problems (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    external_code TEXT NOT NULL,
    platform TEXT NOT NULL,
    title TEXT NOT NULL,
    url TEXT,
    topic TEXT NOT NULL DEFAULT 'General',
    quality_rating INTEGER CHECK (quality_rating IS NULL OR quality_rating BETWEEN 1 AND 5),
    learning_resources TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(platform, external_code)
)
```

`UNIQUE(platform, external_code)` is the natural key that keeps repeated workbook imports (#143)
from ever creating a duplicate `Problem` row. `learning_resources` stores zero or more reference
links using the same list-of-strings JSON/plain-text codec as a flashcard's accepted answers
(`AcceptedAnswerCodec`) — it's a generic "list of short strings" format, not something specific to
answer grading.

### `roadmap_entries`

```sql
CREATE TABLE roadmap_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    problem_id INTEGER NOT NULL REFERENCES problems(id) ON DELETE CASCADE,
    stage TEXT NOT NULL,
    sequence_order INTEGER NOT NULL,
    set_number INTEGER,
    mandatory INTEGER NOT NULL DEFAULT 1,
    suggested_level TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(stage, sequence_order),
    UNIQUE(problem_id, stage)
)
```

Two constraints do the work here:

- `UNIQUE(stage, sequence_order)` — a roadmap slot (e.g. "stage A, position 7") can only be held by
  one problem at a time.
- `UNIQUE(problem_id, stage)` — the same problem cannot be registered twice within one stage.

Together they allow the same problem to occupy positions in **more than one** `RoadmapStage` (e.g.
appear in both stage `A` and stage `C1`) — which the workbook does for some problems — while making
it structurally impossible to duplicate a membership within a single stage. `RoadmapStage`'s
declaration order (`A, B, C1, C2, D1, D2, D3`) is the intended blind learning order; sorting by
`RoadmapStage.ordinal()` (see `RoadmapEntryRepository.findAllInRoadmapOrder`) is preferred over
sorting the string column alphabetically, which would put `C1` before `B`.

### `problem_progress`

```sql
CREATE TABLE problem_progress (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    problem_id INTEGER NOT NULL UNIQUE REFERENCES problems(id) ON DELETE CASCADE,
    state TEXT NOT NULL DEFAULT 'NOT_STARTED',
    perceived_difficulty TEXT,
    solved_with TEXT,
    final_category TEXT,
    approach_notes TEXT,
    mistake_notes TEXT,
    completed_at TEXT,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
)
```

`UNIQUE(problem_id)` is what makes this "the current progress record" rather than a history —
`ProblemProgressRepository` only ever exposes `save` (first row) and `update` (every row after that);
`ProblemProgressService.getOrCreate` is the only way callers reach either one, so "exactly one
progress row" is enforced both at the schema level and at the call site.

#146 adds ten more additive columns (`perceived_difficulty_rating`, `important_observation`,
`time_complexity`, `space_complexity`, `lesson_learned`, `actual_topic`, `editorial_understood`,
`other_solutions_reviewed`, `simpler_implementation_considered`, `better_complexity_considered`) for
post-solve reflection; see [`problem-solving-submissions-reflection.md`](problem-solving-submissions-reflection.md)
for why they're split from `state`/`completed_at` into their own `updateReflection` write path, and why
the original `perceived_difficulty` column stays in place, unused, rather than being renamed or dropped.

### `problem_attempts`

```sql
CREATE TABLE problem_attempts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    problem_id INTEGER NOT NULL REFERENCES problems(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    submission_result TEXT NOT NULL,
    reading_time_seconds INTEGER,
    thinking_time_seconds INTEGER,
    coding_time_seconds INTEGER,
    debugging_time_seconds INTEGER,
    submitted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes TEXT,
    UNIQUE(problem_id, attempt_number)
)
```

The inverse of `problem_progress`: many rows per problem, each one immutable once written.
`ProblemAttemptService.recordAttempt` computes `attempt_number` as `count(existing attempts) + 1`,
and the unique constraint is the safety net that turns an accidental double-write into a rejected
insert rather than a silently duplicated attempt.

### `problem_solving_sessions`

```sql
CREATE TABLE problem_solving_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    problem_id INTEGER NOT NULL UNIQUE REFERENCES problems(id) ON DELETE CASCADE,
    phase TEXT NOT NULL DEFAULT 'READING',
    reading_seconds_elapsed INTEGER NOT NULL DEFAULT 0,
    thinking_seconds_elapsed INTEGER NOT NULL DEFAULT 0,
    coding_seconds_elapsed INTEGER NOT NULL DEFAULT 0,
    debugging_seconds_elapsed INTEGER NOT NULL DEFAULT 0,
    notes TEXT,
    active INTEGER NOT NULL DEFAULT 1,
    started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
)
```

Deliberately separate from both `problem_progress` (aggregate current state) and `problem_attempts`
(finalized submissions): this table holds live, mutable, not-yet-submitted timer state that can be
paused and resumed across app restarts. #142 only establishes the table, model
(`ProblemSolvingSession`), repository, and a minimal service (start/resume, accumulate elapsed
seconds per phase, end, reset) — the full workspace UI and phase-transition workflow is #145's scope.

## Enums

| Enum | Values | Used by |
|---|---|---|
| `RoadmapStage` | `A, B, C1, C2, D1, D2, D3` | `RoadmapEntry.stage` |
| `ProblemState` | `NOT_STARTED, IN_PROGRESS, SOLVED, NEEDS_REVISIT` | `ProblemProgress.state` |
| `SubmissionResult` | `AC, ACX, CS, WA, TLE, RTE, MLE` | `ProblemAttempt.submissionResult` |
| `SolvedWith` | `SELF, HINT, EDITORIAL, SOLUTION, PREVIOUSLY_SOLVED` | `ProblemProgress.solvedWith` |
| `DifficultyLevel` | `EASY, MEDIUM, HARD` | `RoadmapEntry.suggestedLevel` |
| `FinalCategory` | `STRONG, SHAKY, WEAK` | `ProblemProgress.finalCategory` |
| `SolvingPhase` | `READING, THINKING, CODING, DEBUGGING` | `ProblemAttempt`'s four time fields, `ProblemSolvingSession` |
| `ComplexityClass` | `O_1, O_LOG_N, O_N, O_N_LOG_N, O_N_SQUARED, O_N_CUBED, O_EXPONENTIAL, O_FACTORIAL, OTHER` | `ProblemProgress.timeComplexity`/`spaceComplexity` (#146) |

`DifficultyLevel` only covers "how hard the curriculum expects this to be"
(`RoadmapEntry.suggestedLevel`). "How hard it actually felt" is `ProblemProgress.perceivedDifficultyRating`,
a free 1-10 integer (#146) rather than the same three-value enum — a curriculum-author's coarse
`EASY`/`MEDIUM`/`HARD` estimate and a learner's own self-reported feeling turned out to need different
resolutions, so they're intentionally on separate scales instead of being forced to share one.

`FinalCategory` is a coaching-facing signal, independent of both `SolvedWith` (how much help was
used) and `ProblemState` (workflow position) — a problem can be `SOLVED` with `SELF` help and still
be `SHAKY` if it took several attempts, which is exactly the kind of gap #147's dashboards are meant
to surface.

## Deferred by design

- **Import semantics** (matching workbook rows to problems/entries, and never overwriting a newer
  local progress record with a blank or older imported value) belong to the workbook importer (#143),
  which is the only layer that needs to compare against external, potentially-stale data.
- **The solving workspace UI**, phase-transition rules, and turning a session into a finalized
  attempt belong to #145/#146.
- **Problem Library browsing** (blind-order default, topic-based alternative view over the same
  `problems`/`problem_progress` rows) belongs to #144; the repositories here
  (`findAllInRoadmapOrder`, `findByTopic`) already expose what that view needs.
