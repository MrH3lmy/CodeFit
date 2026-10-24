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
  readdirSync,
  mkdirSync,
  existsSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { createLearnerFixture } from "../learner-fixture";
let app: ElectronApplication, dir: string;
async function launch(enabled = true) {
  return electron.launch({
    chromiumSandbox: true,
    executablePath:
      process.env.CODEFIT_DESKTOP_EXECUTABLE ||
      (electronPath as unknown as string),
    args: [
      ...(process.env.CODEFIT_DESKTOP_EXECUTABLE ? [] : [resolve(".")]),
      ...(enabled ? ["--enable-curriculum-preview-local"] : []),
    ],
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
  dir = mkdtempSync(join(tmpdir(), "codefit-atlas-e2e-"));
  writeFileSync(join(dir, "codefit.db"), "PRIVATE-LEARNER-SENTINEL");
  app = await launch();
});
test.afterEach(async () => {
  await app?.close().catch(() => {});
  expect(readFileSync(join(dir, "codefit.db"), "utf8")).toBe(
    "PRIVATE-LEARNER-SENTINEL",
  );
  rmSync(dir, { recursive: true, force: true });
});
async function open(p: Page) {
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Curriculum Preview/ })
    .click();
}
async function loaded(p: Page) {
  await expect(
    p.getByRole("region", { name: "Selected draft details" }),
  ).toBeVisible();
}
async function capture(p: Page, name: string) {
  if (!process.env.CODEFIT_CAPTURE_CURRICULUM_SCREENSHOTS) return;
  const folder = resolve("../docs/architecture/gate2-curriculum/screenshots");
  mkdirSync(folder, { recursive: true });
  await p.evaluate(() => window.scrollTo({ top: 0, behavior: "instant" }));
  await p.mouse.move(0, 0);
  await p.screenshot({
    path: join(folder, `${name}.png`),
    fullPage: true,
    animations: "disabled",
  });
}
test("real Java metadata, four responsive theme compositions, keyboard inspector and honest unavailable stages", async () => {
  const p = await app.firstWindow();
  await open(p);
  await loaded(p);
  await expect(p.locator(".atlas-card")).toHaveCount(4);
  const result = await p.evaluate(() => window.codefit.readCurriculumPreview());
  expect(result.ok).toBe(true);
  if (!result.ok) throw Error(result.code);
  for (const skill of result.result.preview.skills) {
    await p.locator(".atlas-card").filter({ hasText: skill.id }).click();
    await expect(p.locator(".atlas-inspector h2")).toHaveText(skill.title);
    await expect(
      p.getByRole("button", { name: "Mission execution unavailable" }),
    ).toBeDisabled();
    await p.getByRole("tab", { name: /Predict/ }).focus();
    await p.keyboard.press("ArrowRight");
    await expect(p.getByRole("tab", { name: /Debug/ })).toBeFocused();
    await expect(p.getByRole("tabpanel")).toContainText(
      skill.stages.find((s) => s.stage === "DEBUG")!.id,
    );
    expect(
      await p
        .getByRole("tab", { name: /Debug/ })
        .evaluate((el) => getComputedStyle(el).outlineStyle),
    ).not.toBe("none");
    await p.keyboard.press("End");
    await expect(p.getByRole("tab", { name: /Defend/ })).toBeFocused();
  }
  await p.getByLabel("Find a draft skill").fill("no-match");
  await expect(p.getByText("No matching draft definitions.")).toBeVisible();
  await p.getByRole("button", { name: "Clear search" }).click();
  await p.locator(".atlas-card").first().click();
  await p
    .getByText("Open questions before integration", { exact: true })
    .click();
  await expect(p.locator(".atlas-open-questions li")).toHaveCount(10);
  await p
    .getByText("Open questions before integration", { exact: true })
    .click();
  await p.emulateMedia({ reducedMotion: "reduce" });
  for (const width of [1280, 760]) {
    await app.evaluate(
      ({ BrowserWindow }, width) =>
        BrowserWindow.getAllWindows()[0].setContentSize(width, 900),
      width,
    );
    for (const theme of ["dark", "light"] as const) {
      if ((await p.locator("html").getAttribute("data-theme")) !== theme)
        await p
          .getByRole("button", { name: `Switch to ${theme} theme` })
          .click();
      await expect
        .poll(() =>
          p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
        )
        .toBe(true);
      await capture(p, `curriculum-${width}-${theme}`);
    }
  }
  expect(JSON.stringify(result)).not.toMatch(
    /private_checkpoint|PRIVATE-|solution|answerKey/,
  );
  expect(existsSync(join(dir, "CodeFit-Gate0/experimental-library-v1"))).toBe(
    false,
  );
  expect(existsSync(join(dir, "CodeFit-Gate0/learner-snapshots-v1"))).toBe(
    false,
  );
});

test("distribution gate defaults off and cannot be enabled by renderer arguments", async () => {
  await app.close();
  app = await launch(false);
  const p = await app.firstWindow();
  await open(p);
  await expect(
    p.getByRole("heading", { name: "Local review access only" }),
  ).toBeVisible();
  expect(
    await p.evaluate(() => window.codefit.readCurriculumPreview()),
  ).toEqual({ ok: false, code: "RELEASE_DISABLED" });
  await expect(p.locator(".atlas-card")).toHaveCount(0);
  expect(existsSync(join(dir, "CodeFit-Gate0/experimental-library-v1"))).toBe(
    false,
  );
});

test("owning-frame authorization and zero arguments enforced on actual registered handlers", async () => {
  const results = await app.evaluate(async ({ ipcMain, BrowserWindow }) => {
    const sender = BrowserWindow.getAllWindows()[0].webContents;
    const read = (ipcMain as any)._invokeHandlers.get(
      "codefit:curriculum:read",
    );
    const cancel = (ipcMain as any)._invokeHandlers.get(
      "codefit:curriculum:cancel",
    );
    const failures = await Promise.all([
      read({ sender, senderFrame: null }),
      read({
        sender: { isDestroyed: () => false },
        senderFrame: sender.mainFrame,
      }),
      read({ sender, senderFrame: { url: "file:///untrusted", ...{} } }),
      read({ sender, senderFrame: sender.mainFrame }, "/private"),
    ]);
    await cancel({ sender, senderFrame: null });
    await cancel({ sender, senderFrame: sender.mainFrame }, "arbitrary");
    return failures;
  });
  expect(results).toEqual(Array(4).fill({ ok: false, code: "UNAVAILABLE" }));
  const p = await app.firstWindow();
  await open(p);
  await loaded(p);
});

test("renderer reload, cancellation and application restart read the same immutable catalog", async () => {
  let p = await app.firstWindow();
  await open(p);
  await loaded(p);
  const before = await p.evaluate(() => window.codefit.readCurriculumPreview());
  const cancelled = await p.evaluate(async () => {
    const result = window.codefit.readCurriculumPreview();
    await window.codefit.cancelCurriculumPreview();
    return result;
  });
  expect(cancelled).toEqual({ ok: false, code: "CANCELLED" });
  await p.reload();
  await open(p);
  await loaded(p);
  expect(
    await p.evaluate(() => window.codefit.readCurriculumPreview()),
  ).toEqual(before);
  await app.close();
  app = await launch();
  p = await app.firstWindow();
  await open(p);
  await loaded(p);
  expect(
    await p.evaluate(() => window.codefit.readCurriculumPreview()),
  ).toEqual(before);
});

test("connected learner evidence and database bytes are unchanged by draft inspection", async () => {
  const source = join(dir, "actual.db");
  createLearnerFixture(source, "ascension-supported");
  const original = readFileSync(source);
  await app.evaluate(({ dialog }, source) => {
    dialog.showOpenDialog = async () => ({
      canceled: false,
      filePaths: [source],
    });
    dialog.showMessageBox = async () => ({
      response: 1,
      checkboxChecked: false,
    });
  }, source);
  const p = await app.firstWindow();
  await p.getByRole("button", { name: "Open preview profile" }).click();
  await p
    .getByRole("button", { name: "Choose learner profile", exact: true })
    .click();
  await expect(
    p.getByRole("button", { name: "Capture again", exact: true }),
  ).toBeVisible();
  await p.getByRole("button", { name: "Close dialog", exact: true }).click();
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Ascension Tree/ })
    .click();
  await expect(
    p.getByRole("region", { name: "Authoritative skill tree" }),
  ).toBeVisible();
  const before = await p.evaluate(() => window.codefit.readLearnerSkills());
  await open(p);
  await loaded(p);
  await expect(p.locator(".provenance")).toContainText(
    "Separate from learner evidence",
  );
  await p.locator(".atlas-card").last().click();
  await p.getByRole("button", { name: "Recheck catalog" }).click();
  await loaded(p);
  const after = await p.evaluate(() => window.codefit.readLearnerSkills());
  expect(after).toEqual(before);
  expect(JSON.stringify(after)).not.toContain("java.general.");
  expect(readFileSync(source)).toEqual(original);
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Ascension Tree/ })
    .click();
  await expect(
    p.getByRole("region", { name: "Authoritative skill tree" }),
  ).toBeVisible();
  await expect(p.locator(".atlas-card")).toHaveCount(0);
  await p.evaluate(() => window.codefit.disconnectLearnerProfile());
});

test("cancelled UI reads ignore late success and malformed replies never become fallback content", async () => {
  await app.evaluate(({ ipcMain }) => {
    const original = (ipcMain as any)._invokeHandlers.get(
      "codefit:curriculum:read",
    );
    let first = true;
    ipcMain.removeHandler("codefit:curriculum:read");
    ipcMain.handle("codefit:curriculum:read", async (event, ...args) => {
      const result = await original(event, ...args);
      if (first) {
        first = false;
        await new Promise<void>((r) => {
          (globalThis as any).releaseAtlasRead = r;
        });
      }
      return result;
    });
  });
  const p = await app.firstWindow();
  await open(p);
  await expect(
    p.getByRole("button", { name: "Cancel preview read" }),
  ).toBeVisible();
  await expect
    .poll(() => app.evaluate(() => typeof (globalThis as any).releaseAtlasRead))
    .toBe("function");
  await p.getByRole("button", { name: "Cancel preview read" }).click();
  await app.evaluate(() => (globalThis as any).releaseAtlasRead());
  await expect(p.getByRole("alert")).toContainText("cancelled");
  await expect(p.locator(".atlas-card")).toHaveCount(0);
  await p.getByRole("button", { name: "Retry preview read" }).click();
  await loaded(p);
  await app.evaluate(({ ipcMain }) => {
    ipcMain.removeHandler("codefit:curriculum:read");
    ipcMain.handle("codefit:curriculum:read", () => ({
      ok: true,
      result: { independentApprovals: 100, prompt: "PRIVATE-MATERIAL" },
    }));
  });
  await p.getByRole("button", { name: "Recheck catalog" }).click();
  await expect(p.getByRole("alert")).toContainText("could not be verified");
  await expect(p.locator(".atlas-card")).toHaveCount(0);
  await expect(p.getByText("PRIVATE-MATERIAL")).toHaveCount(0);
});
