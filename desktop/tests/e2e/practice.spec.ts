import {
  _electron as electron,
  expect,
  test,
  type ElectronApplication,
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
// Protected instructions must not enter automatic failure traces/screenshots.
test.use({ trace: "off", screenshot: "off", video: "off" });
let app: ElectronApplication, dir: string;
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
  dir = mkdtempSync(join(tmpdir(), "practice-e2e-"));
  writeFileSync(join(dir, "codefit.db"), "REAL-PROFILE-SENTINEL");
  app = await launch();
});
test.afterEach(async () => {
  await app?.close().catch(() => {});
  expect(readFileSync(join(dir, "codefit.db"), "utf8")).toBe(
    "REAL-PROFILE-SENTINEL",
  );
  rmSync(dir, { recursive: true, force: true });
});
async function enter() {
  const p = await app.firstWindow();
  await p
    .getByRole("button", { name: /Practice Workspace Authorized Java/ })
    .click();
  await p
    .getByRole("button", { name: "Connect disposable practice profile" })
    .click();
  await expect(
    p.getByRole("button", { name: "Review start" }).first(),
  ).toBeEnabled();
  return p;
}
test("real authorized start, exact receipt, reload recovery and acknowledgement", async () => {
  let p = await enter();
  await expect(p.locator("[data-protected-content]")).toHaveCount(0);
  await p.getByRole("button", { name: "Review start" }).first().click();
  await p.getByRole("button", { name: "Confirm start" }).click();
  await expect(
    p.getByText("Exposure committed", { exact: true }),
  ).toBeVisible();
  const presentation = await p
    .locator("dd")
    .filter({ hasText: /desktop-practice-/ })
    .innerText();
  await expect(p.locator("[data-protected-content]")).not.toBeEmpty();
  await p.reload();
  await p
    .getByRole("button", { name: /Practice Workspace Authorized Java/ })
    .click();
  await p
    .getByRole("button", { name: "Connect disposable practice profile" })
    .click();
  await p.getByRole("button", { name: "Recover original start" }).click();
  await expect(p.getByText(presentation, { exact: true })).toBeVisible();
  await app.close();
  app = await launch();
  p = await app.firstWindow();
  await p
    .getByRole("button", { name: /Practice Workspace Authorized Java/ })
    .click();
  await p
    .getByRole("button", { name: "Connect disposable practice profile" })
    .click();
  await p.getByRole("button", { name: "Recover original start" }).click();
  await expect(p.getByText(presentation, { exact: true })).toBeVisible();
  await p
    .getByRole("button", { name: "Close instructions and acknowledge receipt" })
    .click();
  await expect(p.locator("[data-protected-content]")).toHaveCount(0);
  await expect(
    p.getByRole("button", { name: "Review start" }).first(),
  ).toBeEnabled();
});
test("pre-start UI uses actual Java candidates at both widths/themes without leaking instructions", async () => {
  const p = await enter();
  const destination = resolve(
    "../docs/architecture/gate2-practice/screenshots",
  );
  for (const width of [1280, 760]) {
    await app.evaluate(
      ({ BrowserWindow }, width) =>
        BrowserWindow.getAllWindows()[0].setContentSize(width, 900),
      width,
    );
    for (const theme of ["dark", "light"]) {
      await expect(p.locator("html")).toHaveAttribute(
        "data-theme",
        /dark|light/,
      );
      if ((await p.locator("html").getAttribute("data-theme")) !== theme)
        await p
          .getByRole("button", { name: `Switch to ${theme} theme` })
          .click();
      await expect(p.locator("html")).toHaveAttribute("data-theme", theme);
      await expect(
        p.getByRole("heading", { name: "Begin with a real problem." }),
      ).toBeVisible();
      expect(
        await p.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
      await expect(p.locator("[data-protected-content]")).toHaveCount(0);
      if (process.env.CODEFIT_CAPTURE_PRACTICE_SCREENSHOTS) {
        mkdirSync(destination, { recursive: true });
        await p.screenshot({
          path: join(destination, `practice-${width}-${theme}.png`),
          fullPage: true,
        });
      }
    }
  }
});
test("unauthorized frames and extra arguments cannot create a practice profile", async () => {
  const replies = await app.evaluate(async ({ ipcMain, BrowserWindow }) => {
    const sender = BrowserWindow.getAllWindows()[0].webContents;
    const handler = (ipcMain as any)._invokeHandlers.get(
      "codefit:practice:connect",
    );
    return Promise.all([
      handler({ sender, senderFrame: null }),
      handler({ sender, senderFrame: sender.mainFrame }, "path"),
    ]);
  });
  expect(replies).toEqual([
    { ok: false, code: "UNAVAILABLE" },
    { ok: false, code: "UNAVAILABLE" },
  ]);
});

test("lost committed reply, duplicate Start and cancellation preserve one recoverable identity", async () => {
  const p = await enter();
  await app.evaluate(({ ipcMain }) => {
    const handler = (ipcMain as any)._invokeHandlers.get(
      "codefit:practice:open",
    );
    ipcMain.removeHandler("codefit:practice:open");
    ipcMain.handle("codefit:practice:open", async (event, ...args) => {
      const result = await handler(event, ...args);
      (globalThis as any).practiceCommitted = !!result.ok;
      return new Promise((resolve) => {
        (globalThis as any).releasePractice = () => resolve(result);
      });
    });
  });
  await p.getByRole("button", { name: "Review start" }).first().click();
  await p.getByRole("button", { name: "Confirm start" }).click();
  await expect
    .poll(() => app.evaluate(() => !!(globalThis as any).practiceCommitted))
    .toBe(true);
  await expect(p.locator("[data-protected-content]")).toHaveCount(0);
  const userData = await app.evaluate(({ app }) => app.getPath("userData"));
  const intent = JSON.parse(
    readFileSync(
      join(
        userData,
        "experimental-practice-operations-v1",
        "pending-practice.json",
      ),
      "utf8",
    ),
  );

  await p
    .getByRole("button", { name: "Disconnect / cancel transport" })
    .click();
  await app.evaluate(() => (globalThis as any).releasePractice());
  await expect(p.locator("[data-protected-content]")).toHaveCount(0);
  await p
    .getByRole("button", { name: "Connect disposable practice profile" })
    .click();
  await expect(
    p.getByText(`Command ${intent.commandId}`, { exact: true }),
  ).toBeVisible();
  await p.getByRole("button", { name: "Recover original start" }).click();
  await expect(p.getByText(intent.commandId, { exact: true })).toBeVisible();
  await expect(
    p.getByText("Exposure committed", { exact: true }),
  ).toBeVisible();
});

test("failed post-start refresh clears live state and instructions while preserving recovery", async () => {
  const p = await enter();
  await app.evaluate(({ ipcMain }) => {
    const original = (ipcMain as any)._invokeHandlers.get(
      "codefit:practice:connect",
    );
    let fail = true;
    ipcMain.removeHandler("codefit:practice:connect");
    ipcMain.handle("codefit:practice:connect", (...args: any[]) => {
      if (fail) {
        fail = false;
        return { ok: false, code: "UNAVAILABLE" };
      }
      return original(...args);
    });
  });
  await p.getByRole("button", { name: "Review start" }).first().click();
  await p.getByRole("button", { name: "Confirm start" }).click();
  await expect(
    p.getByRole("heading", { name: "Practice action unavailable" }),
  ).toBeVisible();
  await expect(p.locator("[data-protected-content]")).toHaveCount(0);
  await expect(p.getByText("Live Java owner", { exact: true })).toHaveCount(0);
  await p
    .getByRole("button", { name: "Reconnect and inspect recovery" })
    .click();
  await p.getByRole("button", { name: "Recover original start" }).click();
  await expect(
    p.getByText("Exposure committed", { exact: true }),
  ).toBeVisible();
});
