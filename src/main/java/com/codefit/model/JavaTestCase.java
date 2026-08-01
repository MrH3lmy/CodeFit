package com.codefit.model;

/**
 * One named local test case for a problem's Java draft (#163's "run multiple local test cases") — a
 * standard-input value and an optional expected-output value, ordered by {@code position} among
 * however many test cases a learner has added for this problem. Independent of
 * {@link JavaSolutionDraft}'s own single stdin/expected-output "quick run" pair.
 */
public class JavaTestCase {
    private long id;
    private long problemId;
    private int position;
    private String stdin;
    private String expectedOutput;

    public JavaTestCase(long id, long problemId, int position, String stdin, String expectedOutput) {
        this.id = id;
        this.problemId = problemId;
        this.position = position;
        this.stdin = stdin;
        this.expectedOutput = expectedOutput;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getProblemId() { return problemId; }
    public int getPosition() { return position; }
    public String getStdin() { return stdin; }
    public void setStdin(String stdin) { this.stdin = stdin; }
    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }

    /** {@code true}/{@code false} once run against real output, or {@code null} if no expected
     *  output was given — a test case can be used to simply observe output, not just assert it. */
    public Boolean matches(String actualStdout) {
        if (expectedOutput == null || expectedOutput.isBlank()) {
            return null;
        }
        String actual = actualStdout == null ? "" : actualStdout.strip();
        return actual.equals(expectedOutput.strip());
    }
}
