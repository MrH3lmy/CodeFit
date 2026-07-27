package com.codefit.model;

import java.time.LocalDateTime;

/**
 * A learner's saved Java solution-in-progress for one {@link Problem} (#163): the source code, the
 * main class name (configurable — the class/template generation the issue asks for), a custom
 * standard-input value, and an optional expected-output value for local test-case comparison. A
 * problem has at most one draft ({@code UNIQUE(problem_id)}); autosaving simply overwrites it in
 * place, the same one-row-per-problem shape as {@link ProblemProgress}/{@link ProblemSolvingSession}.
 */
public class JavaSolutionDraft {
    private long id;
    private long problemId;
    private String mainClassName;
    private String sourceCode;
    private String stdin;
    private String expectedOutput;
    private LocalDateTime updatedAt;

    public JavaSolutionDraft(long id, long problemId, String mainClassName, String sourceCode, String stdin,
                             String expectedOutput, LocalDateTime updatedAt) {
        this.id = id;
        this.problemId = problemId;
        this.mainClassName = mainClassName == null || mainClassName.isBlank() ? "Solution" : mainClassName.strip();
        this.sourceCode = sourceCode;
        this.stdin = stdin;
        this.expectedOutput = expectedOutput;
        this.updatedAt = updatedAt;
    }

    /** A fresh draft pre-filled with a minimal runnable template for a problem with no saved draft yet. */
    public static JavaSolutionDraft template(long problemId) {
        String className = "Solution";
        String source = "public class " + className + " {\n"
                + "    public static void main(String[] args) throws Exception {\n"
                + "        // TODO: read input, solve, print output\n"
                + "    }\n"
                + "}\n";
        return new JavaSolutionDraft(0, problemId, className, source, "", "", null);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getProblemId() { return problemId; }
    public String getMainClassName() { return mainClassName; }
    public void setMainClassName(String mainClassName) {
        this.mainClassName = mainClassName == null || mainClassName.isBlank() ? "Solution" : mainClassName.strip();
    }
    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
    public String getStdin() { return stdin; }
    public void setStdin(String stdin) { this.stdin = stdin; }
    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
