import {
  _electron as electron,
  expect,
  test,
  type ElectronApplication,
  type Page,
} from "@playwright/test";
import electronPath from "electron";
import { execFileSync } from "node:child_process";
import {
  mkdtempSync,
  readFileSync,
  writeFileSync,
  rmSync,
  mkdirSync,
  readdirSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { createLearnerFixture } from "../learner-fixture";

let app: ElectronApplication, dir: string, source: string, before: Buffer;
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
      JAVA_HOME: "/missing-jdk",
      JAVA_TOOL_OPTIONS: "-invalid",
      CLASSPATH: "invalid",
    },
  });
}
test.beforeEach(async () => {
  dir = mkdtempSync(join(tmpdir(), "codefit-learner-e2e-"));
  writeFileSync(join(dir, "codefit.db"), "learner sentinel");
  source = join(dir, "selected", "profile.db");
  createLearnerFixture(source);
  before = readFileSync(source);
  app = await launch();
});
test.afterEach(async () => {
  await app?.close().catch(() => {});
  expect(readFileSync(source)).toEqual(before);
  expect(readFileSync(join(dir, "codefit.db"), "utf8")).toBe(
    "learner sentinel",
  );
  rmSync(dir, { recursive: true, force: true });
});
async function dialogs(file = source, consent = true, canceled = false) {
  await app.evaluate(
    ({ dialog }, input) => {
      dialog.showOpenDialog = async () => ({
        canceled: input.canceled,
        filePaths: input.canceled ? [] : [input.file],
      });
      dialog.showMessageBox = async (...args: any[]) => {
        const options = args[args.length - 1];
        (globalThis as any).__consentDetail = options.detail;
        (globalThis as any).__consentOptions = {
          defaultId: options.defaultId,
          cancelId: options.cancelId,
          buttons: options.buttons,
        };
        return { response: input.consent ? 1 : 0, checkboxChecked: false };
      };
    },
    { file, consent, canceled },
  );
}
async function profileDialog(p: Page) {
  await p
    .getByRole("button", { name: /Open (preview|learner) profile/ })
    .click();
}
async function connect(p: Page) {
  await dialogs();
  await profileDialog(p);
  await p
    .getByRole("button", { name: "Choose learner profile", exact: true })
    .click();
  await expect(
    p.getByRole("dialog").getByText("Ada Learner", { exact: true }),
  ).toBeVisible();
}
async function failureCapture(p: Page, name: string) {
  if (!process.env.CODEFIT_CAPTURE_PROFILE_SCREENSHOTS) return;
  const folder = resolve("../docs/architecture/gate2-profile/screenshots");
  mkdirSync(folder, { recursive: true });
  await p.screenshot({
    path: join(folder, name + ".png"),
    fullPage: true,
    animations: "disabled",
  });
}
function learnerPids() {
  return execFileSync("ps", ["-eo", "pid=,ppid=,args="], { encoding: "utf8" })
    .split("\n")
    .map((x) => x.trim().match(/^(\d+)\s+(\d+)\s+(.+)$/))
    .filter(
      (x) =>
        x &&
        +x[2] === app.process().pid &&
        x[3].includes("com.codefit.desktop.LearnerProfileMain"),
    )
    .map((x) => +x![1]);
}

test("explicit consent connects authentic recorded data offline with dark/light narrow layouts", async () => {
  const p = await app.firstWindow();
  await p.context().setOffline(true);
  await connect(p);
  const detail = await app.evaluate(() => (globalThis as any).__consentDetail);
  expect(detail).toContain(JSON.stringify(source));
  expect(detail).toContain("sensitive local profile content");
  expect(detail).toContain("shared-memory");
  expect(
    await app.evaluate(() => (globalThis as any).__consentOptions),
  ).toEqual({
    defaultId: 0,
    cancelId: 0,
    buttons: ["Cancel", "Connect read-only snapshot"],
  });
  const result = await p.evaluate(() => window.codefit.readLearnerProfile());
  expect(result.ok).toBe(true);
  if (!result.ok) throw new Error(result.code);
  expect(result.result.progression).toEqual({
    status: "available",
    xp: 750,
    level: 8,
  });
  expect(JSON.stringify(result)).not.toContain("PRIVATE-");
  expect(JSON.stringify(result)).not.toContain(source);
  await p.keyboard.press("Escape");
  await expect(p.locator(".learner-progression")).toContainText("750");
  await expect(p.locator(".learner-activity-metrics")).toContainText(
    "Recorded reviews4",
  );
  await expect(p.locator(".learner-activity-metrics")).toContainText(
    "Due scheduled cards3",
  );
  await expect(p.locator(".learner-availability")).toContainText(
    "Skill masteryUnavailable",
  );
  await expect(
    p.getByRole("heading", { name: "Your active mission" }),
  ).toHaveCount(0);
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
      expect(
        await p.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      if (process.env.CODEFIT_CAPTURE_PROFILE_SCREENSHOTS) {
        const folder = resolve(
          "../docs/architecture/gate2-profile/screenshots",
        );
        mkdirSync(folder, { recursive: true });
        await p.screenshot({
          path: join(folder, `${theme}-${width}.png`),
          fullPage: true,
          animations: "disabled",
        });
      }
    }
  }
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Ascension Tree/ })
    .click();
  await expect(p.locator(".provenance")).toContainText("Recorded learner data");
  await expect(
    p.getByRole("region", { name: "Authoritative skill tree" }),
  ).toBeVisible();
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Mission Console/ })
    .click();
  await expect(p.locator(".provenance")).toContainText("Recorded learner data");
  await expect(
    p.getByRole("region", { name: "Recorded Mission Console" }),
  ).toBeVisible();
  await expect(
    p.getByRole("heading", { name: "No saved submissions in this snapshot" }),
  ).toBeVisible();
  await expect(
    p.getByRole("button", { name: "Evaluate / review unavailable" }),
  ).toBeDisabled();
});
test("declining native confirmation never opens a learner reader", async () => {
  const p = await app.firstWindow();
  await dialogs(source, false);
  await profileDialog(p);
  await p
    .getByRole("button", { name: "Choose learner profile", exact: true })
    .click();
  await expect(p.getByRole("dialog")).toContainText(
    "Profile connection cancelled",
  );
  await expect(p.getByRole("dialog")).toContainText(
    "Connecting creates a private full database backup",
  );
  await expect(p.getByRole("dialog")).not.toContainText("backup is stored");
  await failureCapture(p, "consent-declined");
  expect(learnerPids()).toEqual([]);
  expect(await p.evaluate(() => window.codefit.readLearnerProfile())).toEqual({
    ok: false,
    code: "UNAVAILABLE",
  });
});
test("unauthorized frames and renderer-supplied paths cannot open or disconnect a profile", async () => {
  const p = await app.firstWindow();
  await connect(p);
  const failures = await app.evaluate(async ({ ipcMain, BrowserWindow }) => {
    const sender = BrowserWindow.getAllWindows()[0].webContents;
    return Promise.all(
      [
        "codefit:learner:choose",
        "codefit:learner:read",
        "codefit:learner:skills",
      ].flatMap((name) => {
        const h = (ipcMain as any)._invokeHandlers.get(name);
        return [
          h({ sender, senderFrame: null }),
          h({ sender, senderFrame: sender.mainFrame }, "/arbitrary/profile"),
        ];
      }),
    );
  });
  expect(failures).toEqual(
    Array.from({ length: 6 }, () => ({ ok: false, code: "UNAVAILABLE" })),
  );
  await app.evaluate(async ({ ipcMain, BrowserWindow }) => {
    const sender = BrowserWindow.getAllWindows()[0].webContents;
    await (ipcMain as any)._invokeHandlers.get("codefit:learner:disconnect")({
      sender,
      senderFrame: null,
    });
  });
  expect((await p.evaluate(() => window.codefit.readLearnerProfile())).ok).toBe(
    true,
  );
});
test("cancelled profile switch disconnects the old identity and requires new consent", async () => {
  const p = await app.firstWindow();
  await connect(p);
  await dialogs(source, true, true);
  await p.getByRole("button", { name: "Capture again", exact: true }).click();
  await expect(p.getByRole("dialog")).toContainText("Not connected");
  expect(await p.evaluate(() => window.codefit.readLearnerProfile())).toEqual({
    ok: false,
    code: "UNAVAILABLE",
  });
  expect(learnerPids()).toEqual([]);
});
test("reload and full restart never silently reconnect a learner profile and preserve backups", async () => {
  let p = await app.firstWindow();
  await connect(p);
  await p.keyboard.press("Escape");
  await p.reload();
  await expect(p.locator(".provenance")).toContainText(
    "Your learner profile is not connected",
  );
  expect(await p.evaluate(() => window.codefit.readLearnerProfile())).toEqual({
    ok: false,
    code: "UNAVAILABLE",
  });
  await app.close();
  app = await launch();
  p = await app.firstWindow();
  expect(await p.evaluate(() => window.codefit.readLearnerProfile())).toEqual({
    ok: false,
    code: "UNAVAILABLE",
  });
  await connect(p);
  const files = readdirSync(
    join(dir, "CodeFit-Gate0", "learner-snapshots-v1"),
  ).filter((x) => x.endsWith(".sqlite"));
  expect(files).toHaveLength(2);
});
test("future schema is rejected and Java failure stays redacted with historical UI clearly frozen", async () => {
  const bad = join(dir, "future.db");
  createLearnerFixture(bad, "future");
  const bytes = readFileSync(bad),
    p = await app.firstWindow();
  await dialogs(bad);
  await profileDialog(p);
  await p
    .getByRole("button", { name: "Choose learner profile", exact: true })
    .click();
  await expect(p.getByRole("dialog")).toContainText(
    "No migration was performed",
  );
  await failureCapture(p, "incompatible-schema");
  expect(readFileSync(bad)).toEqual(bytes);
  await dialogs();
  await p
    .getByRole("button", { name: "Choose learner profile", exact: true })
    .click();
  await expect(
    p.getByRole("dialog").getByText("Ada Learner", { exact: true }),
  ).toBeVisible();
  process.kill(learnerPids()[0], "SIGKILL");
  await expect.poll(() => learnerPids().length).toBe(0);
  const result = await p.evaluate(() => window.codefit.readLearnerProfile());
  expect(result.ok).toBe(false);
  expect(JSON.stringify(result)).not.toContain(source);
  await expect(p.getByRole("dialog")).toContainText("not a live connection");
});
test("cancellation while consent is pending suppresses a late confirmation", async () => {
  const p = await app.firstWindow();
  await dialogs();
  await app.evaluate(({ dialog }) => {
    dialog.showMessageBox = async () =>
      new Promise((resolve) => {
        (globalThis as any).__releaseConsent = () =>
          resolve({ response: 1, checkboxChecked: false });
      });
  });
  await profileDialog(p);
  await p
    .getByRole("button", { name: "Choose learner profile", exact: true })
    .click();
  await expect
    .poll(() => app.evaluate(() => typeof (globalThis as any).__releaseConsent))
    .toBe("function");
  await p
    .getByRole("dialog")
    .getByRole("button", { name: "Cancel connection", exact: true })
    .click();
  await app.evaluate(() => (globalThis as any).__releaseConsent());
  await p.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  );
  await expect(p.getByRole("dialog")).toContainText("Not connected");
  await failureCapture(p, "cancelled-late-confirmation");
  expect(learnerPids()).toEqual([]);
  expect(await p.evaluate(() => window.codefit.readLearnerProfile())).toEqual({
    ok: false,
    code: "UNAVAILABLE",
  });
});
test("missing progress stays unavailable in the narrow connected view", async () => {
  const missing = join(dir, "missing.db");
  createLearnerFixture(missing, "missing");
  const bytes = readFileSync(missing);
  const p = await app.firstWindow();
  await dialogs(missing);
  await profileDialog(p);
  await p
    .getByRole("button", { name: "Choose learner profile", exact: true })
    .click();
  await expect(
    p.getByRole("dialog").getByText("Learner profile", { exact: true }),
  ).toBeVisible();
  await expect(p.getByRole("dialog").locator(".profile-metrics")).toContainText(
    "Unavailable",
  );
  await p.keyboard.press("Escape");
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].setContentSize(760, 900),
  );
  await expect(p.locator(".learner-progression")).toContainText("Unavailable");
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  expect(readFileSync(missing)).toEqual(bytes);
  await failureCapture(p, "missing-progress-760");
});
