package com.codefit.service;

import com.codefit.model.JavaSolutionDraft;
import com.codefit.model.JavaTestCase;
import com.codefit.model.Problem;
import com.codefit.model.UserProgress;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers draft persistence (#163): surviving across "restarts" (a fresh service instance, standing in
 * for a real app restart the same way {@code ProblemSolvingSessionServiceTest} does), autosave
 * overwriting in place rather than accumulating rows, and the default template for a problem with no
 * saved draft yet.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class JavaSolutionWorkspaceServiceTest {

    private final ProblemService problemService = new ProblemService();
    private final JavaSolutionWorkspaceService workspaceService = new JavaSolutionWorkspaceService();

    private Problem fixtureProblem(String code) {
        return problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", code, "Java Runner Fixture " + code,
                null, "General", null, List.of());
    }

    /** {@code java_test_cases} is a genuine one-to-many table, unlike the single-row draft table every
     *  other fixture in this class overwrites in place — reusing a fixed code across repeated runs
     *  against the same shared test database would accumulate rows instead of resetting, so test-case
     *  fixtures need a code that's actually unique per run. */
    private Problem uniqueFixtureProblem(String testName) {
        return fixtureProblem(testName + "-" + UUID.randomUUID());
    }

    @Test
    void aProblemWithNoSavedDraftGetsAMinimalRunnableTemplate() {
        Problem problem = fixtureProblem("TF-163-TEMPLATE");
        JavaSolutionDraft draft = workspaceService.loadDraft(problem.getId());
        assertEquals("Solution", draft.getMainClassName());
        assertTrue(draft.getSourceCode().contains("class Solution"));
    }

    @Test
    void savingADraftTwiceOverwritesInPlaceRatherThanAccumulating() {
        Problem problem = fixtureProblem("TF-163-OVERWRITE");
        workspaceService.saveDraft(problem.getId(), "Solution", "public class Solution {}", "1 2", "3");
        workspaceService.saveDraft(problem.getId(), "Solution", "public class Solution { /* v2 */ }", "5 6", "11");

        JavaSolutionDraft draft = workspaceService.loadDraft(problem.getId());
        assertEquals("public class Solution { /* v2 */ }", draft.getSourceCode());
        assertEquals("5 6", draft.getStdin());
        assertEquals("11", draft.getExpectedOutput());
    }

    @Test
    void aSavedDraftSurvivesAFreshServiceInstanceStandingInForARestart() {
        Problem problem = fixtureProblem("TF-163-RESTART");
        workspaceService.saveDraft(problem.getId(), "MyClass", "public class MyClass {}", "input", "output");

        JavaSolutionWorkspaceService afterRestart = new JavaSolutionWorkspaceService();
        JavaSolutionDraft reloaded = afterRestart.loadDraft(problem.getId());

        assertEquals("MyClass", reloaded.getMainClassName());
        assertEquals("public class MyClass {}", reloaded.getSourceCode());
        assertEquals("input", reloaded.getStdin());
        assertEquals("output", reloaded.getExpectedOutput());
    }

    @Test
    void runnerAvailabilityIsExposedForActionableSetupGuidance() {
        // Whatever JDK is running this test suite must itself be usable by the runner.
        assertTrue(workspaceService.isRunnerAvailable());
    }

    // ---- Multiple local test cases (#163's "run multiple local test cases") ---------------------

    @Test
    void addedTestCasesAreOrderedAndSurviveAFreshServiceInstance() {
        Problem problem = uniqueFixtureProblem("TF-163-TESTCASES-ORDER");
        workspaceService.addTestCase(problem.getId());
        workspaceService.addTestCase(problem.getId());
        workspaceService.addTestCase(problem.getId());

        JavaSolutionWorkspaceService afterRestart = new JavaSolutionWorkspaceService();
        List<JavaTestCase> reloaded = afterRestart.listTestCases(problem.getId());

        assertEquals(3, reloaded.size());
        assertEquals(0, reloaded.get(0).getPosition());
        assertEquals(1, reloaded.get(1).getPosition());
        assertEquals(2, reloaded.get(2).getPosition());
    }

    @Test
    void updatingATestCasePersistsTheEditedFieldsInPlace() {
        Problem problem = uniqueFixtureProblem("TF-163-TESTCASES-EDIT");
        JavaTestCase created = workspaceService.addTestCase(problem.getId());

        workspaceService.updateTestCase(created.getId(), problem.getId(), created.getPosition(), "3 4", "7");

        JavaTestCase reloaded = workspaceService.listTestCases(problem.getId()).get(0);
        assertEquals("3 4", reloaded.getStdin());
        assertEquals("7", reloaded.getExpectedOutput());
    }

    @Test
    void removingATestCaseDeletesOnlyThatOne() {
        Problem problem = uniqueFixtureProblem("TF-163-TESTCASES-REMOVE");
        JavaTestCase first = workspaceService.addTestCase(problem.getId());
        JavaTestCase second = workspaceService.addTestCase(problem.getId());

        workspaceService.removeTestCase(first.getId());

        List<JavaTestCase> remaining = workspaceService.listTestCases(problem.getId());
        assertEquals(1, remaining.size());
        assertEquals(second.getId(), remaining.get(0).getId());
    }

    @Test
    void matchesIsNullWithNoExpectedOutputAndABooleanOtherwise() {
        JavaTestCase noExpectation = new JavaTestCase(0, 1, 0, "1 2", "");
        JavaTestCase matching = new JavaTestCase(0, 1, 0, "1 2", "3");
        JavaTestCase mismatching = new JavaTestCase(0, 1, 0, "1 2", "99");

        assertNull(noExpectation.matches("3"), "no expected output means no verdict, not a fabricated pass/fail");
        assertEquals(Boolean.TRUE, matching.matches(" 3 \n"), "whitespace around actual output is stripped before comparing");
        assertEquals(Boolean.FALSE, mismatching.matches("3"));
    }

    @Test
    void runningARealTestCaseAgainstACompiledSolutionReportsPassOrFail() {
        Problem problem = uniqueFixtureProblem("TF-163-TESTCASES-RUN");
        String source = "import java.util.Scanner;\n"
                + "public class Solution {\n"
                + "    public static void main(String[] args) {\n"
                + "        Scanner scanner = new Scanner(System.in);\n"
                + "        int a = scanner.nextInt();\n"
                + "        int b = scanner.nextInt();\n"
                + "        System.out.println(a + b);\n"
                + "    }\n"
                + "}\n";

        try (CompileOutcome compiled = workspaceService.compile(source, "Solution")) {
            assertTrue(compiled.success());

            JavaTestCase passing = new JavaTestCase(0, problem.getId(), 0, "3 4", "7");
            RunResult passingResult = workspaceService.runTestCase(compiled, passing, RunLimits.defaults(), null);
            assertEquals(Boolean.TRUE, passing.matches(passingResult.stdout()));

            JavaTestCase failing = new JavaTestCase(0, problem.getId(), 1, "3 4", "99");
            RunResult failingResult = workspaceService.runTestCase(compiled, failing, RunLimits.defaults(), null);
            assertEquals(Boolean.FALSE, failing.matches(failingResult.stdout()));
        }
    }

    // ---- Configurable timeout (#163) ---------------------------------------------------------------

    @Test
    void runTimeoutSecondsDefaultsToRunLimitsDefaultAndRoundTripsThroughThePreferenceStore() {
        assertEquals(RunLimits.defaults().timeoutSeconds(), UserProgress.DEFAULT_JAVA_RUN_TIMEOUT_SECONDS,
                "the preference default must match the fixed default it's replacing");

        workspaceService.setRunTimeoutSeconds(30);
        assertEquals(30, workspaceService.getRunTimeoutSeconds());

        workspaceService.setRunTimeoutSeconds(10);
        assertEquals(10, workspaceService.getRunTimeoutSeconds());

        workspaceService.setRunTimeoutSeconds(UserProgress.DEFAULT_JAVA_RUN_TIMEOUT_SECONDS);
    }

    @Test
    void currentRunLimitsUsesThePreferredTimeoutButKeepsTheDefaultMemoryAndOutputCaps() {
        workspaceService.setRunTimeoutSeconds(45);

        RunLimits limits = workspaceService.currentRunLimits();

        assertEquals(45, limits.timeoutSeconds());
        assertEquals(RunLimits.defaults().memoryLimitMb(), limits.memoryLimitMb());
        assertEquals(RunLimits.defaults().maxOutputBytes(), limits.maxOutputBytes());

        workspaceService.setRunTimeoutSeconds(UserProgress.DEFAULT_JAVA_RUN_TIMEOUT_SECONDS);
    }
}
