package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.evolution.quest.*;
import com.codefit.model.ValidationMode;
import com.codefit.service.*;

import java.time.*;
import java.util.*;

/** Runs only installed catalog harnesses and the learner's own frozen source. Not a sandbox. */
public class JavaChallengeEvaluator {
    public static final String VERSION = "local-java-v1";
    private final CodeRunner runner;
    private final BundledAssets assets;
    private final Clock clock;
    private final JavaMechanismVerifier mechanisms = new JavaMechanismVerifier();

    public JavaChallengeEvaluator() {
        this(new JavaCodeRunner(), new BundledAssets(), Clock.systemUTC());
    }

    public JavaChallengeEvaluator(CodeRunner runner, BundledAssets assets, Clock clock) {
        this.runner = Objects.requireNonNull(runner);
        this.assets = Objects.requireNonNull(assets);
        this.clock = Objects.requireNonNull(clock);
    }

    public static String assemble(ChallengeSpec spec, String artifact, BundledAssets assets) {
        if (spec.runner() == Runner.KEYED_RESPONSE) return artifact;
        String harness =
                assets.read(
                        spec.assets().stream()
                                .filter(a -> a.kind() == AssetKind.HARNESS)
                                .findFirst()
                                .orElseThrow());
        // A saved full draft is supported only with the exact published trusted suffix. The
        // editable fragment is always inspected separately; an extra Main is never executed.
        String fragment =
                artifact.endsWith(harness)
                        ? artifact.substring(0, artifact.length() - harness.length())
                        : artifact;
        return fragment + "\n" + harness;
    }

    public EvaluationResult evaluate(SubmissionSnapshot snapshot, RunCancellationToken token) {
        Instant start = clock.instant();
        if (!VERSION.equals(snapshot.evaluatorVersion()))
            return unavailable(
                    snapshot, Outcome.UNSUPPORTED, "Unsupported evaluator version", start);
        if (token.isCancelled())
            return unavailable(
                    snapshot, Outcome.CANCELLED, "Submission cancelled before execution", start);
        var spec = snapshot.challenge();
        try {
            if (JavaTrainingPack.challenges().stream().noneMatch(known -> known.equals(spec)))
                return unavailable(
                        snapshot,
                        Outcome.UNSUPPORTED,
                        "No evaluator adapter for this exact published challenge/rubric",
                        start);
            assets.validate(spec);
            if (!assemble(spec, snapshot.request().artifact(), assets)
                    .equals(snapshot.executableSource()))
                return unavailable(
                        snapshot,
                        Outcome.EVALUATOR_ERROR,
                        "Frozen harness assembly mismatch",
                        start);
            if (spec.runner() == Runner.KEYED_RESPONSE) {
                var checks = new ArrayList<CheckResult>();
                for (var check : spec.rubric().checks()) {
                    CheckStatus status;
                    if (check.kind() == CheckKind.REFLECTION) status = CheckStatus.UNGRADED;
                    else if (check.kind() == CheckKind.KEYED_DIAGNOSTIC
                            && check.assets().contains("answer-key")) {
                        var answer =
                                AnswerValidator.validate(
                                        snapshot.request().artifact(),
                                        List.of(assets.read(spec.asset("answer-key")).strip()),
                                        ValidationMode.EXACT);
                        status =
                                switch (answer) {
                                    case EXACT -> CheckStatus.PASSED;
                                    case EMPTY -> CheckStatus.INCONCLUSIVE;
                                    default -> CheckStatus.FAILED;
                                };
                    } else status = CheckStatus.UNSUPPORTED;
                    checks.add(
                            new CheckResult(
                                    check.id(),
                                    status,
                                    "Keyed response check; reasoning is ungraded",
                                    List.of()));
                }
                return report(snapshot, checks, start, "");
            }
            if (!runner.isAvailable())
                return unavailable(
                        snapshot, Outcome.TOOL_UNAVAILABLE, "Local JDK unavailable", start);
            try (var compiled =
                    runner.compile(
                            snapshot.executableSource(),
                            "Main",
                            snapshot.profile().compileLimits(),
                            token)) {
                if (!compiled.success()) {
                    Outcome o =
                            switch (compiled.status()) {
                                case CANCELLED -> Outcome.CANCELLED;
                                case TIMED_OUT -> Outcome.TIMED_OUT;
                                case RESOURCE_EXHAUSTED -> Outcome.RESOURCE_EXHAUSTED;
                                case OUTPUT_LIMIT -> Outcome.INCONCLUSIVE;
                                case TOOL_UNAVAILABLE -> Outcome.TOOL_UNAVAILABLE;
                                case INFRASTRUCTURE_ERROR -> Outcome.EVALUATOR_ERROR;
                                default -> Outcome.COMPILE_ERROR;
                            };
                    return unavailable(snapshot, o, clip(compiled.rawOutput(), 12000), start);
                }
                String harness =
                        assets.read(
                                spec.assets().stream()
                                        .filter(a -> a.kind() == AssetKind.HARNESS)
                                        .findFirst()
                                        .orElseThrow());
                String fragment =
                        snapshot.executableSource()
                                .substring(
                                        0, snapshot.executableSource().length() - harness.length());
                var inspection = mechanisms.inspect(fragment, spec.familyId());
                if (!inspection.admitted())
                    return unavailable(snapshot, Outcome.UNSUPPORTED, inspection.detail(), start);
                var results = new ArrayList<CheckResult>();
                for (var check : spec.rubric().checks()) {
                    if (token.isCancelled()) {
                        results.add(
                                new CheckResult(
                                        check.id(), CheckStatus.CANCELLED, "Cancelled", List.of()));
                        continue;
                    }
                    if (check.kind() == CheckKind.CONTROLLED_TRACE) {
                        results.add(
                                new CheckResult(
                                        check.id(),
                                        inspection.boundary(),
                                        inspection.detail(),
                                        List.of()));
                        continue;
                    }
                    if (check.kind() == CheckKind.REFLECTION) {
                        results.add(
                                new CheckResult(
                                        check.id(),
                                        CheckStatus.UNGRADED,
                                        "Reflection retained for future review; not machine graded",
                                        List.of()));
                        continue;
                    }
                    if (check.cases().isEmpty()) {
                        results.add(
                                new CheckResult(
                                        check.id(),
                                        CheckStatus.UNSUPPORTED,
                                        "No supported check implementation",
                                        List.of()));
                        continue;
                    }
                    var runs = new ArrayList<CheckResult.RunObservation>();
                    int repetitions =
                            check.kind() == CheckKind.OBSERVED_CONCURRENCY
                                    ? snapshot.profile().observedRepetitions()
                                    : 1;
                    CheckStatus status = CheckStatus.PASSED;
                    for (var io : check.cases()) {
                        for (int repetition = 1; repetition <= repetitions; repetition++) {
                            var run =
                                    runner.run(
                                            compiled,
                                            io.stdin(),
                                            snapshot.profile().runLimits(check.timeoutSeconds()),
                                            token);
                            var rs = classify(run, io);
                            runs.add(
                                    new CheckResult.RunObservation(
                                            io.id(),
                                            repetition,
                                            rs,
                                            run.exitCode(),
                                            CatalogCodec.digest(run.stdout()),
                                            CatalogCodec.digest(run.stderr()),
                                            clip(run.stdout(), 4096),
                                            clip(run.stderr(), 4096),
                                            run.elapsedMillis()));
                            if (rs != CheckStatus.PASSED) status = rs;
                            if (rs != CheckStatus.PASSED) break;
                        }
                        if (status != CheckStatus.PASSED) break;
                    }
                    results.add(
                            new CheckResult(
                                    check.id(),
                                    status,
                                    check.kind() == CheckKind.OBSERVED_CONCURRENCY
                                            ? "Finite coordinated workloads only; recorded"
                                                    + " repetitions do not enumerate all schedules"
                                            : "Exact specified I/O case comparison",
                                    runs));
                }
                return report(snapshot, results, start, "");
            }
        } catch (RuntimeException e) {
            return unavailable(
                    snapshot,
                    Outcome.EVALUATOR_ERROR,
                    "Local evaluation infrastructure failed",
                    start);
        }
    }

    /** Public cases only, ephemeral feedback. No submission, attempt or evidence is published. */
    public List<ChallengeEvaluationCoordinator.CheckFeedback> practice(
            ChallengeSpec spec, String artifact, RunCancellationToken token) {
        if (artifact == null
                || artifact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 200000)
            throw new IllegalArgumentException("Bounded local source required");
        if (spec.runner() != Runner.JAVA21_MAIN
                || JavaTrainingPack.challenges().stream().noneMatch(spec::equals))
            throw new IllegalStateException(
                    "Practice execution is only available for installed Java challenges");
        assets.validate(spec);
        if (!runner.isAvailable())
            throw new IllegalStateException("Local Java 21 compiler/runner unavailable");
        try (var operation = JavaExecutionCoordinator.tryStart(token)) {
            if (operation == null)
                throw new IllegalStateException(
                        "Another Java operation is active; retry after it finishes");
            var profile = RunnerProfile.defaults();
            String source = assemble(spec, artifact, assets);
            try (var compiled = runner.compile(source, "Main", profile.compileLimits(), token)) {
                if (!compiled.success())
                    return List.of(
                            new ChallengeEvaluationCoordinator.CheckFeedback(
                                    "practice-compile",
                                    CheckStatus.NOT_RUN,
                                    "Practice compilation: "
                                            + compiled.status()
                                            + "; no evidence recorded"));
                String harness =
                        assets.read(
                                spec.assets().stream()
                                        .filter(a -> a.kind() == AssetKind.HARNESS)
                                        .findFirst()
                                        .orElseThrow());
                var inspection =
                        mechanisms.inspect(
                                source.substring(0, source.length() - harness.length()),
                                spec.familyId());
                if (!inspection.admitted())
                    return List.of(
                            new ChallengeEvaluationCoordinator.CheckFeedback(
                                    "practice-support",
                                    CheckStatus.UNSUPPORTED,
                                    "Solution form unsupported; no negative skill conclusion"));
                var results = new ArrayList<ChallengeEvaluationCoordinator.CheckFeedback>();
                for (var check : spec.rubric().checks()) {
                    if (check.visibility() != Visibility.PUBLIC || check.cases().isEmpty())
                        continue;
                    CheckStatus status = CheckStatus.PASSED;
                    for (var io : check.cases()) {
                        status =
                                classify(
                                        runner.run(
                                                compiled,
                                                io.stdin(),
                                                profile.runLimits(check.timeoutSeconds()),
                                                token),
                                        io);
                        if (status != CheckStatus.PASSED) break;
                    }
                    results.add(
                            new ChallengeEvaluationCoordinator.CheckFeedback(
                                    check.id(),
                                    status,
                                    "Public practice cases only; no evidence recorded. Finite tests"
                                        + " do not prove thread safety."));
                }
                return List.copyOf(results);
            }
        }
    }

    private CheckStatus classify(RunResult r, ChallengeRubric.IoCase io) {
        if (r.cancelled()) return CheckStatus.CANCELLED;
        if (r.timedOut()) return CheckStatus.TIMED_OUT;
        if (r.outputTruncated()) return CheckStatus.INCONCLUSIVE;
        if (r.exitCode() == null) return CheckStatus.EVALUATOR_ERROR;
        if (r.stderr().contains("OutOfMemoryError")) return CheckStatus.RESOURCE_EXHAUSTED;
        if (r.stderr().contains("Observed nontermination")) return CheckStatus.INCONCLUSIVE;
        if (r.exitCode() != io.expectedExit())
            return r.stderr().contains("AssertionError")
                    ? CheckStatus.FAILED
                    : CheckStatus.RUNTIME_ERROR;
        return r.stdout().equals(io.expectedStdout()) ? CheckStatus.PASSED : CheckStatus.FAILED;
    }

    private EvaluationResult report(
            SubmissionSnapshot s, List<CheckResult> checks, Instant start, String diagnostics) {
        Outcome outcome = Outcome.PASSED;
        // Unavailable required checks take priority over capability judgments. Observed optional
        // counterexamples can refute completion; optional reflection is never an objective gate.
        for (var result : checks) {
            var definition =
                    s.challenge().rubric().checks().stream()
                            .filter(c -> c.id().equals(result.checkId()))
                            .findFirst()
                            .orElseThrow();
            if (!definition.required() && result.status() == CheckStatus.UNGRADED) continue;
            Outcome unavailable =
                    switch (result.status()) {
                        case CANCELLED -> Outcome.CANCELLED;
                        case TIMED_OUT -> Outcome.TIMED_OUT;
                        case RESOURCE_EXHAUSTED -> Outcome.RESOURCE_EXHAUSTED;
                        case INCONCLUSIVE, NOT_RUN -> Outcome.INCONCLUSIVE;
                        case UNSUPPORTED -> Outcome.UNSUPPORTED;
                        case EVALUATOR_ERROR -> Outcome.EVALUATOR_ERROR;
                        case RUNTIME_ERROR -> Outcome.RUNTIME_ERROR;
                        default -> null;
                    };
            if (unavailable != null
                    && (definition.required() || result.status() != CheckStatus.UNSUPPORTED)) {
                outcome = unavailable;
                break;
            }
            if (result.status() == CheckStatus.FAILED) outcome = Outcome.FAILED;
        }
        if (outcome == Outcome.FAILED
                && checks.stream()
                        .anyMatch(
                                c ->
                                        c.status() == CheckStatus.PASSED
                                                && s.challenge().rubric().checks().stream()
                                                        .anyMatch(
                                                                d ->
                                                                        d.id().equals(c.checkId())
                                                                                && d.required())))
            outcome = Outcome.PARTIAL;
        return new EvaluationResult(
                outcome,
                checks,
                diagnostics,
                start,
                clock.instant(),
                s.evaluatorVersion(),
                System.getProperty("java.runtime.version", "unknown"));
    }

    public EvaluationResult unavailable(
            SubmissionSnapshot s, Outcome outcome, String detail, Instant start) {
        CheckStatus status =
                switch (outcome) {
                    case CANCELLED -> CheckStatus.CANCELLED;
                    case TIMED_OUT -> CheckStatus.TIMED_OUT;
                    case UNSUPPORTED -> CheckStatus.UNSUPPORTED;
                    case RESOURCE_EXHAUSTED -> CheckStatus.RESOURCE_EXHAUSTED;
                    case INCONCLUSIVE -> CheckStatus.INCONCLUSIVE;
                    case COMPILE_ERROR, TOOL_UNAVAILABLE -> CheckStatus.NOT_RUN;
                    default -> CheckStatus.EVALUATOR_ERROR;
                };
        return new EvaluationResult(
                outcome,
                s.challenge().rubric().checks().stream()
                        .map(
                                c ->
                                        new CheckResult(
                                                c.id(),
                                                c.kind() == CheckKind.REFLECTION
                                                        ? CheckStatus.UNGRADED
                                                        : status,
                                                detail,
                                                List.of()))
                        .toList(),
                detail,
                start,
                clock.instant(),
                s.evaluatorVersion(),
                System.getProperty("java.runtime.version", "unknown"));
    }

    static String clip(String text, int bound) {
        return text.length() <= bound ? text : text.substring(0, bound);
    }
}
