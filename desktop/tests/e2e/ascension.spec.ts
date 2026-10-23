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
  readdirSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { createLearnerFixture } from "../learner-fixture";
let app: ElectronApplication, dir: string;
const sources = new Map<string, Buffer>();
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
  dir = mkdtempSync(join(tmpdir(), "codefit-ascension-e2e-"));
  sources.clear();
  writeFileSync(join(dir, "codefit.db"), "learner sentinel");
  app = await launch();
});
test.afterEach(async () => {
  await app?.close().catch(() => {});
  for (const [path, bytes] of sources)
    expect(readFileSync(path)).toEqual(bytes);
  expect(readFileSync(join(dir, "codefit.db"), "utf8")).toBe(
    "learner sentinel",
  );
  rmSync(dir, { recursive: true, force: true });
});
function fixture(mode: string) {
  const path = join(dir, `${mode}-${sources.size}.db`);
  createLearnerFixture(path, `ascension-${mode}`);
  sources.set(path, readFileSync(path));
  return path;
}
async function dialogs(path: string) {
  await app.evaluate(({ dialog }, file) => {
    dialog.showOpenDialog = async () => ({
      canceled: false,
      filePaths: [file],
    });
    dialog.showMessageBox = async () => ({
      response: 1,
      checkboxChecked: false,
    });
  }, path);
}
async function connect(p: Page, mode = "supported") {
  await dialogs(fixture(mode));
  await p
    .getByRole("button", { name: /Open (preview|learner) profile/ })
    .click();
  await p
    .getByRole("button", { name: "Choose learner profile", exact: true })
    .click();
  await expect(
    p.getByRole("button", { name: "Capture again", exact: true }),
  ).toBeVisible();
  await p.getByRole("button", { name: "Close dialog", exact: true }).click();
}
async function tree(p: Page) {
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Ascension Tree/ })
    .click();
}
async function evidence(p: Page) {
  await expect(
    p.getByRole("region", { name: "Authoritative skill tree" }),
  ).toBeVisible();
  await p
    .getByRole("button", { name: /^Repair compound atomic updates,/ })
    .click();
  await p.getByRole("tab", { name: "Evidence", exact: true }).click();
}
async function capture(p: Page, name: string) {
  if (!process.env.CODEFIT_CAPTURE_ASCENSION_SCREENSHOTS) return;
  const folder = resolve("../docs/architecture/gate2-ascension/screenshots");
  mkdirSync(folder, { recursive: true });
  await p.evaluate(() => window.scrollTo({ top: 0, left: 0, behavior: "instant" }));
  await expect.poll(() => p.evaluate(() => window.scrollY)).toBe(0);
  await p.mouse.move(0, 0);
  await p.screenshot({
    path: join(folder, `${name}.png`),
    fullPage: true,
    animations: "disabled",
  });
}

test("real graph preserves premium composition in both themes and sizes with exact historical evidence", async () => {
  const p = await app.firstWindow(),
    errors: string[] = [];
  p.on("pageerror", (e) => errors.push(e.message));
  await p.context().setOffline(true);
  await connect(p);
  await tree(p);
  await evidence(p);
  await expect(p.locator(".skill-facts")).toContainText("INDEPENDENT");
  await expect(
    p.getByText("HISTORICAL · NOT ACTIVE SUPPORT", { exact: true }),
  ).toBeVisible();
  await expect(
    p.getByText("Current capability unavailable", { exact: true }),
  ).toBeVisible();
  await expect(p.locator(".map-note")).toContainText(
    "java-debugging-concurrency v1",
  );
  const response = await p.evaluate(() => window.codefit.readLearnerSkills());
  expect(response.ok).toBe(true);
  expect(JSON.stringify(response)).not.toContain("PRIVATE-");
  if (response.ok) {
    expect(response.result.nodes).toHaveLength(4);
    expect(response.result.edges).toHaveLength(3);
    expect(
      response.result.nodes.every(
        (n) => n.estimate.currentSupport === "NOT_EVALUATED",
      ),
    ).toBe(true);
  }
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
      await p.getByRole("tab", { name: "Overview", exact: true }).click();
      await p.evaluate(() => window.scrollTo(0, 0));
      expect(
        await p.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      const refBounds = await p.locator(".exact-skill-ref").boundingBox();
      expect(refBounds?.width).toBeGreaterThan(180);
      await capture(p, `tree-${theme}-${width}`);
      if (width === 760) {
        await p
          .getByRole("button", { name: /^Inspect Repair compound/ })
          .click();
        await expect(p.locator("#selected-skill-title")).toBeFocused();
      }
      await p.getByRole("tab", { name: "Evidence", exact: true }).click();
      if (width === 760) {
        const title = await p.locator("#selected-skill-title").boundingBox();
        const ref = await p.locator(".exact-skill-ref").boundingBox();
        expect(ref!.y - (title!.y + title!.height)).toBeLessThan(300);
      }
      await capture(p, `evidence-${theme}-${width}`);
    }
  }
  await p
    .getByRole("button", { name: "Refresh frozen view", exact: true })
    .click();
  await expect(
    p.getByRole("region", { name: "Authoritative skill tree" }),
  ).toBeVisible();
  expect(await p.evaluate(() => window.codefit.readLearnerSkills())).toEqual(
    response,
  );
  expect(errors).toEqual([]);
});

for (const [mode, expected] of [
  ["new", "No saved estimate exists"],
  ["insufficient", "DEVELOPING"],
  ["stale", "STALE"],
  ["contradictory", "New observations contradicted"],
  ["withdrawn", "later withdrawn"],
  ["outdated", "graph mismatch"],
])
  test(`${mode} evidence remains explicit without current support or unlocks`, async () => {
    const p = await app.firstWindow();
    await connect(p, mode);
    await tree(p);
    await evidence(p);
    await expect(p.getByRole("tabpanel")).toContainText(expected);
    await expect(p.getByRole("tabpanel")).toContainText(
      "Current capability unavailable",
    );
    await expect(p.locator(".learner-atlas .skill-node.completed")).toHaveCount(
      0,
    );
    await expect(
      p.getByRole("button", { name: "Explore a mission", exact: true }),
    ).toHaveCount(0);
    if (["stale", "contradictory", "withdrawn", "new"].includes(mode))
      await capture(p, mode);
  });

test("spatial keyboard navigation, zoom, prerequisite selection and reduced motion remain usable", async () => {
  const p = await app.firstWindow();
  await p.emulateMedia({ reducedMotion: "reduce" });
  await connect(p);
  await tree(p);
  await evidence(p);
  await p.evaluate(() => {
    const original = HTMLElement.prototype.scrollIntoView;
    HTMLElement.prototype.scrollIntoView = function (arg) {
      (window as any).__scrollOptions = arg;
      return original.call(this, arg);
    };
  });
  await p
    .getByRole("button", { name: /^Repair compound atomic updates,/ })
    .focus();
  await p.keyboard.press("ArrowUp");
  await expect(
    p.getByRole("button", { name: /^Preserve concurrent invariants,/ }),
  ).toBeFocused();
  expect(await p.evaluate(() => (window as any).__scrollOptions.behavior)).toBe(
    "instant",
  );
  await p.keyboard.press("Enter");
  await expect(p.locator("#selected-skill-title")).toHaveText(
    "Preserve concurrent invariants",
  );
  await p
    .getByRole("button", {
      name: "Repair compound atomic updates · v1",
      exact: true,
    })
    .click();
  await expect(p.locator("#selected-skill-title")).toHaveText(
    "Repair compound atomic updates",
  );
  await p.getByRole("button", { name: "Zoom in", exact: true }).click();
  await expect(p.getByRole("status", { name: "Tree zoom" })).toHaveText("125%");
  await p.getByRole("button", { name: "Reset tree view", exact: true }).click();
  await expect(p.getByRole("status", { name: "Tree zoom" })).toHaveText("100%");
});

test("a bounded large graph renders all authored nodes without whole-page overflow", async () => {
  const p = await app.firstWindow();
  await connect(p, "large");
  await tree(p);
  await expect(p.locator(".learner-atlas .skill-node")).toHaveCount(32);
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].setContentSize(760, 900),
  );
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  await p.locator(".learner-atlas .skill-node").first().focus();
  await p.keyboard.press("End");
  await expect(p.locator(".learner-atlas .skill-node").last()).toBeFocused();
  await p.keyboard.press("Enter");
  await expect(p.locator("#selected-skill-title")).toBeVisible();
});

async function delayFirstSkillRead() {
  await app.evaluate(({ ipcMain }) => {
    const old = (ipcMain as any)._invokeHandlers.get("codefit:learner:skills");
    let first = true;
    ipcMain.removeHandler("codefit:learner:skills");
    ipcMain.handle("codefit:learner:skills", async (event, ...args) => {
      const result = await old(event, ...args);
      if (first) {
        first = false;
        await new Promise<void>((resolve) => {
          (globalThis as any).__releaseSkills = resolve;
        });
      }
      return result;
    });
  });
}
test("profile switching discards a delayed previous learner tree", async () => {
  const p = await app.firstWindow();
  await connect(p);
  await delayFirstSkillRead();
  await tree(p);
  await expect
    .poll(() => app.evaluate(() => typeof (globalThis as any).__releaseSkills))
    .toBe("function");
  await dialogs(fixture("new"));
  await p
    .getByRole("button", { name: "Open learner profile", exact: true })
    .click();
  await p.getByRole("button", { name: "Capture again", exact: true }).click();
  await expect(
    p.getByRole("button", { name: "Capture again", exact: true }),
  ).toBeEnabled();
  await p.getByRole("button", { name: "Close dialog", exact: true }).click();
  await evidence(p);
  await expect(p.getByRole("tabpanel")).toContainText(
    "No saved estimate exists",
  );
  await app.evaluate(() => (globalThis as any).__releaseSkills());
  await p.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  );
  await expect(p.getByRole("tabpanel")).toContainText(
    "No saved estimate exists",
  );
  await expect(p.locator(".skill-facts")).toHaveCount(0);
});
test("interrupted read, renderer reload and application restart require new explicit consent", async () => {
  let p = await app.firstWindow();
  await connect(p);
  await delayFirstSkillRead();
  await tree(p);
  await expect
    .poll(() => app.evaluate(() => typeof (globalThis as any).__releaseSkills))
    .toBe("function");
  await p.reload();
  await app.evaluate(() => (globalThis as any).__releaseSkills());
  await expect(p.locator(".provenance")).toContainText("not connected");
  expect(await p.evaluate(() => window.codefit.readLearnerSkills())).toEqual({
    ok: false,
    code: "UNAVAILABLE",
  });
  await app.close();
  app = await launch();
  p = await app.firstWindow();
  expect(await p.evaluate(() => window.codefit.readLearnerSkills())).toEqual({
    ok: false,
    code: "UNAVAILABLE",
  });
  expect(
    readdirSync(join(dir, "CodeFit-Gate0", "learner-snapshots-v1")).filter(
      (n) => n.endsWith(".sqlite"),
    ),
  ).toHaveLength(1);
  await connect(p, "new");
  await tree(p);
  await evidence(p);
  await expect(p.getByRole("tabpanel")).toContainText(
    "No saved estimate exists",
  );
});
