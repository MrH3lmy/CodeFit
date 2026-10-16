package com.codefit.evolution.quest;

import static com.codefit.evolution.quest.QuestTypes.*;

import java.util.*;

/** Published check specifications; not an evaluator or a scoring implementation. */
public record ChallengeRubric(CatalogRef ref, List<Check> checks, String limitations) {
    public record IoCase(String id, String stdin, String expectedStdout, int expectedExit) {
        public IoCase {
            CatalogRef.id(id);
            Objects.requireNonNull(stdin);
            Objects.requireNonNull(expectedStdout);
            if (stdin.length() > 16000
                    || expectedStdout.length() > 16000
                    || expectedExit < 0
                    || expectedExit > 255)
                throw new IllegalArgumentException("Bounded exact I/O case required");
        }
    }

    public record Check(
            String id,
            CheckKind kind,
            boolean required,
            List<BehaviorTarget> targets,
            String specification,
            String verification,
            List<String> assets,
            List<IoCase> cases,
            int timeoutSeconds,
            Visibility visibility,
            String limitations) {
        public Check {
            CatalogRef.id(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(visibility);
            targets = CatalogValues.distinct(targets, 16);
            assets = CatalogValues.distinct(assets, 16);
            assets.forEach(CatalogRef::id);
            cases = List.copyOf(cases);
            if (cases.size() > 16
                    || cases.stream().map(IoCase::id).distinct().count() != cases.size())
                throw new IllegalArgumentException("Unique bounded I/O cases required");
            boolean executable =
                    kind == CheckKind.DETERMINISTIC_IO
                            || kind == CheckKind.OBSERVED_CONCURRENCY
                            || kind == CheckKind.TERMINATION;
            if (executable == cases.isEmpty())
                throw new IllegalArgumentException(
                        "Executable checks require exact I/O cases; other checks do not execute");
            CatalogValues.text(specification);
            CatalogValues.text(verification);
            CatalogValues.text(limitations);
            if (timeoutSeconds < 1 || timeoutSeconds > 30)
                throw new IllegalArgumentException("Bounded check timeout required");
            if (kind == CheckKind.REFLECTION && required)
                throw new IllegalArgumentException(
                        "Ungraded reflection cannot be a required objective gate");
        }
    }

    public ChallengeRubric {
        Objects.requireNonNull(ref);
        checks = List.copyOf(checks).stream().sorted(Comparator.comparing(Check::id)).toList();
        CatalogValues.text(limitations);
        if (checks.isEmpty()
                || checks.size() > 64
                || checks.stream().map(Check::id).distinct().count() != checks.size()
                || checks.stream().noneMatch(Check::required))
            throw new IllegalArgumentException(
                    "Unique checks and at least one required check required");
    }
}
