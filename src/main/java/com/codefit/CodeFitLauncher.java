package com.codefit;

/**
 * Plain Java entry point for classpath-based launches.
 *
 * <p>The JDK launcher treats a main class that extends JavaFX {@code Application} specially and expects
 * JavaFX to be bundled as runtime modules. Keeping this tiny, toolkit-independent entry point lets both
 * Maven and development scripts use the project's ordinary dependency classpath before handing control
 * to the real application.</p>
 */
public final class CodeFitLauncher {
    private CodeFitLauncher() {
    }

    public static void main(String[] args) {
        CodeFitApplication.main(args);
    }
}
