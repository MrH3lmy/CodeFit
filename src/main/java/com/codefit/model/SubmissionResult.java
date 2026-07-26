package com.codefit.model;

/**
 * The verdict of a single {@link ProblemAttempt} submission, matching the online-judge/workbook
 * verdict codes used by the Junior Training Sheet: {@code AC} accepted, {@code ACX} accepted after
 * one or more prior failed submissions on the same problem, {@code CS} compile/syntax error,
 * {@code WA} wrong answer, {@code TLE} time limit exceeded, {@code RTE} runtime error, {@code MLE}
 * memory limit exceeded.
 */
public enum SubmissionResult {
    AC,
    ACX,
    CS,
    WA,
    TLE,
    RTE,
    MLE
}
