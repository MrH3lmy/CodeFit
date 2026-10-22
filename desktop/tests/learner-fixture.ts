import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, delimiter } from "node:path";

let compiled: string | undefined;
/** Host-only fixture code; never copied into packaged Java/resources or exposed via IPC. */
export function createLearnerFixture(file: string, mode = "normal") {
  const engine = [
    resolve("java/engine/classes"),
    resolve("java/engine/lib/*"),
  ].join(delimiter);
  if (!compiled) {
    const jdk = process.env.JAVA_HOME;
    if (!jdk)
      throw new Error(
        "Set JAVA_HOME to the Java21 test JDK (fixture compilation only)",
      );
    compiled = mkdtempSync(join(tmpdir(), "codefit-fixture-classes-"));
    execFileSync(
      join(jdk, "bin", "javac"),
      [
        "--release",
        "21",
        "-cp",
        engine,
        "-d",
        compiled,
        resolve("../src/test/java/com/codefit/desktop/LearnerFixture.java"),
      ],
      { timeout: 30_000, stdio: "pipe" },
    );
    process.on("exit", () => {
      if (compiled) rmSync(compiled, { recursive: true, force: true });
    });
  }
  execFileSync(
    resolve("java/runtime/bin/java"),
    [
      "-cp",
      [compiled, engine].join(delimiter),
      "com.codefit.desktop.LearnerFixture",
      file,
      mode,
    ],
    { timeout: 30_000, stdio: "pipe" },
  );
}
