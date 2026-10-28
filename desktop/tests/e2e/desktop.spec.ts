import {
  _electron as electron,
  expect,
  test,
  type ElectronApplication,
} from "@playwright/test";
import electronPath from "electron";
import { execFileSync, spawn } from "node:child_process";
import {
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
  mkdirSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { resolve, join } from "node:path";

// Native Linux smoke coverage; production OS installers are deliberately a later gate.
const executable =
  process.env.CODEFIT_DESKTOP_EXECUTABLE || (electronPath as unknown as string);
const args = process.env.CODEFIT_DESKTOP_EXECUTABLE ? [] : [resolve(".")];
let application: ElectronApplication;
let directory: string;

function children(parent: number): number[] {
  return execFileSync("ps", ["-eo", "pid=,ppid=,args="], { encoding: "utf8" })
    .split("\n")
    .map((line) => line.trim().match(/^(\d+)\s+(\d+)\s+(.+)$/))
    .filter(
      (match) =>
        match &&
        Number(match[2]) === parent &&
        match[3].includes("com.codefit.desktop.DesktopApplicationMain"),
    )
    .map((match) => Number(match![1]));
}
function alive(pid: number): boolean {
  try {
    const state = readFileSync(`/proc/${pid}/stat`, "utf8").split(") ")[1][0];
    return state !== "Z";
  } catch {
    return false;
  }
}
async function open() {
  return electron.launch({
    chromiumSandbox: true,
    executablePath: executable,
    args,
    cwd: directory,
    env: {
      ...process.env,
      XDG_CONFIG_HOME: directory,
      JAVA_HOME: "/missing-jdk",
      CLASSPATH: "invalid",
      JAVA_TOOL_OPTIONS: "-invalid",
    },
  });
}
test.beforeEach(async () => {
  directory = mkdtempSync(join(tmpdir(), "codefit-desktop-smoke-"));
  writeFileSync(
    join(directory, "codefit.db"),
    "learner sentinel — must stay unchanged",
  );
  application = await open();
});
test.afterEach(async () => {
  await application.close().catch(() => {});
  expect(readFileSync(join(directory, "codefit.db"), "utf8")).toBe(
    "learner sentinel — must stay unchanged",
  );
  rmSync(directory, { recursive: true, force: true });
});

test("real experimental profile works offline with sandbox, narrow bridge and dark/light layouts", async () => {
  const page = await application.firstWindow();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: /Java Library/ })
    .click();
  await page.context().setOffline(true);
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions received from Java",
  );
  expect(children(application.process().pid!)).toHaveLength(1);
  const preferences = await application.evaluate(({ BrowserWindow }) => {
    const p = (
      BrowserWindow.getAllWindows()[0].webContents as any
    ).getLastWebPreferences();
    return {
      sandbox: p.sandbox,
      contextIsolation: p.contextIsolation,
      nodeIntegration: p.nodeIntegration,
      webSecurity: p.webSecurity,
    };
  });
  expect(
    await application.evaluate(() => process.argv.includes("--no-sandbox")),
  ).toBe(false);
  expect(preferences).toEqual({
    sandbox: true,
    contextIsolation: true,
    nodeIntegration: false,
    webSecurity: true,
  });
  expect(
    await page.evaluate(() => ({
      keys: Object.keys(window.codefit).sort(),
      node: typeof (window as any).require,
      process: typeof (window as any).process,
    })),
  ).toEqual({
    keys: [
      "acknowledgeDeck",
      "acknowledgePractice",
      "cancelCurriculumPreview",
      "cancelOperation",
      "chooseLearnerProfile",
      "connectPractice",
      "createDeck",
      "disconnectLearnerProfile",
      "disconnectPractice",
      "listPracticeSubmissions",
      "openAuthorizedPracticeChallenge",
      "pendingDeck",
      "practiceSkillAction",
      "practiceSubmissionAction",
      "preparePracticeSubmission",
      "readCurriculumPreview",
      "readLearnerMissions",
      "readLearnerProfile",
      "readLearnerSkills",
      "readPracticeDebrief",
      "readPracticeDraft",
      "readState",
      "reconcilePracticeDraft",
      "recoverDeck",
      "recoverPractice",
      "resumePractice",
      "savePracticeDraft",
      "setPracticeDraftDirty",
    ],
    node: "undefined",
    process: "undefined",
  });
  expect(
    await page.evaluate(async () => {
      try {
        await fetch("https://example.com");
        return "leaked";
      } catch {
        return "blocked";
      }
    }),
  ).toBe("blocked");
  await page.evaluate(() => window.open("https://example.com"));
  expect(application.windows()).toHaveLength(1);
  // Call the actual registered handler with an unauthorized sender and arguments, from test main only.
  expect(
    await application.evaluate(async ({ ipcMain, BrowserWindow }) => {
      const handler = (ipcMain as any)._invokeHandlers.get(
        "codefit:state:read",
      );
      const sender = BrowserWindow.getAllWindows()[0].webContents;
      return Promise.all([
        handler({ sender: { isDestroyed: () => false }, senderFrame: null }, 0),
        handler({ sender, senderFrame: null }, 0),
        handler({ sender, senderFrame: sender.mainFrame }, 0, "sql"),
      ]);
    }),
  ).toEqual(
    Array.from({ length: 3 }, () => ({ ok: false, code: "UNAVAILABLE" })),
  );
  for (const width of [1280, 760]) {
    await application.evaluate(
      ({ BrowserWindow }, width) =>
        BrowserWindow.getAllWindows()[0].setContentSize(width, 900),
      width,
    );
    for (const theme of ["dark", "light"]) {
      if ((await page.locator("html").getAttribute("data-theme")) !== theme)
        await page
          .getByRole("button", { name: `Switch to ${theme} theme` })
          .click();
      await expect(
        page.getByRole("heading", { name: "Experimental training library" }),
      ).toBeVisible();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
      if (process.env.CODEFIT_CAPTURE_GATE2_SCREENSHOTS) {
        const output = resolve("../docs/architecture/gate2/screenshots");
        mkdirSync(output, { recursive: true });
        await page.waitForTimeout(200);
        await page.screenshot({
          path: join(output, `${theme}-${width}.png`),
          animations: "disabled",
        });
      }
    }
  }
  await page.getByRole("button", { name: "Refresh from Java" }).click();
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions",
  );
  await page.getByRole("link", { name: "Skip to content" }).focus();
  await expect(
    page.getByRole("link", { name: "Skip to content" }),
  ).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.locator("#main-content")).toBeFocused();
  expect(await page.evaluate(() => location.hash)).toBe("");
  await page.getByRole("button", { name: "Refresh from Java" }).click();
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions",
  );
});

test("cancel closes the Java process and the next read creates a fresh session", async () => {
  const page = await application.firstWindow();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: /Java Library/ })
    .click();
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions",
  );
  const previous = children(application.process().pid!)[0];
  await page.evaluate(() => window.codefit.cancelOperation());
  await expect.poll(() => alive(previous)).toBe(false);
  await page.getByRole("button", { name: "Refresh from Java" }).click();
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions",
  );
  expect(children(application.process().pid!)).toHaveLength(1);
  expect(children(application.process().pid!)[0]).not.toBe(previous);
});

test("Java crash is explained and retry recovers without changing the learner sentinel", async () => {
  const page = await application.firstWindow();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: /Java Library/ })
    .click();
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions",
  );
  process.kill(children(application.process().pid!)[0], "SIGKILL");
  await expect.poll(() => children(application.process().pid!).length).toBe(0);
  await page.getByRole("button", { name: "Refresh from Java" }).click();
  await expect(
    page.getByRole("heading", { name: "Connection needs attention" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Refresh from Java" }).click();
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions",
  );
});

test("renderer crash and ordinary window close stop their owned Java process", async () => {
  let page = await application.firstWindow();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: /Java Library/ })
    .click();
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions",
  );
  const first = children(application.process().pid!)[0];
  await application.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].webContents.forcefullyCrashRenderer(),
  );
  await expect.poll(() => alive(first), { timeout: 7000 }).toBe(false);
  await application.close();
  application = await open();
  page = await application.firstWindow();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: /Java Library/ })
    .click();
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions",
  );
  const second = children(application.process().pid!)[0];
  await application.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].close(),
  );
  await expect.poll(() => alive(second), { timeout: 7000 }).toBe(false);
});

test("main process hard crash terminates the owned Java child", async () => {
  const page = await application.firstWindow();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: /Java Library/ })
    .click();
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions",
  );
  const child = children(application.process().pid!)[0];
  application.process().kill("SIGKILL");
  await expect.poll(() => alive(child), { timeout: 7000 }).toBe(false);
});

test("second application instance exits without another Java engine", async () => {
  const page = await application.firstWindow();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: /Java Library/ })
    .click();
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions",
  );
  const child = children(application.process().pid!)[0];
  const duplicate = spawn(executable, args, {
    cwd: directory,
    env: { ...process.env, XDG_CONFIG_HOME: directory },
    stdio: "ignore",
  });
  try {
    await expect.poll(() => duplicate.exitCode, { timeout: 7000 }).toBe(0);
    expect(children(application.process().pid!)).toEqual([child]);
    await expect(page.locator(".library > .status")).toContainText(
      "8 deck descriptions",
    );
  } finally {
    duplicate.kill("SIGKILL");
  }
});

async function library() {
  const page = await application.firstWindow();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: /Java Library/ })
    .click();
  await expect(page.locator(".library > .status")).toContainText(
    "8 deck descriptions",
  );
  return page;
}
async function capture(
  page: Awaited<ReturnType<typeof library>>,
  name: string,
) {
  if (!process.env.CODEFIT_CAPTURE_GATE2_SCREENSHOTS) return;
  const output = resolve("../docs/architecture/gate2/screenshots");
  mkdirSync(output, { recursive: true });
  await page.waitForTimeout(200); // Let the approved 160ms theme transition settle.
  await page.screenshot({
    path: join(output, name + ".png"),
    animations: "disabled",
  });
}

test("intentional deck creation persists across restart with explicit receipt and duplicate-name rejection", async () => {
  let page = await library();
  await page
    .getByLabel("Deck name", { exact: true })
    .fill("My offline practice");
  await page
    .getByLabel("Description", { exact: false })
    .fill("Created by Java in the experimental profile.");
  await page
    .getByRole("button", { name: "Create deck in experimental profile" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Deck created", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".operation-receipt")).toContainText(
    "Java confirmed “My offline practice”",
  );
  const receipt = await page.evaluate(() => window.codefit.pendingDeck());
  expect(receipt.ok && receipt.operation?.receipt?.status).toBe("created");
  for (const width of [1280, 760]) {
    await application.evaluate(
      ({ BrowserWindow }, width) =>
        BrowserWindow.getAllWindows()[0].setContentSize(width, 900),
      width,
    );
    for (const theme of ["dark", "light"]) {
      if ((await page.locator("html").getAttribute("data-theme")) !== theme)
        await page
          .getByRole("button", { name: `Switch to ${theme} theme` })
          .click();
      await expect(
        page.getByRole("button", { name: "Acknowledge and continue" }),
      ).toBeEnabled();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      await capture(page, `created-${theme}-${width}`);
    }
  }
  await application.close();
  application = await open();
  page = await library();
  await expect(
    page.getByRole("heading", { name: "Deck created", exact: true }),
  ).toBeVisible();
  expect(await page.evaluate(() => window.codefit.pendingDeck())).toEqual(
    receipt,
  );
  await page.getByRole("button", { name: "Acknowledge and continue" }).click();
  await page.getByRole("button", { name: "Next page of decks" }).click();
  await expect(page.locator(".deck")).toHaveCount(1);
  await expect(page.locator(".deck h3")).toHaveText("My offline practice");
  await page
    .getByLabel("Deck name", { exact: true })
    .fill("My offline practice");
  await page
    .getByRole("button", { name: "Create deck in experimental profile" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Name already exists", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".operation-receipt")).toContainText(
    "No additional deck was created",
  );
  await capture(page, "name-rejected-dark-1280");
});

test("lost create reply shows unknown completion and reconciles the original Java receipt", async () => {
  const page = await library();
  // Test-only transport fault, after real Java committed: drop the receipt before renderer delivery.
  await application.evaluate(({ ipcMain }) => {
    const original = (ipcMain as any)._invokeHandlers.get(
      "codefit:deck:create",
    );
    ipcMain.removeHandler("codefit:deck:create");
    ipcMain.handle("codefit:deck:create", async (...args: any[]) => {
      const result = await original(...args);
      if (result.ok && result.operation)
        return {
          ok: true,
          operation: {
            ...result.operation,
            receipt: null,
            error: "UNAVAILABLE",
          },
        };
      return result;
    });
  });
  await page
    .getByLabel("Deck name", { exact: true })
    .fill("Recovered after interruption");
  await page
    .getByRole("button", { name: "Create deck in experimental profile" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Creation needs reconciliation" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Deck created", exact: true }),
  ).toHaveCount(0);
  await capture(page, "unknown-completion-dark-1280");
  const command = await page.locator(".operation-receipt code").innerText();
  await page
    .getByRole("button", { name: "Check and finish saved creation" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Deck created", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".operation-receipt code")).toHaveText(command);
  await capture(page, "reconciled-dark-1280");
});

test("renderer crash during a withheld write response preserves recoverable intent on application restart", async () => {
  let page = await library();
  await application.evaluate(({ ipcMain }) => {
    const original = (ipcMain as any)._invokeHandlers.get(
      "codefit:deck:create",
    );
    ipcMain.removeHandler("codefit:deck:create");
    ipcMain.handle("codefit:deck:create", async (...args: any[]) => {
      const result = await original(...args);
      (globalThis as any).__gate2Committed = result;
      return new Promise(() => {});
    });
  });
  await page
    .getByLabel("Deck name", { exact: true })
    .fill("Crash-safe creation");
  await page
    .getByRole("button", { name: "Create deck in experimental profile" })
    .click();
  await expect
    .poll(() =>
      application.evaluate(
        () => (globalThis as any).__gate2Committed?.operation?.receipt?.status,
      ),
    )
    .toBe("created");
  const command = await application.evaluate(
    () => (globalThis as any).__gate2Committed.operation.commandId,
  );
  const child = children(application.process().pid!)[0];
  await capture(page, "pending-dark-1280");
  await application.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].webContents.forcefullyCrashRenderer(),
  );
  await expect.poll(() => alive(child), { timeout: 7000 }).toBe(false);
  await application.close();
  application = await open();
  page = await library();
  await expect(
    page.getByRole("heading", { name: "Deck created", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".operation-receipt code")).toHaveText(command);
  await page.getByRole("button", { name: "Acknowledge and continue" }).click();
  await page.getByRole("button", { name: "Next page of decks" }).click();
  await expect(page.locator(".deck")).toHaveCount(1);
  await expect(page.locator(".deck h3")).toHaveText("Crash-safe creation");
});

test("all write and recovery handlers deny unauthorized frames and malformed arguments", async () => {
  const page = await library();
  const results = await application.evaluate(
    async ({ ipcMain, BrowserWindow }) => {
      const sender = BrowserWindow.getAllWindows()[0].webContents;
      const handlers = (ipcMain as any)._invokeHandlers;
      return Promise.all([
        handlers.get("codefit:deck:create")(
          { sender, senderFrame: null },
          { name: "Forbidden", description: "" },
        ),
        handlers.get("codefit:deck:recover")({ sender, senderFrame: null }),
        handlers.get("codefit:deck:pending")({ sender, senderFrame: null }),
        handlers.get("codefit:deck:acknowledge")(
          { sender, senderFrame: null },
          "bad",
        ),
        handlers.get("codefit:deck:create")(
          { sender, senderFrame: sender.mainFrame },
          { name: "Bad", description: "", sql: "SELECT 1" },
        ),
      ]);
    },
  );
  expect(results.slice(0, 4)).toEqual(
    Array.from({ length: 4 }, () => ({ ok: false, code: "UNAVAILABLE" })),
  );
  expect(results[4]).toEqual({ ok: false, code: "INVALID_INPUT" });
  expect(await page.evaluate(() => window.codefit.pendingDeck())).toEqual({
    ok: true,
    operation: null,
  });
});

test("cancelled write transport reconciles committed receipt and suppresses its late renderer reply", async () => {
  const page = await library();
  await application.evaluate(({ ipcMain }) => {
    const original = (ipcMain as any)._invokeHandlers.get(
      "codefit:deck:create",
    );
    ipcMain.removeHandler("codefit:deck:create");
    ipcMain.handle("codefit:deck:create", async (...args: any[]) => {
      const result = await original(...args);
      (globalThis as any).__gate2Committed = result;
      return new Promise((resolve) => {
        (globalThis as any).__gate2Release = () =>
          resolve({
            ok: true,
            operation: { ...result.operation, receipt: null, error: "TIMEOUT" },
          });
      });
    });
  });
  await page
    .getByLabel("Deck name", { exact: true })
    .fill("Cancelled connection");
  await page
    .getByRole("button", { name: "Create deck in experimental profile" })
    .click();
  await expect
    .poll(() =>
      application.evaluate(
        () => (globalThis as any).__gate2Committed?.operation?.receipt?.status,
      ),
    )
    .toBe("created");
  await page.getByRole("button", { name: "Cancel connection" }).click();
  await expect(
    page.getByRole("heading", { name: "Deck created", exact: true }),
  ).toBeVisible();
  await application.evaluate(async ({ BrowserWindow }) => {
    (globalThis as any).__gate2Release();
    // Flush the invoke reply from main, then wait for renderer microtasks and React paint.
    await new Promise<void>((resolve) => setImmediate(resolve));
    await BrowserWindow.getAllWindows()[0].webContents.executeJavaScript(
      "new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))",
    );
  });
  await expect(
    page.getByRole("heading", { name: "Creation needs reconciliation" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: "Deck created", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Acknowledge and continue" }),
  ).toBeEnabled();
});

test("maximum-length Unicode deck descriptors fit the narrow library after a real write", async () => {
  const page = await library();
  await application.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].setContentSize(760, 900),
  );
  const name = "λ".repeat(256),
    description = "界".repeat(2048);
  await page.getByLabel("Deck name", { exact: true }).fill(name);
  await page.getByLabel("Description", { exact: false }).fill(description);
  await page
    .getByRole("button", { name: "Create deck in experimental profile" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Deck created", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Acknowledge and continue" }).click();
  await page.getByRole("button", { name: "Next page of decks" }).click();
  await expect(page.locator(".deck")).toHaveCount(1);
  await expect(page.locator(".deck h3")).toHaveText(name);
  await expect(page.locator(".deck > p")).toHaveText(description);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
