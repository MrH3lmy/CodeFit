import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, rmSync } from "node:fs";
import { resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
const desktop = resolve(fileURLToPath(new URL("..", import.meta.url)));
const root = resolve(desktop, "..");
const jdk = process.env.JAVA_HOME;
if (!jdk) throw new Error("Set JAVA_HOME to a Java 21 JDK for this platform.");
const executable = (name) =>
  join(jdk, "bin", name + (process.platform === "win32" ? ".exe" : ""));
function run(command, args, cwd = root) {
  const result = spawnSync(command, args, {
    cwd,
    stdio: "inherit",
    shell: false,
  });
  if (result.status !== 0)
    throw new Error(`${command} failed (${result.status})`);
}
const version = spawnSync(executable("java"), ["-version"], {
  encoding: "utf8",
});
if (version.status !== 0 || !/version "21[.\"]/.test(version.stderr))
  throw new Error("Java 21 is required.");
const maven =
  process.env.CODEFIT_MAVEN ||
  (process.platform === "win32" ? "mvn.cmd" : "mvn");
// CODEFIT_MAVEN may be an executable wrapper supplying local repository/proxy settings; never a shell string.
run(maven, [
  "-B",
  "-DskipTests",
  "compile",
  "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies",
  "-DincludeScope=runtime",
  "-DoutputDirectory=" + join(root, "target", "desktop-dependencies"),
]);
const destination = join(desktop, "java");
rmSync(destination, { recursive: true, force: true });
mkdirSync(join(destination, "engine"), { recursive: true });
cpSync(
  join(root, "target", "classes"),
  join(destination, "engine", "classes"),
  { recursive: true },
);
cpSync(
  join(root, "target", "desktop-dependencies"),
  join(destination, "engine", "lib"),
  { recursive: true },
);
// Full engine dependencies stay intact for the spike; JavaFX classes are never initialized here.
run(executable("jlink"), [
  "--add-modules",
  "java.base,java.sql,java.desktop,java.logging,java.naming,java.management,java.prefs,java.xml,jdk.unsupported,jdk.crypto.ec",
  "--strip-debug",
  "--no-header-files",
  "--no-man-pages",
  "--output",
  join(destination, "runtime"),
]);
for (const name of ["NOTICE", "LICENSE", "release"])
  if (existsSync(join(jdk, name)))
    cpSync(join(jdk, name), join(destination, "runtime", "SOURCE-JDK-" + name));
console.log(
  "Prepared platform Java 21 runtime and existing engine in desktop/java.",
);
