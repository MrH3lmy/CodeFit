# CodeFit

CodeFit is a JavaFX desktop application for tracking fitness information. The application entry point is `com.codefit.CodeFitApplication`, which initializes the database configuration and launches the JavaFX dashboard.

## Requirements

- **JDK 21** is required. The Maven compiler configuration targets Java 21, so make sure both `JAVA_HOME` and your `PATH` point to a Java 21 installation.
- Maven is required for the recommended development workflow.

You can verify your local Java version with:

```bash
java -version
```

## Development

Use the JavaFX Maven plugin to launch the app during development:

```bash
mvn javafx:run
```

This is the recommended launch command because the project depends on JavaFX modules (`javafx-controls` and `javafx-fxml`) and configures the JavaFX plugin with the application main class.

## Running packaged artifacts

Running a compiled JAR directly with:

```bash
java -jar target/codefit-1.0.0-SNAPSHOT.jar
```

may fail because JavaFX runtime modules are not bundled with the JDK. If you run outside Maven, you must provide the JavaFX runtime modules on the module path/classpath yourself, or create a distributable package that includes them.

## Packaging guidance

For a distributable desktop application, use JavaFX-aware packaging tooling instead of relying on a plain `java -jar` workflow. Common options include:

- `jlink` to build a custom runtime image containing the required Java and JavaFX modules.
- `jpackage` to create native installers or application images from a configured runtime image.
- A Maven plugin configuration that integrates JavaFX packaging, runtime-image creation, or native packaging for your target platforms.

When adding packaging, ensure the generated artifact includes the JavaFX runtime modules required by the dependencies declared in `pom.xml` and launches `com.codefit.CodeFitApplication`.
