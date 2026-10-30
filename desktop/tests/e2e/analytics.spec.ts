import {
  _electron as electron,
  expect,
  test,
  type ElectronApplication,
  type Page,
} from "@playwright/test";
import electronPath from "electron";
import {
  mkdtempSync,
  rmSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { seedAnalytics, databaseHashes } from "../analytics-fixture";
import { createLearnerFixture } from "../learner-fixture";
test.use({ trace: "off", screenshot: "off", video: "off" });
let app: ElectronApplication, dir: string, file: string;
async function launch() {
  return electron.launch({
    chromiumSandbox: true,
    executablePath:
      process.env.CODEFIT_DESKTOP_EXECUTABLE ||
      (electronPath as unknown as string),
    args: process.env.CODEFIT_DESKTOP_EXECUTABLE ? [] : [resolve(".")],
    cwd: dir,
    env: { ...process.env, XDG_CONFIG_HOME: dir, JAVA_HOME: "/missing" },
  });
}
test.beforeEach(async () => {
  dir = mkdtempSync(join(tmpdir(), "analytics-ui-"));
  writeFileSync(join(dir, "codefit.db"), "REAL_PROFILE_UNCHANGED");
  app = await launch();
  const base = await app.evaluate(({ app }) => app.getPath("userData"));
  file = join(base, "experimental-practice-v1/codefit.db");
});
test.afterEach(async () => {
  app?.process().kill("SIGKILL");
  await app?.close().catch(() => {});
  expect(readFileSync(join(dir, "codefit.db"), "utf8")).toBe(
    "REAL_PROFILE_UNCHANGED",
  );
  rmSync(dir, { recursive: true, force: true });
});
async function open() {
  const p = await app.firstWindow();
  await p
    .getByRole("button", { name: /Skill Analytics Explore recorded evidence/ })
    .click();
  await p
    .getByRole("button", { name: "Connect analytics practice profile" })
    .click();
  await expect(
    p.getByRole("button", { name: "Refresh analytics" }),
  ).toBeEnabled();
  return p;
}
async function read(p: Page) {
  await p.getByRole("button", { name: "Refresh analytics" }).click();
  await expect(
    p.getByRole("region", { name: "Ordinal skill development" }),
  ).toBeVisible();
}
async function history() {
  seedAnalytics(file.replace(/\/codefit.db$/, ""));
  const p = await open();
  await read(p);
  await p
    .getByLabel("Skill", { exact: true })
    .selectOption("java.concurrency.atomic-update");
  return p;
}
test("five real charts, corrections, comparison, filters and rendering never mutate the database", async () => {
  const p = await history();
  const before = databaseHashes(file);
  await expect(p.locator(".analytics-point")).toHaveCount(4);
  await expect(p.locator(".analytics-event")).toHaveCount(4);
  await expect(p.locator(".analytics-node")).toHaveCount(4);
  await expect(p.locator(".analytics-matrix [role=row]")).toHaveCount(5);
  await expect(
    p.getByRole("region", { name: "Historical interpretation comparison" }),
  ).toContainText("Saved interpretation");
  await expect(
    p.getByRole("region", { name: "Ordinal skill development" }),
  ).toContainText("evidence withdrawn");
  const earlier = p.getByLabel("Earlier interpretation", { exact: true });
  const later = p.getByLabel("Later interpretation", { exact: true });
  const originalLater = await later.inputValue();
  await later.selectOption(await earlier.inputValue());
  await expect(p.locator("#analytics-4")).toContainText("No compatible pair");
  await later.selectOption(originalLater);
  await expect(p.locator(".analytics-compare")).toBeVisible();
  await p.locator(".analytics-event").last().click();
  await expect(
    p.getByRole("region", { name: "Persisted evidence activity" }),
  ).toContainText("source event");
  await p.getByLabel("From (UTC)").fill("2099-01-01");
  await expect(p.locator(".analytics-point")).toHaveCount(0);
  await p.getByRole("button", { name: "Clear dates" }).click();
  await p.locator(".analytics-node").first().focus();
  await p.keyboard.press("ArrowRight");
  await expect(p.locator(".analytics-node").nth(1)).toBeFocused();
  await p.getByRole("button", { name: "Zoom in" }).click();
  await read(p);
  expect(databaseHashes(file)).toEqual(before);
  const result = await app.evaluate(async ({ ipcMain, BrowserWindow }) => {
    const w = BrowserWindow.getAllWindows()[0],
      fn = (ipcMain as any)._invokeHandlers.get("codefit:practice:analytics");
    return [
      await fn(
        { sender: w.webContents, senderFrame: null },
        { page: 0, snapshot: "" },
        "p",
        "s",
      ),
      await fn(
        { sender: w.webContents, senderFrame: w.webContents.mainFrame },
        { page: 0, snapshot: "" },
        "p",
        "s",
        "extra",
      ),
    ];
  });
  expect(result.every((r) => !r.ok)).toBe(true);
  await app.evaluate(({ ipcMain }) => {
    ipcMain.removeHandler("codefit:practice:analytics");
    ipcMain.handle("codefit:practice:analytics", () => ({
      ok: false,
      code: "PROFILE_INVALID",
    }));
  });
  await p.getByRole("button", { name: "Refresh analytics" }).click();
  await expect(p.locator(".analytics-chart")).toHaveCount(0);
  expect(databaseHashes(file)).toEqual(before);
});
test("empty, interrupted reads, disconnect and renderer restart never show stale history", async () => {
  const p = await open();
  await read(p);
  await expect(
    p.getByRole("region", { name: "Ordinal skill development" }),
  ).toContainText("No saved interpretations");
  const before = databaseHashes(file);
  await app.evaluate(({ ipcMain }) => {
    const fn = (ipcMain as any)._invokeHandlers.get(
      "codefit:practice:analytics",
    );
    ipcMain.removeHandler("codefit:practice:analytics");
    ipcMain.handle("codefit:practice:analytics", async (e, ...a) => {
      const r = await fn(e, ...a);
      await new Promise<void>(
        (resolve) => ((globalThis as any).releaseAnalytics = resolve),
      );
      return r;
    });
  });
  await p.getByRole("button", { name: "Refresh analytics" }).click();
  await expect
    .poll(() => app.evaluate(() => typeof (globalThis as any).releaseAnalytics))
    .toBe("function");
  await p.getByRole("button", { name: "Disconnect analytics" }).click();
  await p
    .getByRole("button", { name: "Connect analytics practice profile" })
    .click();
  await expect(
    p.getByRole("button", { name: "Refresh analytics" }),
  ).toBeEnabled();
  await app.evaluate(() => (globalThis as any).releaseAnalytics());
  await expect(p.locator(".analytics-chart")).toHaveCount(0);
  await p.reload();
  await p
    .getByRole("button", { name: /Skill Analytics Explore recorded evidence/ })
    .click();
  await expect(
    p.getByRole("button", { name: "Connect analytics practice profile" }),
  ).toBeVisible();
  await expect(p.locator(".analytics-chart")).toHaveCount(0);
  expect(databaseHashes(file)).toEqual(before);
});
test("switching the separate learner profile clears practice analytics without merging identities", async () => {
  const p = await history();
  const before = databaseHashes(file);
  const source = join(dir, "learner-switch.db");
  createLearnerFixture(source);
  const sourceBefore = readFileSync(source);
  await app.evaluate(({ dialog }, path) => {
    dialog.showOpenDialog = async () => ({
      canceled: false,
      filePaths: [path],
    });
    dialog.showMessageBox = async () => ({
      response: 1,
      checkboxChecked: false,
    });
  }, source);
  await p
    .getByRole("button", { name: /Open (preview|learner) profile/ })
    .click();
  await p
    .getByRole("button", { name: "Choose learner profile", exact: true })
    .click();
  await expect(
    p.getByRole("button", { name: "Capture again", exact: true }),
  ).toBeVisible();
  await p.getByRole("button", { name: "Close dialog", exact: true }).click();
  await expect(p.locator(".analytics-chart")).toHaveCount(0);
  await expect(
    p.getByRole("button", { name: "Connect analytics practice profile" }),
  ).toBeVisible();
  expect(readFileSync(source)).toEqual(sourceBefore);
  expect(databaseHashes(file)).toEqual(before);
});
test("packaged screenshots for all five charts in four theme and width combinations", async () => {
  const p = await history();
  await p.locator(".analytics-event").last().click();
  const folder = resolve(
    "../docs/architecture/gate2-skill-analytics/screenshots",
  );
  if (process.env.CODEFIT_CAPTURE_ANALYTICS_SCREENSHOTS)
    mkdirSync(folder, { recursive: true });
  for (const width of [1280, 760])
    for (const theme of ["dark", "light"]) {
      await app.evaluate(
        ({ BrowserWindow }, width) =>
          BrowserWindow.getAllWindows()[0].setSize(width, 1000),
        width,
      );
      if ((await p.locator("html").getAttribute("data-theme")) !== theme)
        await p
          .getByRole("button", { name: /Switch to (light|dark) theme/ })
          .click();
      for (let chart = 0; chart < 5; chart++) {
        const section = p.locator(`#analytics-${chart}`);
        await section.evaluate((e) =>
          e.scrollIntoView({ block: "start", behavior: "instant" }),
        );
        await expect(section).toBeVisible();
        expect(
          await p.evaluate(
            () => document.documentElement.scrollWidth <= window.innerWidth,
          ),
        ).toBe(true);
        if (process.env.CODEFIT_CAPTURE_ANALYTICS_SCREENSHOTS)
          await p.screenshot({
            path: join(folder, `chart-${chart + 1}-${width}-${theme}.png`),
          });
      }
    }
});
