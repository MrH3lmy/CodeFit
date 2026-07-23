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

Available Java BE templates:

- `01-core-java.tsv`
- `02-collections-streams-generics.tsv`
- `03-jdbc-sql.tsv`
- `04-spring-boot-rest.tsv`
- `05-jpa-hibernate.tsv`
- `06-testing.tsv`
- `07-security.tsv`
- `08-deployment.tsv`

To import a template, open the **Decks** screen, create or select the deck you want to fill, click **Import Cards**, and choose one of the TSV files from `src/main/resources/templates/java-be/`. Repeat this for each module you want to practice.

### Java Concurrency in Practice template decks

A deeper concurrency curriculum is available in `src/main/resources/templates/java-concurrency-in-practice/`. It contains **160 original, paraphrased cards** organized into four progressive decks:

- `01-fundamentals.tsv` — thread safety, atomicity, visibility, publication, confinement, immutability, composition, concurrent collections, queues, and synchronizers.
- `02-task-execution-cancellation.tsv` — executors, futures, parallel task design, cancellation, interruption, shutdown, thread pools, saturation, and single-threaded subsystems.
- `03-liveness-performance-testing.tsv` — deadlock, lock ordering, open calls, starvation, livelock, scalability, contention, benchmarking, and concurrent testing.
- `04-locks-atomics-memory-model.tsv` — explicit locks, conditions, AQS, CAS, nonblocking algorithms, happens-before, safe initialization, and publication.

The curriculum favors explanation and production diagnosis over trivia: 108 `CONCEPT` cards, 46 `RECALL` cards, and 6 deterministic `CODE_OUTPUT` cards. See the template directory's `README.md` for suggested deck names, counts, scope, and a recommended training sequence.
