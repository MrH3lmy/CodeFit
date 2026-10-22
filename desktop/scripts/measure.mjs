import { _electron as electron } from "@playwright/test";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const executablePath = process.env.CODEFIT_DESKTOP_EXECUTABLE;
if (!executablePath)
  throw new Error(
    "Set CODEFIT_DESKTOP_EXECUTABLE to the built local artifact.",
  );
const samples = [];
for (let run = 0; run < 5; run++) {
  const directory = mkdtempSync(join(tmpdir(), "codefit-gate0-measure-"));
  let app;
  try {
    const start = performance.now();
    app = await electron.launch({
      chromiumSandbox: true,
      executablePath,
      cwd: directory,
      env: {
        ...process.env,
        XDG_CONFIG_HOME: directory,
        JAVA_HOME: "/no-system-jdk",
      },
    });
    const page = await app.firstWindow();
    await page
      .getByRole("navigation")
      .getByRole("button", { name: /Java Library/ })
      .click();
    await page
      .getByRole("status")
      .filter({ hasText: "8 deck descriptions received from Java" })
      .waitFor();
    const readyMs = Math.round(performance.now() - start);
    const rows = execFileSync("ps", ["-eo", "pid=,ppid=,rss=,args="], {
      encoding: "utf8",
    })
      .split("\n")
      .map((line) => line.trim().match(/^(\d+)\s+(\d+)\s+(\d+)\s+(.+)$/))
      .filter(Boolean)
      .map((m) => ({
        pid: +m[1],
        parent: +m[2],
        rssKiB: +m[3],
        java: m[4].includes("com.codefit.desktop.DesktopApplicationMain"),
      }));
    const owned = new Set([app.process().pid]);
    let previous = 0;
    while (previous !== owned.size) {
      previous = owned.size;
      rows.forEach((row) => {
        if (owned.has(row.parent)) owned.add(row.pid);
      });
    }
    const processes = rows.filter((row) => owned.has(row.pid));
    samples.push({
      readyMs,
      processCount: processes.length,
      totalRssKiB: processes.reduce((sum, row) => sum + row.rssKiB, 0),
      javaRssKiB: processes
        .filter((row) => row.java)
        .reduce((sum, row) => sum + row.rssKiB, 0),
    });
  } finally {
    if (app) await app.close();
    rmSync(directory, { recursive: true, force: true });
  }
}
const report = {
  platform: process.platform,
  arch: process.arch,
  measuredAt: new Date().toISOString(),
  method:
    "Five fresh app profiles/process trees, packaged artifact, first real service result. Warm OS file cache; automation and shared-host load affect timing. RSS sums count shared pages repeatedly; not PSS or peak.",
  samples,
};
writeFileSync(
  resolve("../docs/architecture/gate2/measurements.json"),
  JSON.stringify(report, null, 2) + "\n",
);
console.log(JSON.stringify(report, null, 2));
