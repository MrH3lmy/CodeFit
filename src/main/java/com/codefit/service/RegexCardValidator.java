package com.codefit.service;

import com.codefit.model.RegexCardConfig;
import com.codefit.model.RegexCardFlag;
import com.codefit.model.RegexMatchMode;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Grades a learner's submitted regex by compiling it and executing it against a card's configured
 * positive/negative example strings ({@link RegexCardConfig}), instead of comparing it as text
 * against a saved "correct" pattern. This accepts any pattern equivalent to the intended one and
 * rejects one that merely looks similar but matches a different language — the actual defect this
 * card type existed to fix.
 *
 * <p>{@code java.util.regex} has no cooperative cancellation: a catastrophically backtracking
 * submission (e.g. {@code (a+)+$} against a long non-matching string) cannot be interrupted mid-match.
 * Each example is therefore matched on its own daemon thread with a hard wall-clock budget
 * ({@link #EXAMPLE_TIMEOUT_MS}); when the budget is exceeded the grading call returns a
 * {@link Outcome#TIMEOUT} result immediately and abandons the stuck thread rather than blocking the
 * caller (and the JVM) on it. The abandoned thread keeps burning CPU until the pattern's own
 * exponential blowup would have finished anyway, but it is a daemon thread so it never prevents
 * shutdown, and every real submission is expected to resolve in well under the budget.</p>
 */
public final class RegexCardValidator {

    private static final long EXAMPLE_TIMEOUT_MS = 300;

    private RegexCardValidator() {
    }

    public enum Outcome { PASS, INVALID_SYNTAX, TIMEOUT, MISCONFIGURED, FAIL }

    /**
     * @param failingExample the example that first failed, or {@code null} when there is nothing to
     *                        report ({@link Outcome#PASS}, {@link Outcome#MISCONFIGURED}, or a blank
     *                        submission). Never the card's accepted pattern — only ever one of the
     *                        card author's configured example strings.
     * @param failingExampleShouldMatch whether {@code failingExample} was a must-match (true) or
     *                                  must-not-match (false) example.
     */
    public record Result(Outcome outcome, String failingExample, boolean failingExampleShouldMatch, String syntaxError) {
        public boolean passed() {
            return outcome == Outcome.PASS;
        }
    }

    public static boolean matches(String attempt, String encodedConfig) {
        return grade(attempt, RegexCardCodec.decode(encodedConfig)).passed();
    }

    public static Result grade(String submittedPattern, RegexCardConfig config) {
        if (submittedPattern == null || submittedPattern.isBlank()) {
            return new Result(Outcome.FAIL, null, false, null);
        }
        if (config.mustMatch().isEmpty() && config.mustNotMatch().isEmpty()) {
            return new Result(Outcome.MISCONFIGURED, null, false, null);
        }

        Pattern compiled;
        try {
            compiled = Pattern.compile(submittedPattern, toPatternFlags(config.flags()));
        } catch (PatternSyntaxException invalidSyntax) {
            String description = invalidSyntax.getDescription();
            return new Result(Outcome.INVALID_SYNTAX, null, false,
                    description == null ? invalidSyntax.getMessage() : description);
        }

        for (String example : config.mustMatch()) {
            Result failure = checkExample(compiled, example, config.matchMode(), true);
            if (failure != null) {
                return failure;
            }
        }
        for (String example : config.mustNotMatch()) {
            Result failure = checkExample(compiled, example, config.matchMode(), false);
            if (failure != null) {
                return failure;
            }
        }
        return new Result(Outcome.PASS, null, false, null);
    }

    private static Result checkExample(Pattern compiled, String example, RegexMatchMode matchMode, boolean shouldMatch) {
        Boolean matched = matchWithTimeout(compiled, example, matchMode);
        if (matched == null) {
            return new Result(Outcome.TIMEOUT, example, shouldMatch, null);
        }
        if (matched != shouldMatch) {
            return new Result(Outcome.FAIL, example, shouldMatch, null);
        }
        return null;
    }

    private static int toPatternFlags(Set<RegexCardFlag> flags) {
        int bitmask = 0;
        for (RegexCardFlag flag : flags) {
            bitmask |= switch (flag) {
                case CASE_INSENSITIVE -> Pattern.CASE_INSENSITIVE;
                case MULTILINE -> Pattern.MULTILINE;
                case DOTALL -> Pattern.DOTALL;
            };
        }
        return bitmask;
    }

    /** @return the match result, or {@code null} if it did not complete within {@link #EXAMPLE_TIMEOUT_MS}. */
    private static Boolean matchWithTimeout(Pattern pattern, String example, RegexMatchMode matchMode) {
        Callable<Boolean> task = () -> {
            Matcher matcher = pattern.matcher(example == null ? "" : example);
            return matchMode == RegexMatchMode.FULL_MATCH ? matcher.matches() : matcher.find();
        };
        // A fresh single-use executor, not a shared pool: a shared bounded pool would let enough
        // pathological submissions permanently occupy every worker, so unrelated later grading calls
        // would queue behind them forever instead of just this one timing out.
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "regex-card-grader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Boolean> future = executor.submit(task);
            return future.get(EXAMPLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException taskFailed) {
            return null;
        } finally {
            executor.shutdownNow();
        }
    }
}
