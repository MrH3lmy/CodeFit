import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, delimiter } from "node:path";
let compiled: string | undefined;
export function seedAnalytics(directory: string, mode = "history") {
  const jdk = process.env.JAVA_HOME;
  if (!jdk) throw Error("JAVA_HOME required for test fixture");
  const engine = [
    resolve("java/engine/classes"),
    resolve("java/engine/lib/*"),
  ].join(delimiter);
  if (!compiled) {
    compiled = mkdtempSync(join(tmpdir(), "analytics-fixture-"));
    execFileSync(
      join(jdk, "bin/javac"),
      [
        "--release",
        "21",
        "-cp",
        engine,
        "-d",
        compiled,
        resolve("../src/test/java/com/codefit/desktop/AnalyticsFixture.java"),
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
      "com.codefit.desktop.AnalyticsFixture",
      directory,
      mode,
    ],
    { timeout: 30000, stdio: "pipe" },
  );
}
export function databaseHashes(file: string) {
  return JSON.parse(
    execFileSync(
      "python3",
      [
        "-c",
        `import sqlite3,json,sys,hashlib
c=sqlite3.connect('file:'+sys.argv[1]+'?mode=ro',uri=True)
tables=[r[0] for r in c.execute("select name from sqlite_master where type='table' order by name")]
print(json.dumps({t:sorted(hashlib.sha256(repr(r).encode()).hexdigest() for r in c.execute('select * from "'+t+'"')) for t in tables},sort_keys=True))`,
        file,
      ],
      { encoding: "utf8", maxBuffer: 8 * 1024 * 1024 },
    ),
  );
}
