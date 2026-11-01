import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, delimiter } from "node:path";
let compiled: string | undefined;
export function seedGraphs(directory: string, mode = "history") {
  const jdk = process.env.JAVA_HOME;
  if (!jdk) throw Error("JAVA_HOME required for test fixture");
  const engine = [
    resolve("java/engine/classes"),
    resolve("java/engine/lib/*"),
  ].join(delimiter);
  if (!compiled) {
    compiled = mkdtempSync(join(tmpdir(), "graph-fixture-"));
    execFileSync(
      join(jdk, "bin/javac"),
      [
        "--release",
        "21",
        "-cp",
        engine,
        "-d",
        compiled,
        resolve("../src/test/java/com/codefit/desktop/GraphFixture.java"),
      ],
      { timeout: 30000, stdio: "pipe" },
    );
    process.on(
      "exit",
      () => compiled && rmSync(compiled, { recursive: true, force: true }),
    );
  }
  execFileSync(
    join(jdk, "bin/java"),
    [
      "-cp",
      [compiled, engine].join(delimiter),
      "com.codefit.desktop.GraphFixture",
      directory,
      mode,
    ],
    { timeout: 30000, stdio: "pipe" },
  );
}
