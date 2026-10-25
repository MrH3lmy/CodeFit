import { execFileSync } from "node:child_process";
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
  readFileSync,
  writeFileSync,
  rmSync,
  mkdirSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { createLearnerFixture } from "../learner-fixture";
let app: ElectronApplication, dir: string;
const files = new Map<string, Buffer>();
async function launch() {
  return electron.launch({
    chromiumSandbox: true,
    executablePath:
      process.env.CODEFIT_DESKTOP_EXECUTABLE ||
      (electronPath as unknown as string),
    args: process.env.CODEFIT_DESKTOP_EXECUTABLE ? [] : [resolve(".")],
    cwd: dir,
    env: {
      ...process.env,
      XDG_CONFIG_HOME: dir,
      JAVA_HOME: "/missing",
      JAVA_TOOL_OPTIONS: "-invalid",
      CLASSPATH: "invalid",
    },
  });
}
test.beforeEach(async () => {
  dir = mkdtempSync(join(tmpdir(), "recorded-mission-e2e-"));
  files.clear();
  writeFileSync(join(dir, "codefit.db"), "PRIVATE-SENTINEL");
  app = await launch();
});
test.afterEach(async () => {
  await app?.close().catch(() => {});
  for (const [file, bytes] of files) expect(readFileSync(file)).toEqual(bytes);
  expect(readFileSync(join(dir, "codefit.db"), "utf8")).toBe(
    "PRIVATE-SENTINEL",
  );
  rmSync(dir, { recursive: true, force: true });
});
async function connect(p: Page, mode = "recorded") {
  const path = join(dir, `${mode}-${files.size}.db`);
  createLearnerFixture(path, `mission-${mode}`);
  files.set(path, readFileSync(path));
  await app.evaluate(({ dialog }, path) => {
    dialog.showOpenDialog = async () => ({
      canceled: false,
      filePaths: [path],
    });
    dialog.showMessageBox = async () => ({
      response: 1,
      checkboxChecked: false,
    });
  }, path);
  await p
    .getByRole("button", { name: /Open (preview|learner) profile/ })
    .click();
  await p
    .getByRole("button", {
      name: /Choose learner profile|Capture again/,
      exact: true,
    })
    .click();
  await expect(
    p.getByRole("button", { name: "Capture again", exact: true }),
  ).toBeVisible();
  await p.getByRole("button", { name: "Close dialog", exact: true }).click();
}
async function missions(p: Page) {
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Mission Console/ })
    .click();
}
async function capture(p: Page, name: string) {
  if (!process.env.CODEFIT_CAPTURE_MISSION_SCREENSHOTS) return;
  const out = resolve("../docs/architecture/gate2-missions/screenshots");
  mkdirSync(out, { recursive: true });
  await p.evaluate(() => window.scrollTo({ top: 0, behavior: "instant" }));
  await p.mouse.move(0, 0);
  await p.screenshot({
    path: join(out, `${name}.png`),
    fullPage: true,
    animations: "disabled",
  });
}

test("connected history uses Java records, accessible inspection and four real theme/width compositions", async () => {
  const p = await app.firstWindow();
  await connect(p);
  await missions(p);
  await expect(p.locator(".mission-record")).toHaveCount(3);
  await expect(p.locator(".provenance")).toContainText("LEARNER SNAPSHOT");
  await expect(
    p.getByRole("button", { name: "Open / resume unavailable" }),
  ).toBeDisabled();
  const reply = await p.evaluate(() => window.codefit.readLearnerMissions());
  expect(reply.ok).toBe(true);
  if (!reply.ok) throw Error(reply.code);
  expect(reply.result.currentEligibility).toBe("NOT_EVALUATED");
  expect(reply.result.actionsEnabled).toBe(false);
  expect(JSON.stringify(reply)).not.toMatch(
    /PRIVATE-|java\.general\.|diagnostics|answerKey/,
  );
  await p.locator(".mission-record").last().focus();
  await p.keyboard.press("Enter");
  await expect(
    p.getByRole("complementary", { name: "Recorded submission details" }),
  ).toContainText("mission-submission-1");
  expect(
    await p
      .locator(".mission-record")
      .last()
      .evaluate((el) => getComputedStyle(el).outlineStyle),
  ).not.toBe("none");
  await p.getByLabel("Filter recorded submissions").selectOption("FINALIZED");
  await expect(p.locator(".mission-record")).toHaveCount(2);
  await p.getByLabel("Filter recorded submissions").selectOption("all");
  await p.emulateMedia({ reducedMotion: "reduce" });
  for (const width of [1280, 760]) {
    await app.evaluate(
      ({ BrowserWindow }, w) =>
        BrowserWindow.getAllWindows()[0].setContentSize(w, 900),
      width,
    );
    for (const theme of ["dark", "light"]) {
      if ((await p.locator("html").getAttribute("data-theme")) !== theme)
        await p
          .getByRole("button", { name: `Switch to ${theme} theme` })
          .click();
      await expect
        .poll(() =>
          p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
        )
        .toBe(true);
      await capture(p, `missions-${width}-${theme}`);
    }
  }
  expect(await p.evaluate(() => window.codefit.readLearnerMissions())).toEqual(
    reply,
  );
});

for (const mode of ["empty", "corrupt", "missing", "conflicting", "withdrawn"])
  test(`${mode} records remain honest without prototype fallback`, async () => {
    const p = await app.firstWindow();
    await connect(p, mode);
    await missions(p);
    if (mode === "empty")
      await expect(
        p.getByRole("heading", {
          name: "No saved submissions in this snapshot",
        }),
      ).toBeVisible();
    else if (mode === "corrupt")
      await expect(
        p.getByRole("heading", {
          name: "History cannot be interpreted safely",
        }),
      ).toBeVisible();
    else if (mode === "withdrawn") {
      await expect(
        p.locator(".mission-record").filter({ hasText: "WITHDRAWN" }),
      ).toHaveCount(1);
      await p.locator(".mission-record").last().click();
      await expect(p.locator(".mission-record-inspector")).toContainText(
        "no active result asserted",
      );
    } else {
      await expect(
        p.getByText("Some catalog metadata is missing or incompatible.", {
          exact: false,
        }),
      ).toBeVisible();
      await expect(p.locator(".published-quest-grid article")).toHaveCount(0);
    }
    await expect(
      p.getByRole("button", { name: "Evaluate / review unavailable" }),
    ).toBeDisabled();
    await expect(p.locator(".mission-record-hero")).toBeVisible();
  });

test("unauthorized frames and parameters cannot access mission history", async () => {
  const results = await app.evaluate(async ({ ipcMain, BrowserWindow }) => {
    const sender = BrowserWindow.getAllWindows()[0].webContents;
    const h = (ipcMain as any)._invokeHandlers.get("codefit:learner:missions");
    return Promise.all([
      h({ sender, senderFrame: null }),
      h({ sender, senderFrame: sender.mainFrame }, "/private"),
      h({
        sender: { isDestroyed: () => false },
        senderFrame: sender.mainFrame,
      }),
    ]);
  });
  expect(results).toEqual(Array(3).fill({ ok: false, code: "UNAVAILABLE" }));
});

test("profile replacement fences withheld mission responses and reload/restart require consent", async () => {
  const p = await app.firstWindow();
  await connect(p);
  await missions(p);
  await expect(p.locator(".mission-record")).toHaveCount(3);
  await p.locator(".mission-record").last().click();
  await expect(p.locator(".mission-record-inspector")).toContainText(
    "mission-submission-1",
  );
  await app.evaluate(({ ipcMain }) => {
    const original = (ipcMain as any)._invokeHandlers.get(
      "codefit:learner:missions",
    );
    let once = true;
    ipcMain.removeHandler("codefit:learner:missions");
    ipcMain.handle("codefit:learner:missions", async (event, ...args) => {
      const r = await original(event, ...args);
      if (once) {
        once = false;
        await new Promise<void>((resolve) => {
          (globalThis as any).releaseMission = resolve;
        });
      }
      return r;
    });
  });
  await p.getByRole("button", { name: "Refresh frozen view" }).click();
  await expect(
    p.getByRole("button", { name: "Cancel mission read and disconnect" }),
  ).toBeVisible();
  await expect
    .poll(() => app.evaluate(() => typeof (globalThis as any).releaseMission))
    .toBe("function");
  await connect(p, "empty");
  await app.evaluate(() => (globalThis as any).releaseMission());
  await expect(
    p.getByRole("heading", { name: "No saved submissions in this snapshot" }),
  ).toBeVisible();
  await expect(p.locator(".mission-record")).toHaveCount(0);
  await p.reload();
  await missions(p);
  await expect(
    p.getByRole("region", { name: "Recorded Mission Console" }),
  ).toHaveCount(0);
  await expect(p.locator(".provenance")).toContainText("DESIGN PREVIEW");
  await app.close();
  app = await launch();
  const next = await app.firstWindow();
  await missions(next);
  await expect(next.locator(".provenance")).toContainText("DESIGN PREVIEW");
});

test("Java crash never fabricates available missions", async () => {
  const p = await app.firstWindow();
  await connect(p);
  await missions(p);
  await expect(p.locator(".mission-record")).toHaveCount(3);
  await p.locator(".mission-record").last().click();
  // Kill only the consented reader discovered by the host test, never the real application database.
  const readers = execFileSync("ps", ["-eo", "pid=,ppid=,args="], {
    encoding: "utf8",
  })
    .split("\n")
    .map((line) => line.trim().match(/^(\d+)\s+(\d+)\s+(.*)$/))
    .filter(
      (m) =>
        m &&
        Number(m[2]) === app.process().pid &&
        m[3].includes("com.codefit.desktop.LearnerProfileMain"),
    );
  expect(readers).toHaveLength(1);
  process.kill(Number(readers[0]![1]), "SIGKILL");
  await p.getByRole("button", { name: "Refresh frozen view" }).click();
  await expect(
    p.getByRole("heading", { name: "Mission history unavailable" }),
  ).toBeVisible();
  await expect(p.locator(".mission-record")).toHaveCount(0);
});

test("cancelling a pending mission read disconnects and suppresses late content", async () => {
  const p = await app.firstWindow();
  await connect(p);
  await missions(p);
  await expect(p.locator(".mission-record")).toHaveCount(3);
  await p.locator(".mission-record").last().click();
  await app.evaluate(({ ipcMain }) => {
    const original = (ipcMain as any)._invokeHandlers.get(
      "codefit:learner:missions",
    );
    ipcMain.removeHandler("codefit:learner:missions");
    ipcMain.handle("codefit:learner:missions", async (event, ...args) => {
      const result = await original(event, ...args);
      await new Promise<void>((resolve) => {
        (globalThis as any).cancelledMissionRelease = resolve;
      });
      return result;
    });
  });
  await p.getByRole("button", { name: "Refresh frozen view" }).click();
  await expect
    .poll(() =>
      app.evaluate(() => typeof (globalThis as any).cancelledMissionRelease),
    )
    .toBe("function");
  await p
    .getByRole("button", { name: "Cancel mission read and disconnect" })
    .click();
  await app.evaluate(() => (globalThis as any).cancelledMissionRelease());
  await expect(
    p.getByRole("region", { name: "Recorded Mission Console" }),
  ).toHaveCount(0);
  await expect(p.locator(".provenance")).toContainText("DESIGN PREVIEW");
});

test("inspector selection belongs to the active filter and remains stable on refresh", async () => {
  const p = await app.firstWindow();
  await connect(p);
  await missions(p);
  const rows = p.locator(".mission-record");
  const inspector = p.locator(".mission-record-inspector");
  const filter = p.getByLabel("Filter recorded submissions");
  await expect(rows).toHaveCount(3);
  await rows.last().focus();
  await p.keyboard.press("Enter");
  await expect(inspector).toContainText("mission-submission-1");
  await filter.focus();
  await filter.selectOption("unfinished");
  await expect(rows).toHaveCount(1);
  await expect(inspector).toContainText("mission-submission-3");
  await expect(inspector).not.toContainText("mission-submission-1");
  await expect(rows.first()).toHaveAttribute("aria-pressed", "true");
  await expect(filter).toBeFocused();
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].setContentSize(1280, 900),
  );
  await capture(p, "missions-filter-unfinished-1280-dark");
  await filter.selectOption("all");
  await expect(inspector).toContainText("mission-submission-3");
  await rows.last().click();
  await filter.selectOption("FINALIZED");
  await expect(rows).toHaveCount(2);
  await expect(inspector).toContainText("mission-submission-1");
  await p.getByRole("button", { name: "Refresh frozen view" }).click();
  await expect(filter).toHaveValue("FINALIZED");
  await expect(rows).toHaveCount(2);
  await expect(inspector).toContainText("mission-submission-1");
  await expect(rows.last()).toHaveAttribute("aria-pressed", "true");
  await filter.selectOption("unfinished");
  await p.getByRole("button", { name: "Refresh frozen view" }).click();
  await expect(filter).toHaveValue("unfinished");
  await expect(rows).toHaveCount(1);
  await expect(inspector).toContainText("mission-submission-3");
});

test("a filter with zero matches clears the inspector through refresh and filter return", async () => {
  const p = await app.firstWindow();
  await connect(p, "large");
  await missions(p);
  const rows = p.locator(".mission-record");
  const inspector = p.locator(".mission-record-inspector");
  const filter = p.getByLabel("Filter recorded submissions");
  await expect(rows).toHaveCount(20);
  await rows.last().click();
  await expect(inspector).toContainText("mission-submission-6");
  await filter.selectOption("FINALIZED");
  await expect(rows).toHaveCount(0);
  await expect(
    p.getByRole("heading", { name: "No submissions match this filter" }),
  ).toBeVisible();
  await expect(inspector).toContainText("No visible submission selected");
  await expect(inspector).not.toContainText("mission-submission-");
  await expect(
    p.getByText("History is limited to the latest 20 saved submissions", {
      exact: false,
    }),
  ).toBeVisible();
  await p.getByRole("button", { name: "Refresh frozen view" }).click();
  await expect(filter).toHaveValue("FINALIZED");
  await expect(inspector).toContainText("No visible submission selected");
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].setContentSize(1280, 900),
  );
  await capture(p, "missions-filter-empty-1280-dark");
  await filter.selectOption("all");
  await expect(rows).toHaveCount(20);
  await expect(inspector).toContainText("mission-submission-25");
  await expect(rows.first()).toHaveAttribute("aria-pressed", "true");
});
