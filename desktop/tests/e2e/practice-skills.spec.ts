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
import { spawnSync } from "node:child_process";
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
  dir = mkdtempSync(join(tmpdir(), "skills-ui-"));
  writeFileSync(join(dir, "codefit.db"), "REAL_LEARNER_UNCHANGED");
  app = await launch();
});
test.afterEach(async () => {
  app?.process().kill("SIGKILL");
  await app?.close().catch(() => {});
  expect(readFileSync(join(dir, "codefit.db"), "utf8")).toBe(
    "REAL_LEARNER_UNCHANGED",
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
async function snapshot() {
  const base = await app.evaluate(({ app }) => app.getPath("userData"));
  const r = spawnSync(
    "python3",
    [
      "-c",
      `import sqlite3,json,sys,hashlib
c=sqlite3.connect('file:'+sys.argv[1]+'?mode=ro',uri=True)
tables=[r[0] for r in c.execute("select name from sqlite_master where type='table' and name not like 'sqlite_%'")]
print(json.dumps({t:[hashlib.sha256(repr(r).encode()).hexdigest() for r in c.execute('select * from "'+t+'" order by rowid')] for t in tables},sort_keys=True))`,
      join(base, "experimental-practice-v1/codefit.db"),
    ],
    { encoding: "utf8", maxBuffer: 8 * 1024 * 1024 },
  );
  expect(r.status).toBe(0);
  return JSON.parse(r.stdout) as Record<string, string[]>;
}
async function read(p: Page) {
  await p
    .getByRole("button", { name: "Read saved interpretations / recover" })
    .click();
  await expect(
    p.getByRole("button", {
      name: "Update skill interpretations",
      exact: true,
    }),
  ).toBeEnabled();
}
async function publish(p: Page) {
  await read(p);
  await p
    .getByRole("button", { name: "Update skill interpretations", exact: true })
    .click();
  await expect(p.getByRole("dialog")).toContainText(
    "java.concurrency.atomic-update v1",
  );
  await p
    .getByRole("button", { name: "Confirm skill interpretation update" })
    .evaluate((b: HTMLButtonElement) => {
      b.click();
      b.click();
    });
  await expect(
    p.getByRole("region", { name: "Skill interpretations" }),
  ).toContainText("PUBLISHED");
}
test("explicit atomic skill update, no read mutations, replayed receipt and restart", async () => {
  let p = await connect();
  const before = await snapshot();
  await read(p);
  expect(await snapshot()).toEqual(before);
  await publish(p);
  const after = await snapshot();
  expect(after.evolution_estimate_revisions).toHaveLength(4);
  expect(after.desktop_skill_commands).toHaveLength(1);
  expect(after.desktop_skill_results).toHaveLength(4);
  for (const key of Object.keys(before))
    if (
      !key.startsWith("desktop_skill_") &&
      key !== "evolution_estimate_revisions"
    )
      expect(after[key], key).toEqual(before[key]);
  const region = p.getByRole("region", { name: "Skill interpretations" });
  await expect(region).toContainText("UNKNOWN");
  await expect(region).toContainText("INSUFFICIENT");
  const receipt = await region
    .locator(".practice-reference")
    .first()
    .innerText();
  await read(p);
  expect(await snapshot()).toEqual(after);
  app.process().kill("SIGKILL");
  await app.close().catch(() => {});
  app = await launch();
  p = await connect();
  await read(p);
  await expect(
    p
      .getByRole("region", { name: "Skill interpretations" })
      .locator(".practice-reference")
      .first(),
  ).toHaveText(receipt);
  expect(await snapshot()).toEqual(after);
});
test("lost committed acknowledgement and disconnect cannot restore old session state or duplicate estimates", async () => {
  const p = await connect();
  await read(p);
  await app.evaluate(({ ipcMain }) => {
    const fn = (ipcMain as any)._invokeHandlers.get("codefit:practice:skills");
    ipcMain.removeHandler("codefit:practice:skills");
    ipcMain.handle("codefit:practice:skills", async (e, ...args) => {
      const r = await fn(e, ...args);
      if (args[0] === "publish" && r.ok)
        await new Promise<void>((resolve) => {
          (globalThis as any).releaseSkills = resolve;
        });
      return r;
    });
  });
  await p
    .getByRole("button", { name: "Update skill interpretations", exact: true })
    .click();
  await p
    .getByRole("button", { name: "Confirm skill interpretation update" })
    .click();
  await expect
    .poll(() => app.evaluate(() => typeof (globalThis as any).releaseSkills))
    .toBe("function");
  await p
    .getByRole("button", { name: "Disconnect / cancel transport" })
    .click();
  await expect(
    p.getByRole("region", { name: "Skill interpretations" }),
  ).toHaveCount(0);
  await connect();
  await app.evaluate(() => (globalThis as any).releaseSkills());
  await read(p);
  await expect(
    p.getByRole("region", { name: "Skill interpretations" }),
  ).toContainText("PUBLISHED");
  const after = await snapshot();
  expect(after.evolution_estimate_revisions).toHaveLength(4);
  expect(after.desktop_skill_commands).toHaveLength(1);
  expect(
    await app.evaluate(async ({ ipcMain, BrowserWindow }) => {
      const w = BrowserWindow.getAllWindows()[0],
        fn = (ipcMain as any)._invokeHandlers.get("codefit:practice:skills");
      const extra = await fn(
        { sender: w.webContents, senderFrame: w.webContents.mainFrame },
        "publish",
        "profile",
        "session",
        "extra",
      );
      const frame = await fn(
        { sender: w.webContents, senderFrame: null },
        "publish",
        "profile",
        "session",
      );
      return !extra.ok && !frame.ok;
    }),
  ).toBe(true);
  expect(await snapshot()).toEqual(after);
});
test("packaged skill receipt themes, narrow layout and keyboard confirmation", async () => {
  const p = await connect();
  await read(p);
  const button = p.getByRole("button", {
    name: "Update skill interpretations",
    exact: true,
  });
  await button.focus();
  await button.press("Enter");
  await expect(p.getByRole("dialog")).toBeVisible();
  await p
    .getByRole("button", { name: "Confirm skill interpretation update" })
    .click();
  await expect(
    p.getByRole("region", { name: "Skill interpretations" }),
  ).toContainText("PUBLISHED");
  const folder = resolve(
    "../docs/architecture/gate2-skill-reconciliation/screenshots",
  );
  if (process.env.CODEFIT_CAPTURE_SKILLS_SCREENSHOTS)
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
      await p
        .getByRole("region", { name: "Skill interpretations" })
        .evaluate((e) =>
          e.scrollIntoView({ block: "start", behavior: "instant" }),
        );
      expect(
        await p.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      if (process.env.CODEFIT_CAPTURE_SKILLS_SCREENSHOTS)
        await p.screenshot({
          path: join(folder, `skills-${width}-${theme}.png`),
          animations: "disabled",
        });
    }
});
