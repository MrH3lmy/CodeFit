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
  readFileSync,
  writeFileSync,
  mkdirSync,
} from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
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
  dir = mkdtempSync(join(tmpdir(), "eval-ui-"));
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
  await p.getByRole("button", { name: "Refresh submission status" }).click();
  await expect(
    p.getByRole("button", { name: "Submit saved revision" }),
  ).toBeEnabled();
  return p;
}
async function prepare(p: Page) {
  await p.getByRole("button", { name: "Submit saved revision" }).click();
  await p.getByLabel("Outside assistance").selectOption("NONE_DECLARED");
  await p.getByRole("button", { name: "Confirm frozen submission" }).click();
  await expect(
    p.getByRole("button", { name: "Run frozen submission" }),
  ).toBeEnabled();
}
async function finalized(p: Page) {
  await expect
    .poll(
      async () => {
        const b = p.getByRole("button", { name: "Check this submission" });
        if (await b.isEnabled()) await b.click();
        return p
          .getByRole("article", { name: "Frozen submission result" })
          .innerText();
      },
      { timeout: 30000 },
    )
    .toContain("FINALIZED");
}
async function counts() {
  const path = await app.evaluate(({ app }) => app.getPath("userData"));
  const r = spawnSync(
    "python3",
    [
      "-c",
      `import sqlite3,json,sys
c=sqlite3.connect('file:'+sys.argv[1]+'?mode=ro',uri=True)
tables=['evolution_submissions','evolution_evaluation_revisions','problem_attempts','evolution_evidence_observations','evolution_challenge_presentations']
print(json.dumps({t:c.execute('select count(*) from '+t).fetchone()[0] for t in tables}))`,
      join(path, "experimental-practice-v1/codefit.db"),
    ],
    { encoding: "utf8" },
  );
  expect(r.status).toBe(0);
  return JSON.parse(r.stdout);
}
async function debrief(p: Page) {
  await prepare(p);
  await p.getByRole("button", { name: "Cancel frozen submission" }).click();
  await finalized(p);
  await p
    .getByRole("button", { name: "Read learning debrief", exact: true })
    .click();
  await expect(
    p.getByRole("heading", { name: "One correlated observation" }),
  ).toBeVisible();
}
test("debrief survives restart, repeated reads preserve records, and failed reads retain saved result", async () => {
  let p = await start();
  await debrief(p);
  const before = await counts();
  await expect(
    p.getByRole("region", { name: "Learning debrief" }),
  ).toContainText("Not yet evaluated");
  await p.getByRole("button", { name: "Refresh learning debrief" }).click();
  await expect(
    p.getByRole("heading", { name: "One correlated observation" }),
  ).toBeVisible();
  expect(await counts()).toEqual(before);
  await app.evaluate(({ ipcMain }) => {
    ipcMain.removeHandler("codefit:practice:debrief");
    ipcMain.handle("codefit:practice:debrief", () => ({
      ok: false,
      code: "UNAVAILABLE",
    }));
  });
  await p.getByRole("button", { name: "Refresh learning debrief" }).click();
  await expect(p.getByRole("alert")).toContainText(
    "Evaluation saved. Learning summary unavailable.",
  );
  await expect(
    p.getByRole("article", { name: "Frozen submission result" }),
  ).toContainText("FINALIZED");
  expect(await counts()).toEqual(before);
  app.process().kill("SIGKILL");
  await app.close().catch(() => {});
  app = await launch();
  p = await connect();
  await p
    .getByRole("button", { name: "Inspect saved submissions" })
    .first()
    .click();
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveCount(0);
  await p
    .getByRole("button", { name: "Read learning debrief", exact: true })
    .click();
  await expect(
    p.getByRole("heading", { name: "One correlated observation" }),
  ).toBeVisible();
  expect(await counts()).toEqual(before);
});
test("debrief owning-frame boundary rejects extra arguments and disconnect suppresses late renderer results", async () => {
  const p = await start();
  await debrief(p);
  expect(
    await app.evaluate(async ({ ipcMain, BrowserWindow }) => {
      const w = BrowserWindow.getAllWindows()[0],
        fn = (ipcMain as any)._invokeHandlers.get("codefit:practice:debrief");
      const extra = await fn(
        { sender: w.webContents, senderFrame: w.webContents.mainFrame },
        {},
        "profile",
        "session",
        "extra",
      );
      const frame = await fn(
        { sender: w.webContents, senderFrame: null },
        {},
        "profile",
        "session",
      );
      return !extra.ok && !frame.ok;
    }),
  ).toBe(true);
  await app.evaluate(({ ipcMain }) => {
    const fn = (ipcMain as any)._invokeHandlers.get("codefit:practice:debrief");
    ipcMain.removeHandler("codefit:practice:debrief");
    ipcMain.handle("codefit:practice:debrief", async (e, ...args) => {
      const r = await fn(e, ...args);
      await new Promise<void>(
        (resolve) => ((globalThis as any).releaseDebrief = resolve),
      );
      return r;
    });
  });
  await p.getByRole("button", { name: "Refresh learning debrief" }).click();
  await p
    .getByRole("button", { name: "Disconnect / cancel transport" })
    .click();
  await expect(
    p.getByRole("button", { name: "Connect disposable practice profile" }),
  ).toBeVisible();
  await expect
    .poll(() => app.evaluate(() => typeof (globalThis as any).releaseDebrief))
    .toBe("function");
  await app.evaluate(() => (globalThis as any).releaseDebrief());
  await expect(p.getByRole("region", { name: "Learning debrief" })).toHaveCount(
    0,
  );
});
test("packaged learning debrief screenshots and keyboard scope inspection", async () => {
  const p = await start();
  await p
    .getByRole("textbox", { name: "Java source editor" })
    .fill("// Synthetic debrief capture\nclass Exercise {}\n");
  await expect(p.locator(".draft-status")).toHaveText(
    /^Saved · acknowledged revision \d+$/,
  );
  await prepare(p);
  await p.getByRole("button", { name: "Run frozen submission" }).click();
  await finalized(p);
  await p
    .getByRole("button", { name: "Read learning debrief", exact: true })
    .click();
  await expect(
    p.getByRole("heading", { name: "One correlated observation" }),
  ).toBeVisible();
  await p.getByText("Authorized instructions", { exact: true }).click();
  const folder = resolve("../docs/architecture/gate2-debrief/screenshots");
  if (process.env.CODEFIT_CAPTURE_DEBRIEF_SCREENSHOTS)
    mkdirSync(folder, { recursive: true });
  const details = p.getByText("Inspect check scope and behavior targets", {
    exact: true,
  });
  await details.focus();
  await details.press("Enter");
  await expect(
    p.getByRole("region", { name: "Learning debrief" }),
  ).toContainText("Required");
  await details.press("Enter");
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
      const region = p.getByRole("region", { name: "Learning debrief" });
      await region.evaluate((e) =>
        e.scrollIntoView({ block: "start", behavior: "instant" }),
      );
      await expect(p.locator("[data-protected-content]")).not.toBeVisible();
      expect(
        await p.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      if (process.env.CODEFIT_CAPTURE_DEBRIEF_SCREENSHOTS)
        await p.screenshot({
          path: join(folder, `debrief-${width}-${theme}.png`),
          animations: "disabled",
        });
    }
});
