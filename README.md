# CodeFit

CodeFit is a JavaFX desktop application for spaced-repetition practice of programming and technical skills. Instead of tracking workouts, it helps learners build fluency with code concepts, command-line workflows, Git, SQL, regex, output prediction, and other technical topics through focused flashcard reviews.

The application entry point is `com.codefit.CodeFitApplication`, which initializes the database configuration and launches the JavaFX dashboard.

## Features

- **Decks** organize practice material by topic or skill area so you can separate subjects such as Java, Git, Linux commands, SQL, or interview prep.
- **Flashcards** store prompts, answers, accepted responses, hints, skill categories, optional time limits, and card types for different technical-practice formats.
- **Review queues** surface due cards using spaced-repetition scheduling, helping you revisit skills when they need reinforcement.
- **Review ratings** let you mark cards Again, Hard, Good, or Easy so future due dates adapt to your recall strength.
- **XP and streaks** provide lightweight progress feedback and motivation as you complete review sessions.
- **Technical-skill practice** supports concept recall and code-oriented card types such as commands, SQL queries, regex patterns, and code-output prediction.
- **Simulated-terminal review mode** is available for command-challenge cards: command cards present a terminal-style answer area, validate typed command attempts against accepted answers, and can show simulated command output during review.

## Requirements

- **JDK 21** is required. The Maven compiler configuration targets Java 21, so make sure both `JAVA_HOME` and your `PATH` point to a Java 21 installation.
- Maven is required for the recommended development workflow.

You can verify your local Java version with:

```bash
java -version
```

## Development

Use the JavaFX Maven plugin to launch the app during development:

```bash
mvn javafx:run
```

This is the recommended launch command because the project depends on JavaFX modules (`javafx-controls` and `javafx-fxml`) and configures the JavaFX plugin with the application main class.

## Running packaged artifacts

Running a compiled JAR directly with:

```bash
java -jar target/codefit-1.0.0-SNAPSHOT.jar
```

may fail because JavaFX runtime modules are not bundled with the JDK. If you run outside Maven, you must provide the JavaFX runtime modules on the module path/classpath yourself, or create a distributable package that includes them.

## Packaging guidance

For a distributable desktop application, use JavaFX-aware packaging tooling instead of relying on a plain `java -jar` workflow. Common options include:

- `jlink` to build a custom runtime image containing the required Java and JavaFX modules.
- `jpackage` to create native installers or application images from a configured runtime image.
- A Maven plugin configuration that integrates JavaFX packaging, runtime-image creation, or native packaging for your target platforms.

When adding packaging, ensure the generated artifact includes the JavaFX runtime modules required by the dependencies declared in `pom.xml` and launches `com.codefit.CodeFitApplication`.

## UI architecture

The desktop UI is a persistent application shell rather than a per-screen window stack.

- **Shell**: `AppShellController` (`fxml/app-shell.fxml`) owns the sidebar and top bar for the
  lifetime of the application. `NavigationService` builds this shell once and, on every
  navigation, swaps only the shell's content host — the sidebar, top bar, and theme selection are
  never rebuilt or reset.
- **Routes**: `com.codefit.ui.Route` is the single source of truth mapping a destination to its
  FXML resource, window title, and which sidebar item (if any) it highlights. Screens that don't
  belong in the primary nav (Syllabus, the global Add/Edit Card composer) still have a `Route`
  entry; they just have no `NavItem` or share one with their parent section.
- **Distraction-free mode**: the shell hides the sidebar/top bar automatically whenever the active
  route is `Route.REVIEW`, and restores them on any other navigation — this is derived from the
  route itself, not managed ad hoc by `ReviewController`.
- **Theming**: `NavigationService` supports Dark and Light only. Both themes implement the same
  eleven-token semantic contract defined in `css/tokens.css` (background, surface,
  surface-raised, border, text-primary, text-secondary, accent, accent-hover, success, warning,
  danger). Legacy Ocean/Forest/Synthwave preferences are mapped onto Dark by
  `NavigationService.sanitizeThemeClass` so an old preferences file can never fail to start.
- **Stylesheets** load in this fixed order (see `NavigationService.STYLESHEETS`), each file scoped
  to a single responsibility:
  1. `tokens.css` — theme token definitions only.
  2. `base.css` — resets, typography, and generic layout helpers.
  3. `controls.css` — reusable buttons, form fields, badges, focus/disabled states, rating
     controls; shared by every screen.
  4. `shell.css` — sidebar, top bar, nav items.
  5. `review.css`, `library.css`, `forms.css`, `progress.css`, `today.css` — screen-specific
     rules, in no particular order relative to each other (they don't overlap).
- **Regression checks**: `RouteTest` and `StylesheetResourcesTest` verify every route's FXML and
  every stylesheet resolve on the classpath, load in the documented order, and cover the four
  primary nav items. `FxmlLoadingTest` loads the shell and every route's FXML through a real
  `FXMLLoader` to catch broken `fx:id`s or controller wiring before a PR is opened; it requires a
  JavaFX-capable display and skips (not fails) in headless CI environments that don't have one —
  run it locally with a display, or under `xvfb-run -a mvn test` on Linux, to exercise it.

## Problem-solving training

CodeFit is gaining a dedicated problem-solving training area (epic #141) alongside flashcard review,
built around a locally-imported roadmap workbook (blind order `A → B → C1 → C2 → D1 → D2 → D3`, with
topic-based browsing as an alternative view over the same data). The persistent data model — problem
identity, roadmap membership, current progress, attempt history, and in-progress solving sessions —
landed first; see [`docs/problem-solving-domain-model.md`](docs/problem-solving-domain-model.md) for
the schema and the reasoning behind the entity split. It is entirely separate from flashcard review:
no problem-solving table references `flashcards`/`decks`, so existing review workflows are
unaffected.

A learner can import their own local Junior Training Sheet workbook (`.xlsx`) from **Settings →
Problem-Solving Training → Import Training Sheet…**. The import is local-only, transactional, and
safe to repeat: it never creates duplicate problems or roadmap memberships, and never overwrites
progress already recorded locally. See
[`docs/problem-solving-workbook-import.md`](docs/problem-solving-workbook-import.md) for the expected
workbook shape and the exact rules the importer follows. The real Junior Training Sheet is never
committed to this repository — only synthetic, programmatically-generated fixtures are used in tests.

Imported problems show up on the **Problems** screen (its own sidebar entry), which defaults to
following the roadmap in Blind Order and highlights the next recommended unsolved problem, or can be
switched to a filterable Topics view over the exact same problems and progress. See
[`docs/problem-library.md`](docs/problem-library.md) for how the two views stay in sync.

## Anki-compatible card import/export

Decks can import and export tab-separated text files (`.tsv` or `.txt`) from the Decks screen with the **Import Cards** and **Export Cards** buttons. Choose a deck first, then select a file.

The required Anki-compatible format is one card per line with the prompt/front in the first field and the answer/back in the second field:

```tsv
front	back
What does `git status` show?	The working tree and staging area state.
```

CodeFit also supports an extended eight-field TSV format. Exported files use this format so CodeFit-specific practice metadata can round-trip:

```tsv
front	back	card_type	accepted_answers	validation_mode	hint	skill_category	time_limit_seconds
What command lists files?	ls	LINUX_COMMAND	ls	COMMAND_NORMALIZED	Remember the common Unix list command.	Linux	30
```

Supported extended fields are:

1. `front` - required prompt text.
2. `back` - required answer text.
3. `card_type` - optional enum value such as `RECALL`, `COMMAND`, `LINUX_COMMAND`, `GIT_COMMAND`, `SQL_QUERY`, `REGEX_PATTERN`, `CODE_OUTPUT`, or `CONCEPT`.
4. `accepted_answers` - optional accepted response text; defaults to `back` when blank.
5. `validation_mode` - optional enum value such as `EXACT`, `CASE_INSENSITIVE`, `NORMALIZED_SPACING`, or `COMMAND_NORMALIZED`.
6. `hint` - optional hint shown during review.
7. `skill_category` - optional category label; defaults to `General` when blank.
8. `time_limit_seconds` - optional positive integer time limit.

Because Anki-style TSV does not use quoting, fields must not contain literal tabs or newlines. CodeFit rejects malformed import rows with line-numbered errors and skips duplicate imports when the same deck already contains a card with the same front text.

### Java BE template decks

Starter Java backend practice templates are available in `src/main/resources/templates/java-be/`. The templates use CodeFit's extended TSV columns:

```tsv
front	back	card_type	accepted_answers	validation_mode	hint	skill_category	time_limit_seconds
```

Available Java BE templates (each is a 20-card deck covering the module's learning objectives with a documented mix of card styles — basic recall, explain/compare, predict behavior or output, diagnose a failure, and write code/SQL/regex/commands — instead of being weighted toward simple recall):

- `01-core-java.tsv`
- `02-collections-streams-generics.tsv`
- `03-jdbc-sql.tsv`
- `04-spring-boot-rest.tsv`
- `05-jpa-hibernate.tsv`
- `06-testing.tsv`
- `07-security.tsv`
- `08-deployment.tsv`

To import a template, open the **Decks** screen, create or select the deck you want to fill, click **Import Cards**, and choose one of the TSV files from `src/main/resources/templates/java-be/`. Repeat this for each module you want to practice. See the template directory's `README.md` for the full per-module coverage matrix (learning objectives and card-style counts).

### Java Concurrency in Practice template decks

A deeper concurrency curriculum is available in `src/main/resources/templates/java-concurrency-in-practice/`. It contains **160 original, paraphrased cards** organized into four progressive decks:

- `01-fundamentals.tsv` — thread safety, atomicity, visibility, publication, confinement, immutability, composition, concurrent collections, queues, and synchronizers.
- `02-task-execution-cancellation.tsv` — executors, futures, parallel task design, cancellation, interruption, shutdown, thread pools, saturation, and single-threaded subsystems.
- `03-liveness-performance-testing.tsv` — deadlock, lock ordering, open calls, starvation, livelock, scalability, contention, benchmarking, and concurrent testing.
- `04-locks-atomics-memory-model.tsv` — explicit locks, conditions, AQS, CAS, nonblocking algorithms, happens-before, safe initialization, and publication.

The curriculum favors explanation and production diagnosis over trivia: 108 `CONCEPT` cards, 46 `RECALL` cards, and 6 deterministic `CODE_OUTPUT` cards. See the template directory's `README.md` for suggested deck names, counts, scope, and a recommended training sequence.

### Advanced Backend Engineering training path

CodeFit registers a **second training path** aimed at senior backend engineers rather than
beginner Java recall: `TrainingPathService.getAdvancedBackendEngineeringPath()`. It appears on the
**Syllabus** screen alongside the Java Backend path (each module tagged with its path name) and
participates in the same mastery-based recommendation engine and review queue — there is no
separate mechanism for it.

The path has 10 modules. Module 1 reuses the Java Concurrency in Practice curriculum above; starter
decks for modules 2-10 (259 cards total) live in `src/main/resources/templates/advanced-backend-engineering/`:

- `02-database-transactions-locking-isolation.tsv` — isolation levels, locking, deadlocks, MVCC.
- `03-idempotency-race-condition-prevention.tsv` — idempotency keys, dedup, CAS, fencing tokens.
- `04-kafka-delivery-semantics-outbox-dlq.tsv` — delivery semantics, transactional outbox, DLQs.
- `05-distributed-transactions-sagas.tsv` — 2PC, sagas, compensation, TCC.
- `06-oauth2-oidc-service-authentication.tsv` — grant types, JWTs, JWKS, mTLS.
- `07-caching-consistency-invalidation.tsv` — cache patterns, stampedes, invalidation races.
- `08-observability-production-debugging.tsv` — logs/metrics/traces, thread/heap dumps, SLOs.
- `09-jvm-memory-gc-performance.tsv` — heap generations, GC tuning, leaks, JIT warm-up.
- `10-api-database-failure-scenarios.tsv` — timeouts, retries, circuit breakers, failover.

Each module has a documented learning objective, prerequisites, and mastery threshold. See the
template directory's `README.md` for the full coverage map, the intended senior-backend audience,
and import instructions.
