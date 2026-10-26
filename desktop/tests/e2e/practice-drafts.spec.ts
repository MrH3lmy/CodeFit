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
  writeFileSync,
  readFileSync,
  mkdirSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
test.use({ trace: "off", screenshot: "off", video: "off" });
let app: ElectronApplication, dir: string;
const synthetic =
  "// Synthetic UI verification fixture\npublic class PracticeExample {\n    public static int total(int left, int right) {\n        return left + right;\n    }\n}\n";
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
  dir = mkdtempSync(join(tmpdir(), "practice-draft-e2e-"));
  writeFileSync(join(dir, "codefit.db"), "REAL_PROFILE_UNCHANGED");
  app = await launch();
});
test.afterEach(async () => {
  app?.process().kill("SIGKILL");
  await app?.close().catch(() => {});
  expect(readFileSync(join(dir, "codefit.db"), "utf8")).toBe(
    "REAL_PROFILE_UNCHANGED",
  );
  rmSync(dir, { recursive: true, force: true });
});
async function connect() {
  const p = await app.firstWindow();
  await p
    .getByRole("button", { name: /Practice Workspace Authorized Java/ })
    .click();
  await p
    .getByRole("button", { name: "Connect disposable practice profile" })
    .click();
  await expect(p.getByText("Live Java owner", { exact: true })).toBeVisible();
  return p;
}
async function start() {
  const p = await connect();
  await p.getByRole("button", { name: "Review start" }).first().click();
  await p.getByRole("button", { name: "Confirm start" }).click();
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toBeVisible();
  await expect(
    p.getByRole("button", {
      name: "Close instructions and acknowledge receipt",
    }),
  ).toBeEnabled();
  return p;
}
async function saved(p: Page) {
  await expect(p.locator(".draft-status")).toHaveText(
    /^Saved · acknowledged revision \d+$/,
  );
}
async function replace(p: Page, text = synthetic) {
  await p.getByRole("textbox", { name: "Java source editor" }).fill(text);
  await saved(p);
}
test("real autosave, acknowledged close, navigation and restart resume exactly the same draft", async () => {
  let p = await start();
  const presentation = await p
    .locator("dd")
    .filter({ hasText: /desktop-practice-/ })
    .innerText();
  await replace(p);
  await p
    .getByRole("textbox", { name: "Working reflection" })
    .fill("Synthetic reflection; no capability claim.");
  await saved(p);
  await p
    .getByRole("button", { name: "Close instructions and acknowledge receipt" })
    .click();
  await p
    .getByRole("button", { name: "Resume saved practice" })
    .first()
    .click();
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveValue(synthetic);
  await app.close();
  app = await launch();
  p = await connect();
  await p
    .getByRole("button", { name: "Resume saved practice" })
    .first()
    .click();
  await expect(p.getByText(presentation, { exact: true })).toBeVisible();
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveValue(synthetic);
  await expect(
    p.getByRole("textbox", { name: "Working reflection" }),
  ).toHaveValue("Synthetic reflection; no capability claim.");
  await saved(p);
  await p.getByRole("button", { name: "Back to Mission Console" }).click();
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveCount(0);
});
test("delayed save acknowledgement never overwrites newer edits; writes are serialized", async () => {
  const p = await start();
  await app.evaluate(({ ipcMain }) => {
    const original = (ipcMain as any)._invokeHandlers.get(
      "codefit:practice:draft-save",
    );
    ipcMain.removeHandler("codefit:practice:draft-save");
    (globalThis as any).draftCalls = 0;
    (globalThis as any).draftOutstanding = 0;
    (globalThis as any).draftMax = 0;
    ipcMain.handle("codefit:practice:draft-save", async (...args: any[]) => {
      (globalThis as any).draftCalls++;
      (globalThis as any).draftOutstanding++;
      (globalThis as any).draftMax = Math.max(
        (globalThis as any).draftMax,
        (globalThis as any).draftOutstanding,
      );
      const result = await original(...args);
      await new Promise((r) => setTimeout(r, 900));
      (globalThis as any).draftOutstanding--;
      return result;
    });
  });
  const editor = p.getByRole("textbox", { name: "Java source editor" });
  await editor.fill("class FirstSynthetic {}");
  await expect(p.locator(".draft-status")).toContainText("Saving");
  await editor.fill(synthetic);
  await expect(editor).toHaveValue(synthetic);
  await saved(p);
  expect(await app.evaluate(() => (globalThis as any).draftMax)).toBe(1);
  expect(await app.evaluate(() => (globalThis as any).draftCalls)).toBe(2);
});
test("failed save blocks navigation without discarding edits and reconciles a lost committed reply", async () => {
  const p = await start();
  await app.evaluate(({ ipcMain }) => {
    const original = (ipcMain as any)._invokeHandlers.get(
      "codefit:practice:draft-save",
    );
    let remaining = 2;
    ipcMain.removeHandler("codefit:practice:draft-save");
    ipcMain.handle("codefit:practice:draft-save", async (...args: any[]) => {
      const result = await original(...args);
      if (remaining-- > 0) {
        return { ok: false, code: "TIMEOUT" };
      }
      return result;
    });
  });
  await p.getByRole("textbox", { name: "Java source editor" }).fill(synthetic);
  await expect(p.locator(".draft-status")).toContainText("Recovery required");
  p.once("dialog", (d) => d.dismiss());
  await p.getByRole("button", { name: "Back to Mission Console" }).click();
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveValue(synthetic);
  await p.getByRole("button", { name: "Reconcile saved draft" }).click();
  await expect(p.getByRole("alert")).toContainText("Reconciliation complete");
  await p.getByText(/Inspect last known stored draft/).click();
  p.once("dialog", (d) => d.accept());
  await p.getByRole("button", { name: "Load stored draft" }).click();
  await saved(p);
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveValue(synthetic);
  await p
    .getByRole("textbox", { name: "Java source editor" })
    .fill("class SecondSynthetic {}");
  await expect(p.locator(".draft-status")).toContainText("Recovery required");
  await p.getByText(/Inspect last known stored draft/).click();
  await expect(
    p.getByRole("button", { name: "Keep my edits against this revision" }),
  ).toBeDisabled();
  await expect(
    p.getByRole("button", { name: "Load stored draft" }),
  ).toBeDisabled();
});
test("stale editor conflicts rather than overwriting an independently saved newer draft", async () => {
  const p = await start();
  const presentation = await p
    .locator("dd")
    .filter({ hasText: /desktop-practice-/ })
    .innerText();
  expect(
    await p.evaluate(async (id) => {
      const s = await window.codefit.connectPractice();
      if (!s.ok) return false;
      const d = await window.codefit.readPracticeDraft(
        id,
        s.profileId,
        s.sessionId,
      );
      if (!d.ok || d.method !== "practice.draft.read") return false;
      const r = await window.codefit.savePracticeDraft(
        {
          presentationId: id,
          expectedRevision: d.draft.revision,
          source: "class NewerSynthetic {}",
          reflection: "",
        },
        s.profileId,
        s.sessionId,
      );
      return (
        r.ok &&
        r.method === "practice.draft.save" &&
        r.saved.outcome === "SAVED"
      );
    }, presentation),
  ).toBe(true);
  await p.getByRole("textbox", { name: "Java source editor" }).fill(synthetic);
  await expect(p.locator(".draft-status")).toContainText("Conflict");
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveValue(synthetic);
  await p.getByRole("button", { name: "Reconcile saved draft" }).click();
  await p.getByText(/Inspect last known stored draft/).click();
  await expect(p.locator(".draft-conflict pre")).toHaveText(
    "class NewerSynthetic {}",
  );
});
test("native indentation, undo, keyboard focus and explicit save work offline", async () => {
  const p = await start(),
    editor = p.getByRole("textbox", { name: "Java source editor" });
  await editor.fill("class Synthetic {\n");
  await editor.press("End");
  await editor.press("Tab");
  await expect(editor).toHaveValue("class Synthetic {\n    ");
  await editor.press("Control+z");
  await expect(editor).toHaveValue("class Synthetic {\n");
  await editor.press("Shift+Tab");
  await expect(editor).not.toBeFocused();
  await editor.focus();
  await editor.press("Control+s");
  await saved(p);
  expect(await p.locator(".java-keyword").count()).toBeGreaterThan(0);
});
test("extra arguments and uncommitted presentation IDs cannot read or save source", async () => {
  const p = await connect();
  expect(
    await p.evaluate(async () => {
      const s = await window.codefit.connectPractice();
      if (!s.ok) return false;
      const r = await window.codefit.readPracticeDraft(
        "desktop-practice-00000000-0000-4000-8000-000000000000",
        s.profileId,
        s.sessionId,
      );
      return !r.ok;
    }),
  ).toBe(true);
  expect(
    await app.evaluate(async ({ ipcMain, BrowserWindow }) => {
      const w = BrowserWindow.getAllWindows()[0],
        fn = (ipcMain as any)._invokeHandlers.get(
          "codefit:practice:draft-save",
        );
      const r = await fn(
        { sender: w.webContents, senderFrame: w.webContents.mainFrame },
        {},
        "profile",
        "session",
        "extra",
      );
      return !r.ok;
    }),
  ).toBe(true);
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveCount(0);
});
test("packaged synthetic editor screenshots in both themes and widths", async () => {
  const p = await start();
  await replace(p);
  await p.getByText("Authorized instructions", { exact: true }).click();
  const folder = resolve("../docs/architecture/gate2-drafts/screenshots");
  if (process.env.CODEFIT_CAPTURE_DRAFT_SCREENSHOTS)
    mkdirSync(folder, { recursive: true });
  for (const width of [1280, 760])
    for (const theme of ["dark", "light"]) {
      await app.evaluate(
        ({ BrowserWindow }, width) =>
          BrowserWindow.getAllWindows()[0].setSize(width, 1000),
        width,
      );
      const current = await p.locator("html").getAttribute("data-theme");
      if (current !== theme)
        await p
          .getByRole("button", { name: /Switch to (light|dark) theme/ })
          .click();
      await p
        .getByRole("heading", { name: "Your working solution" })
        .evaluate((e) =>
          e.scrollIntoView({ block: "start", behavior: "instant" }),
        );
      await expect(
        p.getByRole("textbox", { name: "Java source editor" }),
      ).toBeVisible();
      await expect(p.locator("[data-protected-content]")).not.toBeVisible();
      expect(
        await p.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
      if (process.env.CODEFIT_CAPTURE_DRAFT_SCREENSHOTS)
        await p.screenshot({
          path: join(folder, `draft-${width}-${theme}.png`),
        });
    }
});
test("saved draft survives renderer reload and force quit without another exposure", async () => {
  let p = await start();
  await replace(p);
  await p
    .getByRole("button", { name: "Close instructions and acknowledge receipt" })
    .click();
  await p.reload();
  p = await app.firstWindow();
  await p
    .getByRole("button", { name: /Practice Workspace Authorized Java/ })
    .click();
  await p
    .getByRole("button", { name: "Connect disposable practice profile" })
    .click();
  await p
    .getByRole("button", { name: "Resume saved practice" })
    .first()
    .click();
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveValue(synthetic);
  app.process().kill("SIGKILL");
  await app.close().catch(() => {});
  app = await launch();
  p = await connect();
  await p
    .getByRole("button", { name: "Resume saved practice" })
    .first()
    .click();
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveValue(synthetic);
  expect(
    await p.evaluate(async () => {
      const s = await window.codefit.connectPractice();
      return s.ok ? s.presentations.length : -1;
    }),
  ).toBe(1);
});
test("dirty close and renderer reload are blocked even before the main dirty notification", async () => {
  const p = await start();
  await app.evaluate(({ ipcMain, dialog }) => {
    ipcMain.removeHandler("codefit:practice:draft-save");
    ipcMain.handle("codefit:practice:draft-save", () => ({
      ok: false,
      code: "UNAVAILABLE",
    }));
    (globalThis as any).discardPrompts = 0;
    dialog.showMessageBoxSync = () => {
      (globalThis as any).discardPrompts++;
      return 0;
    };
  });
  await p.getByRole("textbox", { name: "Java source editor" }).fill(synthetic);
  await expect(p.locator(".draft-status")).toContainText("Recovery required");
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].close(),
  );
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveValue(synthetic);
  expect(await app.evaluate(() => (globalThis as any).discardPrompts)).toBe(1);
  // Simulate the ordering window before the boolean IPC reaches main; beforeunload still guards.
  await p.evaluate(() => window.codefit.setPracticeDraftDirty(false));
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].webContents.reload(),
  );
  await expect
    .poll(() => app.evaluate(() => (globalThis as any).discardPrompts))
    .toBe(2);
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveValue(synthetic);
});
