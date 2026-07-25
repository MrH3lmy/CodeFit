package com.codefit.service;

import java.util.List;

/**
 * Defense-in-depth textual scan rejecting Java sources that reach for capabilities outside the
 * scoped "bounded fill-in-the-blank" exercise model (predefined templates the app controls, with a
 * learner-supplied method body/expression substituted in). This is deliberately a blunt substring
 * denylist, not a parser — {@link JavaSandboxRunner}'s process isolation, restricted classpath, and
 * hard timeout are the real controls; this only catches obviously out-of-scope snippets before a
 * process is even spawned, and can be bypassed by a determined author (e.g. string concatenation or
 * reflection tricks). It exists to keep accidental or careless template/attempt content from trying
 * to escape the sandbox, not to contain a hostile one.
 */
final class JavaSnippetGuard {

    private static final List<String> BANNED_SUBSTRINGS = List.of(
            "ProcessBuilder",
            "Runtime.getRuntime",
            "ProcessHandle",
            ".exec(",
            "java.lang.reflect",
            "setAccessible",
            "Class.forName",
            "System.exit",
            "sun.misc",
            "jdk.internal",
            "java.net.",
            "javax.net.",
            "Socket(",
            "ServerSocket",
            "DatagramSocket",
            "URLConnection",
            "HttpClient",
            "InetAddress",
            "java.io.File",
            "java.nio.file",
            "FileInputStream",
            "FileOutputStream",
            "FileWriter",
            "FileReader",
            "RandomAccessFile"
    );

    private JavaSnippetGuard() {
    }

    /** Returns a human-readable reason the snippet was rejected, or {@code null} if it looks safe. */
    static String violation(String javaSource) {
        if (javaSource == null) {
            return null;
        }
        for (String banned : BANNED_SUBSTRINGS) {
            if (javaSource.contains(banned)) {
                return "Snippet uses a disallowed construct: " + banned;
            }
        }
        return null;
    }
}
