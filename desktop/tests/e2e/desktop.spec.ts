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
        match[3].includes("com.codefit.desktop.DesktopReadOnlyMain"),
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

test("real read-only service works offline with sandbox, narrow bridge and dark/light layouts", async () => {
  const page = await application.firstWindow();
  await page.context().setOffline(true);
  await expect(page.getByRole("status")).toContainText(
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
    keys: ["cancelRead", "listDecks"],
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
        "codefit:decks:list",
      );
      const sender = BrowserWindow.getAllWindows()[0].webContents;
      return Promise.all([
        handler({ sender: { isDestroyed: () => false }, senderFrame: null }),
        handler({ sender, senderFrame: null }),
        handler({ sender, senderFrame: sender.mainFrame }, "sql"),
      ]);
    }),
  ).toEqual(Array.from({ length: 3 }, () => ({ ok: false, code: "UNAVAILABLE" })));
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
        page.getByRole("heading", { name: "Sample training library" }),
      ).toBeVisible();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth,
        ),
      ).toBe(true);
      if (process.env.CODEFIT_CAPTURE_SCREENSHOTS) {
        const output = resolve("../docs/architecture/gate0/screenshots");
        mkdirSync(output, { recursive: true });
        await page.screenshot({ path: join(output, `${theme}-${width}.png`) });
      }
    }
  }
  await page.getByRole("button", { name: "Refresh from Java" }).click();
  await expect(page.getByRole("status")).toContainText("8 deck descriptions");
  await page.keyboard.press("Tab");
  expect(await page.evaluate(() => document.activeElement?.tagName)).toBe(
    "BUTTON",
  );
});

test("cancel closes the Java process and the next read creates a fresh session", async () => {
  const page = await application.firstWindow();
  await expect(page.getByRole("status")).toContainText("8 deck descriptions");
  const previous = children(application.process().pid!)[0];
  await page.evaluate(() => window.codefit.cancelRead());
  await expect.poll(() => alive(previous)).toBe(false);
  await page.getByRole("button", { name: "Refresh from Java" }).click();
  await expect(page.getByRole("status")).toContainText("8 deck descriptions");
  expect(children(application.process().pid!)).toHaveLength(1);
  expect(children(application.process().pid!)[0]).not.toBe(previous);
});

test("Java crash is explained and retry recovers without changing the learner sentinel", async () => {
  const page = await application.firstWindow();
  await expect(page.getByRole("status")).toContainText("8 deck descriptions");
  process.kill(children(application.process().pid!)[0], "SIGKILL");
  await expect.poll(() => children(application.process().pid!).length).toBe(0);
  await page.getByRole("button", { name: "Refresh from Java" }).click();
  await expect(
    page.getByRole("heading", { name: "Connection needs attention" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Refresh from Java" }).click();
  await expect(page.getByRole("status")).toContainText("8 deck descriptions");
});

test("renderer crash and ordinary window close stop their owned Java process", async () => {
  let page = await application.firstWindow();
  await expect(page.getByRole("status")).toContainText("8 deck descriptions");
  const first = children(application.process().pid!)[0];
  await application.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].webContents.forcefullyCrashRenderer(),
  );
  await expect.poll(() => alive(first), { timeout: 7000 }).toBe(false);
  await application.close();
  application = await open();
  page = await application.firstWindow();
  await expect(page.getByRole("status")).toContainText("8 deck descriptions");
  const second = children(application.process().pid!)[0];
  await application.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].close(),
  );
  await expect.poll(() => alive(second), { timeout: 7000 }).toBe(false);
});

test("main process hard crash terminates the owned Java child", async () => {
  const page = await application.firstWindow();
  await expect(page.getByRole("status")).toContainText("8 deck descriptions");
  const child = children(application.process().pid!)[0];
  application.process().kill("SIGKILL");
  await expect.poll(() => alive(child), { timeout: 7000 }).toBe(false);
});

test("second application instance exits without another Java engine", async () => {
  const page = await application.firstWindow();
  await expect(page.getByRole("status")).toContainText("8 deck descriptions");
  const child = children(application.process().pid!)[0];
  const duplicate = spawn(executable, args, {
    cwd: directory,
    env: { ...process.env, XDG_CONFIG_HOME: directory },
    stdio: "ignore",
  });
  try {
    await expect.poll(() => duplicate.exitCode, { timeout: 7000 }).toBe(0);
    expect(children(application.process().pid!)).toEqual([child]);
    await expect(page.getByRole("status")).toContainText("8 deck descriptions");
  } finally {
    duplicate.kill("SIGKILL");
  }
});
