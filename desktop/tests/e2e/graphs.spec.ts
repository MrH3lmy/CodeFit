import { _electron as electron, expect, test } from "@playwright/test";
import electronPath from "electron";
import { mkdtempSync, rmSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
test.use({ trace: "off", screenshot: "off", video: "off" });
test("published graph inspection and explicit same-graph selection", async () => {
  const dir = mkdtempSync(join(tmpdir(), "graph-ui-"));
  const app = await electron.launch({
    chromiumSandbox: true,
    executablePath:
      process.env.CODEFIT_DESKTOP_EXECUTABLE ||
      (electronPath as unknown as string),
    args: process.env.CODEFIT_DESKTOP_EXECUTABLE ? [] : [resolve(".")],
    cwd: dir,
    env: { ...process.env, XDG_CONFIG_HOME: dir, JAVA_HOME: "/missing" },
  });
  try {
    const p = await app.firstWindow();
    await p
      .getByRole("button", {
        name: /Engineering Graphs Inspect exact published versions/,
      })
      .click();
    await p
      .getByRole("button", { name: "Connect disposable practice profile" })
      .click();
    await p
      .getByRole("button", { name: "Read graph catalog / recover" })
      .click();
    await expect(
      p.getByRole("region", { name: "Graph definitions" }),
    ).toBeVisible();
    await expect(
      p.getByRole("heading", { name: "Selected planning graph" }),
    ).toBeVisible();
    await p.getByRole("button", { name: "Choose this exact graph" }).click();
    await p
      .getByRole("button", { name: "Confirm exact graph selection" })
      .click();
    await expect(
      p.getByRole("status").filter({ hasText: "Saved receipt:" }),
    ).toContainText("UNCHANGED");
    const out = resolve(
      "../docs/architecture/gate2-graph-selection/screenshots",
    );
    mkdirSync(out, { recursive: true });
    for (const theme of ["dark", "light"]) {
      await p.evaluate((t) => {
        document.documentElement.dataset.theme = t;
        localStorage.setItem("codefit.theme", t);
      }, theme);
      for (const width of [1280, 760]) {
        await p.setViewportSize({ width, height: 1000 });
        await p.screenshot({
          path: join(out, `graphs-${theme}-${width}.png`),
          fullPage: true,
        });
      }
    }
    await p.getByRole("button", { name: "Disconnect graph session" }).click();
    await expect(
      p.getByRole("region", { name: "Graph definitions" }),
    ).toHaveCount(0);
  } finally {
    app.process().kill("SIGKILL");
    await app.close().catch(() => {});
    rmSync(dir, { recursive: true, force: true });
  }
});
