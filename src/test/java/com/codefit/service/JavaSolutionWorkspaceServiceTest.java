package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.JavaSolutionDraft;
import com.codefit.model.Problem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers draft persistence (#163): surviving across "restarts" (a fresh service instance, standing in
 * for a real app restart the same way {@code ProblemSolvingSessionServiceTest} does), autosave
 * overwriting in place rather than accumulating rows, and the default template for a problem with no
 * saved draft yet.
 */
class JavaSolutionWorkspaceServiceTest {

    private final ProblemService problemService = new ProblemService();
    private final JavaSolutionWorkspaceService workspaceService = new JavaSolutionWorkspaceService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    private Problem fixtureProblem(String code) {
        return problemService.findOrCreateProblem("TEST-FIXTURE-PLATFORM", code, "Java Runner Fixture " + code,
                null, "General", null, List.of());
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
}
