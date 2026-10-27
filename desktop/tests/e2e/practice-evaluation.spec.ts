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
test("real evaluation freezes saved revision, preserves later edits and resumes finalized result after restart", async () => {
  let p = await start();
  const editor = p.getByRole("textbox", { name: "Java source editor" });
  await editor.fill("// Synthetic evaluation fixture\nclass Exercise {}\n");
  await expect(p.locator(".draft-status")).toHaveText(
    /^Saved · acknowledged revision \d+$/,
  );
  await prepare(p);
  const before = await p.locator(".practice-evaluation-result").innerText();
  expect(before).toContain("PREPARED");
  await editor.fill("// A later synthetic editable draft\nclass Exercise {}\n");
  await expect(p.locator(".draft-status")).toHaveText(
    /^Saved · acknowledged revision \d+$/,
  );
  await p.getByRole("button", { name: "Run frozen submission" }).click();
  await finalized(p);
  await expect(p.locator(".evaluation-checks li").first()).toBeVisible();
  const original = await p.locator(".practice-evaluation-result").innerText();
  expect(original).toContain("COMPILE_ERROR");
  const first = await counts();
  expect(first.evolution_submissions).toBe(1);
  expect(first.problem_attempts).toBe(1);
  expect(first.evolution_evaluation_revisions).toBe(1);
  expect(first.evolution_evidence_observations).toBe(1);
  await app.close();
  app = await launch();
  p = await connect();
  await p
    .getByRole("button", { name: "Resume saved practice" })
    .first()
    .click();
  await p.getByRole("button", { name: "Refresh submission status" }).click();
  await expect(p.locator(".practice-evaluation-result")).toContainText(
    "FINALIZED",
  );
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveValue("// A later synthetic editable draft\nclass Exercise {}\n");
  await p.getByRole("button", { name: "Check this submission" }).click();
  expect(await counts()).toEqual(first);
});
test("prepared submission survives renderer reload and explicit cancellation", async () => {
  let p = await start();
  await prepare(p);
  const initial = await counts();
  expect(initial.problem_attempts).toBe(0);
  await p.reload();
  p = await connect();
  await p
    .getByRole("button", { name: "Inspect saved submissions" })
    .first()
    .click();
  await expect(p.locator("[data-protected-content]")).toHaveCount(0);
  await expect(
    p.getByRole("textbox", { name: "Java source editor" }),
  ).toHaveCount(0);
  await expect(
    p.getByRole("button", { name: "Cancel frozen submission" }),
  ).toBeEnabled();
  await p.getByRole("button", { name: "Cancel frozen submission" }).click();
  await finalized(p);
  await expect(p.locator(".practice-evaluation-result")).toContainText(
    "CANCELLED",
  );
  expect((await counts()).evolution_challenge_presentations).toBe(1);
});
test("lost prepared response and double confirmation do not mint a second command", async () => {
  const p = await start();
  await app.evaluate(({ ipcMain }) => {
    const h = (ipcMain as any)._invokeHandlers.get(
      "codefit:practice:submission-prepare",
    );
    ipcMain.removeHandler("codefit:practice:submission-prepare");
    ipcMain.handle(
      "codefit:practice:submission-prepare",
      async (event, ...args) => {
        await h(event, ...args);
        return { ok: false, code: "TIMEOUT" };
      },
    );
  });
  await p.getByRole("button", { name: "Submit saved revision" }).click();
  await p.getByRole("button", { name: "Confirm frozen submission" }).dblclick();
  await expect(
    p.getByRole("button", { name: "Run frozen submission" }),
  ).toBeEnabled();
  expect((await counts()).evolution_submissions).toBe(1);
});
test("evaluation IPC rejects unauthorized frames, extra arguments and forged stage identities", async () => {
  const p = await start();
  const results = await app.evaluate(async ({ ipcMain, BrowserWindow }) => {
    const sender = BrowserWindow.getAllWindows()[0].webContents;
    const out = [];
    for (const channel of [
      "submission-prepare",
      "submission-action",
      "submissions",
    ]) {
      const h = (ipcMain as any)._invokeHandlers.get(
        "codefit:practice:" + channel,
      );
      out.push(await h({ sender, senderFrame: null }, {}, "p", "s"));
      out.push(
        await h(
          { sender, senderFrame: sender.mainFrame },
          {},
          "p",
          "s",
          "extra",
        ),
      );
    }
    return out;
  });
  expect(results).toEqual(Array(6).fill({ ok: false, code: "UNAVAILABLE" }));
  const rejected = await p.evaluate(async () => {
    const state = await window.codefit.connectPractice();
    if (!state.ok) throw new Error();
    return window.codefit.preparePracticeSubmission(
      {
        presentationId: "draft.v1",
        expectedRevision: 0,
        assistance: "UNKNOWN",
      },
      state.profileId,
      state.sessionId,
    );
  });
  expect(rejected).toEqual({ ok: false, code: "INVALID_INPUT" });
  expect((await counts()).evolution_submissions).toBe(0);
});
test("packaged redacted evaluation screenshots at desktop and narrow in both themes", async () => {
  const p = await start();
  await p
    .getByRole("textbox", { name: "Java source editor" })
    .fill("// Synthetic UI fixture\nclass Exercise {}\n");
  await expect(p.locator(".draft-status")).toHaveText(
    /^Saved · acknowledged revision \d+$/,
  );
  await prepare(p);
  await p.getByRole("button", { name: "Run frozen submission" }).click();
  await finalized(p);
  await p.getByText("Authorized instructions", { exact: true }).click();
  const folder = resolve("../docs/architecture/gate2-evaluation/screenshots");
  if (process.env.CODEFIT_CAPTURE_EVALUATION_SCREENSHOTS)
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
        .getByRole("region", { name: "Java evaluation and recovery" })
        .evaluate((e) =>
          e.scrollIntoView({ block: "start", behavior: "instant" }),
        );
      await expect(p.locator("[data-protected-content]")).not.toBeVisible();
      expect(
        await p.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      if (process.env.CODEFIT_CAPTURE_EVALUATION_SCREENSHOTS)
        await p.screenshot({
          path: join(folder, `evaluation-${width}-${theme}.png`),
        });
    }
});
