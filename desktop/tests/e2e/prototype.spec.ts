import {
  _electron as electron,
  test,
  expect,
  type ElectronApplication,
} from "@playwright/test";
import electronPath from "electron";
import {
  mkdtempSync,
  rmSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
let app: ElectronApplication;
let dir: string;
const output = resolve("../docs/architecture/gate1/screenshots");
test.beforeEach(async ({}, testInfo) => {
  dir = mkdtempSync(join(tmpdir(), "codefit-gate1-"));
  writeFileSync(join(dir, "codefit.db"), "gate1 learner sentinel");
  app = await electron.launch({
    chromiumSandbox: true,
    executablePath:
      process.env.CODEFIT_DESKTOP_EXECUTABLE ||
      (electronPath as unknown as string),
    args: [
      ...(process.env.CODEFIT_DESKTOP_EXECUTABLE ? [] : [resolve(".")]),
      ...(testInfo.title.includes("HiDPI")
        ? ["--force-device-scale-factor=2"]
        : []),
    ],
    cwd: dir,
    env: { ...process.env, XDG_CONFIG_HOME: dir },
  });
});
test.afterEach(async () => {
  await app?.close().catch(() => {});
  expect(readFileSync(join(dir, "codefit.db"), "utf8")).toBe(
    "gate1 learner sentinel",
  );
  rmSync(dir, { recursive: true, force: true });
});
async function capture(name: string) {
  if (process.env.CODEFIT_CAPTURE_SCREENSHOTS) {
    mkdirSync(output, { recursive: true });
    await (
      await app.firstWindow()
    ).screenshot({ path: join(output, name + ".png"), animations: "disabled" });
  }
}

test("twelve native screen, theme and width compositions", async () => {
  const p = await app.firstWindow();
  await p.context().setOffline(true);
  const errors: string[] = [];
  p.on("pageerror", (error) => errors.push(error.message));
  for (const width of [1280, 760]) {
    await app.evaluate(
      ({ BrowserWindow }, width) =>
        BrowserWindow.getAllWindows()[0].setContentSize(width, 900),
      width,
    );
    for (const theme of ["dark", "light"]) {
      if ((await p.locator("html").getAttribute("data-theme")) !== theme)
        await p
          .getByRole("button", { name: `Switch to ${theme} theme` })
          .click();
      for (const [name, file] of [
        ["Command Center", "command"],
        ["Ascension Tree", "tree"],
        ["Mission Console", "missions"],
      ]) {
        await p
          .getByRole("navigation")
          .getByRole("button", { name: new RegExp(name) })
          .click();
        await expect(p.getByRole("heading", { level: 1 })).toContainText(name);
        await p.evaluate(() => document.fonts.ready);
        await expect(
          p.getByText(
            "Demonstration data. Your learner profile is not connected.",
          ),
        ).toBeVisible();
        expect(
          await p.evaluate(
            () => document.documentElement.scrollWidth <= innerWidth,
          ),
        ).toBe(true);
        await capture(`${file}-${theme}-${width}`);
        if (file === "command") {
          await p.locator(".command-lower").scrollIntoViewIfNeeded();
          await capture(`command-details-${theme}-${width}`);
          await p.evaluate(() => scrollTo(0, 0));
        }
        if (file === "tree" && width === 760) {
          await p.getByRole("button", { name: "Inspect Debugging" }).click();
          await expect(p.locator("#selected-skill-title")).toBeFocused();
          await capture(`tree-inspector-${theme}-760`);
          await p.evaluate(() => scrollTo(0, 0));
        }
      }
    }
  }
  expect(errors).toEqual([]);
});

test("mission search, filters, bookmarks and briefing navigation", async () => {
  const p = await app.firstWindow();
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Mission Console/ })
    .click();
  await p.getByRole("textbox", { name: "Search missions" }).fill("contract");
  await expect(p.locator(".mission-item")).toHaveCount(1);
  await expect(
    p.getByRole("complementary", { name: "Selected mission" }),
  ).toContainText("A contract worth keeping");
  await p
    .getByRole("button", { name: "Bookmark mission", exact: true })
    .click();
  await expect(
    p.getByRole("button", { name: "Remove mission bookmark" }),
  ).toHaveAttribute("aria-pressed", "true");
  await p
    .getByRole("textbox", { name: "Search missions" })
    .fill("no such mission");
  await expect(
    p.getByRole("heading", { name: "Your next chapter is unwritten." }),
  ).toBeVisible();
  await capture("missions-empty");
  await p.getByRole("button", { name: "Explore sample missions" }).click();
  await p.getByRole("tab", { name: "Blocked", exact: true }).click();
  await p.getByRole("button", { name: "Inspect requirements" }).click();
  await expect(p.getByRole("dialog")).toContainText("BLOCKED IN THIS EXAMPLE");
  await expect(
    p.getByRole("tab", { name: "Workspace", exact: true }),
  ).toBeDisabled();
  await expect(
    p.getByRole("tab", { name: "Evaluation", exact: true }),
  ).toBeDisabled();
  await p.getByRole("button", { name: "View skill path" }).click();
  await expect(p.getByRole("heading", { level: 1 })).toContainText(
    "Ascension Tree",
  );
});

test("skill graph keyboard exploration, prerequisites, evidence and zoom", async () => {
  const p = await app.firstWindow();
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Ascension Tree/ })
    .click();
  await p.getByRole("button", { name: "Zoom in" }).click();
  await expect(p.getByRole("status", { name: "Tree zoom" })).toHaveText("125%");
  await p.getByRole("button", { name: "Reset tree view" }).click();
  await p.getByRole("button", { name: /Debugging, In progress/ }).focus();
  await p.keyboard.press("ArrowRight");
  await expect(
    p.getByRole("button", { name: /Collections, Available/ }),
  ).toBeFocused();
  await p.keyboard.press("Enter");
  await expect(
    p
      .getByRole("complementary", { name: "Selected skill" })
      .getByRole("heading", { name: "Collections", exact: true }),
  ).toBeVisible();
  await p.getByRole("tab", { name: "Overview", exact: true }).focus();
  await p.keyboard.press("ArrowRight");
  await expect(
    p.getByRole("tab", { name: "Evidence", exact: true }),
  ).toBeFocused();
  await expect(
    p.getByRole("tab", { name: "Evidence", exact: true }),
  ).toHaveAttribute("aria-selected", "true");
  await p.getByLabel("Evidence scenario").selectOption("Stale");
  await expect(p.getByText("A fresh observation is due")).toBeVisible();
  await capture("evidence-stale");
  await p.getByLabel("Evidence scenario").selectOption("Contradictory");
  await expect(p.getByText("The evidence disagrees")).toBeVisible();
  await capture("evidence-contradictory");
  await p.getByRole("tab", { name: "Overview", exact: true }).click();
  await p.getByRole("button", { name: "Control flow", exact: true }).click();
  await expect(
    p
      .getByRole("complementary", { name: "Selected skill" })
      .getByRole("heading", { name: "Control flow", exact: true }),
  ).toBeVisible();
});

test("sample editor retains text, guards unsaved close, and shows recovery states", async () => {
  const p = await app.firstWindow();
  await p.getByRole("button", { name: "Open preview profile" }).click();
  await expect(p.getByRole("dialog")).toContainText("Not connected");
  await capture("profile-unconnected");
  await p.keyboard.press("Escape");
  await p.getByRole("button", { name: "View journal" }).click();
  await expect(p.getByRole("dialog")).toContainText("Not earned.");
  await capture("activity-achievement-specimen");
  await p.keyboard.press("Escape");
  await p.getByRole("button", { name: "Enter your next mission" }).click();
  await p.getByRole("button", { name: "Enter sample workspace" }).click();
  await p
    .getByRole("textbox", { name: "Example mission editor" })
    .fill("// a testable assumption");
  await p.keyboard.press("Escape");
  await expect(
    p.getByRole("heading", { name: "Keep your thought in view." }),
  ).toBeVisible();
  await capture("workspace-unsaved");
  await p.getByRole("button", { name: "Keep editing" }).click();
  await expect(
    p.getByRole("textbox", { name: "Example mission editor" }),
  ).toHaveValue("// a testable assumption");
  await p.getByRole("button", { name: "Keep in this preview" }).click();
  await p.getByRole("button", { name: "Simulate evaluation" }).click();
  await expect(p.getByText("Preparing example feedback…")).toBeVisible();
  await expect(
    p.getByText("An explanation is a useful first step."),
  ).toBeVisible();
  await p.getByLabel("Evaluation scenario").selectOption("Interrupted");
  await capture("evaluation-interrupted");
  await p.getByRole("button", { name: "Inspect example status" }).click();
  await expect(
    p.getByText("An explanation is a useful first step."),
  ).toBeVisible();
  await p
    .getByLabel("Evaluation scenario")
    .selectOption("Evaluator unavailable");
  await capture("evaluator-unavailable");
  await p.keyboard.press("Escape");
  await expect(p.getByRole("dialog")).toHaveCount(0);
  await expect(
    p.getByRole("button", { name: "Enter your next mission" }),
  ).toBeFocused();
});

test("designed loading, offline, error, unknown and empty states remain recoverable", async () => {
  const p = await app.firstWindow();
  for (const [label, file] of [
    ["Loading", "loading"],
    ["Offline", "offline"],
    ["Service error", "error"],
    ["New learner / unknown", "unknown"],
    ["Empty", "empty"],
  ]) {
    await p
      .getByRole("button", { name: "Preview states", exact: true })
      .click();
    await p
      .getByRole("dialog")
      .getByRole("button", { name: new RegExp(label.replace("/", "\\/")) })
      .click();
    if (file === "unknown") {
      await expect(
        p.locator(".active-mission, .command-lower, .objective-list"),
      ).toHaveCount(0);
      await expect(
        p.getByText(
          "No active mission, completion history or earned achievement is connected.",
          { exact: false },
        ),
      ).toBeVisible();
    }
    await capture(`state-${file}`);
    await expect(
      p.getByText("Demonstration data. Your learner profile is not connected."),
    ).toBeVisible();
  }
  await p.getByRole("button", { name: "Explore sample missions" }).click();
  await expect(
    p.getByRole("button", { name: "Enter your next mission" }),
  ).toBeVisible();
});

test("keyboard dialog containment, theme and reduced motion at minimum width", async () => {
  const p = await app.firstWindow();
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].setContentSize(760, 900),
  );
  await p.emulateMedia({ reducedMotion: "reduce" });
  await p.getByRole("button", { name: "Preview states", exact: true }).focus();
  await p.keyboard.press("Enter");
  await expect(p.getByRole("dialog")).toBeVisible();
  for (let i = 0; i < 12; i++) {
    await p.keyboard.press("Tab");
    expect(
      await p.evaluate(
        () => document.activeElement?.closest("dialog") !== null,
      ),
    ).toBe(true);
  }
  await p.keyboard.press("Escape");
  await expect(
    p.getByRole("button", { name: "Preview states", exact: true }),
  ).toBeFocused();
  await p.getByRole("button", { name: "Switch to light theme" }).click();
  await expect(p.locator("html")).toHaveAttribute("data-theme", "light");
  await p.getByRole("button", { name: "Preview states", exact: true }).click();
  await p
    .getByRole("dialog")
    .getByRole("button", { name: /Loading/ })
    .click();
  expect(
    await p
      .locator(".skeletons i")
      .first()
      .evaluate((el) => getComputedStyle(el).animationName),
  ).toBe("none");
  await capture("loading-light-760-reduced-motion");
  for (const [label, file] of [
    ["Empty", "empty"],
    ["Service error", "error"],
    ["Offline", "offline"],
  ]) {
    await p
      .getByRole("button", { name: "Preview states", exact: true })
      .click();
    await p
      .getByRole("dialog")
      .getByRole("button", { name: new RegExp(label) })
      .click();
    await p.evaluate(() => scrollTo(0, 0));
    await capture(`${file}-light-760`);
    expect(
      await p.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  }
});

test("dirty navigation to the skill path requires an explicit discard", async () => {
  const p = await app.firstWindow();
  await p.getByRole("button", { name: "Enter your next mission" }).click();
  await p.getByRole("button", { name: "Enter sample workspace" }).click();
  await p
    .getByRole("textbox", { name: "Example mission editor" })
    .fill("// preserve this thought");
  await p.getByRole("tab", { name: "Briefing", exact: true }).click();
  await p.getByRole("button", { name: "View skill path" }).click();
  await expect(
    p.getByRole("heading", { name: "Keep your thought in view." }),
  ).toBeVisible();
  await p.getByRole("button", { name: "Keep editing" }).click();
  await p.getByRole("tab", { name: "Workspace", exact: true }).click();
  await expect(
    p.getByRole("textbox", { name: "Example mission editor" }),
  ).toHaveValue("// preserve this thought");
  await p.getByRole("tab", { name: "Briefing", exact: true }).click();
  await p.getByRole("button", { name: "View skill path" }).click();
  await p.getByRole("button", { name: "Discard changes" }).click();
  await expect(p.getByRole("dialog")).toHaveCount(0);
  await expect(p.getByRole("heading", { level: 1 })).toContainText(
    "Ascension Tree",
  );
});

test("Library remains one isolated read through rapid navigation", async () => {
  const p = await app.firstWindow();
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Java Library/ })
    .click();
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Command Center/ })
    .click();
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Java Library/ })
    .click();
  await expect(p.locator(".library").getByRole("status")).toContainText(
    "8 deck descriptions",
  );
  await p.getByRole("button", { name: "Refresh from Java" }).click();
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Ascension Tree/ })
    .click();
  await p
    .getByRole("navigation")
    .getByRole("button", { name: /Java Library/ })
    .click();
  await expect(p.locator(".library").getByRole("status")).toContainText(
    "8 deck descriptions",
  );
  await expect(
    p.getByRole("heading", { name: "Connection needs attention" }),
  ).toHaveCount(0);
});

test("HiDPI rendering and representative text contrast in both themes", async () => {
  const p = await app.firstWindow();
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].setContentSize(1280, 900),
  );
  expect(await p.evaluate(() => devicePixelRatio)).toBe(2);
  await p.evaluate(() => document.fonts.ready);
  await capture("command-dark-1280-2x");
  for (const theme of ["dark", "light"]) {
    if ((await p.locator("html").getAttribute("data-theme")) !== theme)
      await p.getByRole("button", { name: `Switch to ${theme} theme` }).click();
    const ratios = await p.evaluate(() => {
      const css = getComputedStyle(document.documentElement);
      const luminance = (token: string) => {
        let hex = css.getPropertyValue(token).trim().slice(1);
        if (hex.length === 3)
          hex = hex
            .split("")
            .map((v) => v + v)
            .join("");
        const rgb = [0, 2, 4]
          .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
          .map((v) =>
            v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4,
          );
        return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
      };
      const pairs = [
        ...["--ink", "--muted", "--faint"].flatMap((f) =>
          ["--bg", "--rail", "--surface", "--surface-2", "--surface-3"].map(
            (b) => [f, b],
          ),
        ),
        ["--cyan", "--cyan-deep"],
        ["--violet", "--violet-deep"],
        ["--button-ink", "--accent"],
      ];
      return pairs.map(([f, b]) => {
        const a = luminance(f),
          z = luminance(b);
        return {
          pair: f + " / " + b,
          ratio: (Math.max(a, z) + 0.05) / (Math.min(a, z) + 0.05),
        };
      });
    });
    for (const r of ratios)
      expect(r.ratio, `${theme}: ${r.pair}`).toBeGreaterThanOrEqual(4.5);
  }
});
