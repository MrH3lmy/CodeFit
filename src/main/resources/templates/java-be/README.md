# Java Backend starter decks

This directory contains original starter flashcard decks for eight core Java backend
modules. Each deck is designed against a documented card-style mix rather than being a
plain glossary dump, so that reviewing a deck builds judgment (explaining trade-offs,
predicting behavior, diagnosing failures, writing real code/SQL/commands) and not just
term recall.

## Decks

| File | Suggested deck name | Cards |
|---|---|---:|
| `01-core-java.tsv` | `Java BE 01 - Core Java & OOP` | 20 |
| `02-collections-streams-generics.tsv` | `Java BE 02 - Collections, Streams & Generics` | 20 |
| `03-jdbc-sql.tsv` | `Java BE 03 - JDBC & SQL` | 20 |
| `04-spring-boot-rest.tsv` | `Java BE 04 - Spring Boot REST APIs` | 20 |
| `05-jpa-hibernate.tsv` | `Java BE 05 - JPA/Hibernate` | 20 |
| `06-testing.tsv` | `Java BE 06 - Testing` | 20 |
| `07-security.tsv` | `Java BE 07 - Security & Auth` | 20 |
| `08-deployment.tsv` | `Java BE 08 - Build, Git & Deployment` | 20 |

Total: **160 cards**.

## Card-style mix (applies to every deck above)

Each 20-card deck follows the same target distribution of card *styles*, independent of
the underlying `card_type` column value used to encode it:

| Style | Cards per deck | Share |
|---|---:|---:|
| Basic recall (terms, annotations, API names) | 3 | 15% |
| Explain or compare (design trade-offs, contracts, semantics) | 5 | 25% |
| Predict behavior or output (deterministic snippets and API/HTTP outcomes) | 4 | 20% |
| Diagnose a failure (symptom-to-root-cause production scenarios) | 5 | 25% |
| Write code, SQL, regex, or a command | 3 | 15% |

This mirrors the project-wide content standard from issue #100: basic definition cards
never dominate a deck, and every module includes multiple scenario/diagnosis cards
grounded in realistic backend engineering situations (leaked connections, N+1 queries,
mass assignment, flaky tests, OOM-killed containers, etc.) rather than trivia.

`CONCEPT`-typed cards are always self-graded (see `AnswerValidator.validateForCardType`),
so the "explain/compare" and "diagnose a failure" styles are implemented almost entirely
with `CONCEPT` cards; the "predict output" style prefers deterministic `CODE_OUTPUT` cards
where a short literal answer exists (an HTTP status code, a printed value, an exception
name) and falls back to `CONCEPT` where the predicted behavior is best described in prose.
The "write code" style uses `SQL_QUERY`, `REGEX_PATTERN`, `GIT_COMMAND`, or `COMMAND` where
a dedicated card type exists, and falls back to `RECALL` for plain Java code snippets,
since this codebase does not yet have a dedicated "write Java code" card type.

## Learning objectives per module

- **01-core-java.tsv** — access control and immutability, abstract classes vs.
  interfaces, the equals/hashCode contract, overloading vs. overriding, composition vs.
  inheritance, checked vs. unchecked exceptions, Integer caching and String
  concatenation semantics, constructor/initialization order, NPE and race diagnosis in
  production, stack depth limits, regex and try-with-resources authoring.
- **02-collections-streams-generics.tsv** — List vs. Set semantics, stream pipelines
  being single-use, type erasure, Comparable vs. Comparator, fail-fast iterators,
  Integer/immutable-list output prediction, stale-hashCode and Arrays.asList pitfalls,
  `remove(int)` vs. `remove(Object)`, generic invariance, parallel stream overhead,
  writing `groupingBy`/`joining` collectors and a generic method.
- **03-jdbc-sql.tsv** — JOIN semantics, PreparedStatement vs. injection, commit/rollback,
  connection pooling, primary key vs. unique constraint, COUNT vs. SUM over empty sets,
  non-repeatable reads under READ COMMITTED, cursor-before-first-row behavior, stale
  connection/statement reuse, batch inserts, deadlocks, missing indexes, connection
  leaks, writing aggregate/anti-join SQL and parameterized JDBC code.
- **04-spring-boot-rest.tsv** — `@RestController` vs. `@Controller`, bean stereotypes,
  singleton-scope thread safety, DTOs vs. entities, request-mapping dispatch, default
  status codes for GET/POST/validation failures, missing required parameters, leaking
  stack traces, ambiguous mappings, ambiguous bean injection, content-negotiation
  failures, mass assignment, writing an exception handler, a mapping method, and a curl
  request.
- **05-jpa-hibernate.tsv** — LAZY vs. EAGER fetching, first- vs. second-level cache,
  relationship ownership and cascade types, dirty checking, `LazyInitializationException`,
  unique-constraint violations, IDENTITY vs. batching, `merge()` semantics, bidirectional
  serialization cycles, missing transaction boundaries, the N+1 select problem, DDL vs.
  runtime `NOT NULL` enforcement, optimistic locking conflicts, writing JPQL and
  derived-query repository methods.
- **06-testing.tsv** — JUnit 5 lifecycle annotations, mocks vs. stubs, unit vs.
  integration tests, test independence, coverage vs. assertion quality, time-based
  flakiness, unused-stub behavior, exceptions before assertions, `assertThrows` type
  mismatches, shared static state, order-dependent flakiness, external-dependency
  flakiness, Mockito matcher misuse, H2-vs-production dialect gaps, `@AfterEach`
  guarantees, writing a parameterized test, a stub, and a Maven test filter command.
- **07-security.tsv** — 401 vs. 403, JWT structure, authentication vs. authorization,
  slow salted password hashing, stateless vs. server-side sessions, CSRF applicability,
  least privilege, default status codes for missing/insufficient credentials, expired
  token handling, user enumeration, SQL injection, XSS-driven token theft from
  localStorage, broken object-level authorization/IDOR, unsalted fast hashing, CORS
  wildcard-plus-credentials rejection, writing a security matcher rule, a password
  regex, and an authenticated curl request.
- **08-deployment.tsv** — git merge vs. rebase, multi-stage Docker builds, rolling vs.
  blue-green deployments, semantic versioning, liveness vs. readiness probes, `git
  status` staged-section output, Docker layer caching, CI failure propagation from a
  failing test, non-fast-forward pushes after a rebase, OOM-killed containers (exit
  137), manual merge-conflict resolution, multi-module build ordering, misconfigured
  environment variables, premature readiness before warm-up, writing a branch-creation
  command, a Dockerfile CMD, and a test-skipping Maven command.

## Importing into CodeFit

For each TSV file:

1. Open **Decks**.
2. Create a deck using the suggested name above.
3. Select that deck.
4. Choose **Import Cards** and select the matching TSV file.

The importer skips a card when the selected deck already contains the same prompt, so
repeating an import is safe.

## Content notes

- Every accepted answer is written to be unambiguous under its declared
  `validation_mode`: literal, deterministic values (HTTP status codes, `true`/`false`,
  exception names, SQL/regex/command text) use `EXACT` or `CASE_INSENSITIVE`, while
  longer phrased answers use `NORMALIZED_SPACING` and are only graded on a matching
  keyword window plus manual self-rating for `CONCEPT` cards.
- No prompt is duplicated within or across these eight files.
- Diagnose-a-failure cards are deliberately scenario-first ("a request fails with...",
  "a deploy crash-loops with...") rather than "what does X mean" trivia, matching the
  card-style standard from issue #100.
