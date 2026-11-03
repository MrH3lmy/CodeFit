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
  existsSync,
} from "node:fs";
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
  const fixtures = resolve("test-results", "curriculum-fixtures");
  mkdirSync(fixtures, { recursive: true });
  dir = mkdtempSync(join(fixtures, "codefit-atlas-e2e-"));
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
  const externalRequests: string[] = [];
  const browserLogs: string[] = [];
  const browserErrors: string[] = [];
  p.on("request", (request) => {
    if (/^https?:\/\//i.test(request.url())) externalRequests.push(request.url());
  });
  p.on("console", (message) => {
    browserLogs.push(message.text());
    if (message.type() === "error") browserErrors.push(message.text());
  });
  p.on("pageerror", (error) => browserErrors.push(error.message));
  await p.context().setOffline(true);
  await open(p);
  await loaded(p);
  await expect(p.locator(".atlas-cards .atlas-card")).toHaveCount(4);
  await expect(p.locator(".atlas-book-card")).toHaveCount(5);
  const result = await p.evaluate(() => window.codefit.readCurriculumPreview());
  expect(result.ok).toBe(true);
  if (!result.ok) throw Error(result.code);
  expect(result.result.v).toBe(2);
  const coverage = result.result.coverage;
  expect(coverage.books.map((b) => [b.title, b.inventory.count])).toEqual([
    ["OCP Java SE 17 Guide", 26],
    ["Effective Java", 90],
    ["100 Java Mistakes", 100],
    ["Java Coding Problems", 269],
    ["Complete Coding Interview Guide in Java", 19],
  ]);
  expect(coverage.reportedClaims).toMatchObject({
    structuralUnits: 504,
    preliminaryMappedUnits: 157,
    reportedUnmappedUnits: 347,
    missionBlueprintSlots: 419,
    revisionEvents: { count: 424, additiveToMissionBlueprintSlots: false },
    independentApprovals: 0,
    hunterProductionImports: 0,
  });
  await expect(p.getByLabel("Historical reported totals")).toContainText("Nonadditive");
  await expect(p.getByLabel("Source-attributed book coverage")).toContainText(coverage.provenance.source);
  for (const book of coverage.books) {
    expect(book.inventory.status).toBe("REPORTED_NOT_ENUMERATED");
    expect(book.preliminaryMapped.status).toBe("UNKNOWN");
    expect(book.independentReview).toMatchObject({ count: 0, status: "ZERO_REPORTED" });
    expect(book.runtimeLearning).toMatchObject({ count: 0, status: "ZERO_REPORTED" });
    const card = p.locator(".atlas-book-card").filter({ hasText: book.id });
    await expect(card).toContainText("Preliminary mapping Unknown, not supplied");
    await expect(card).toContainText("Independent review 0 · Reported zero, not independently verified");
    await expect(card).toContainText("Runtime imports 0 · Reported zero, not independently verified");
    await expect(card).toContainText("Learner validation Unknown, not supplied");
    await card.focus();
    await p.keyboard.press("Enter");
    await expect(card).toHaveAttribute("aria-pressed", "true");
    const inspector = p.getByRole("region", { name: "Selected book details" });
    await expect(inspector.getByRole("heading", { level: 2 })).toHaveText(book.title);
    await expect(inspector).toContainText("Learner validation");
    await expect(inspector).toContainText("Unknown, not supplied");
    await expect(inspector).toContainText("Follow-up gaps");
  }
  const bookInspector = p.getByRole("region", { name: "Selected book details" });
  const detailButton = p.getByRole("button", { name: "Go to selected book details" });
  await p.getByRole("combobox", { name: "Author-draft status" }).selectOption("first-pass");
  await expect(p.locator(".atlas-book-card")).toHaveCount(1);
  await expect(p.locator(".atlas-book-card")).toContainText("Effective Java");
  await expect(p.locator(".atlas-book-card")).toHaveAttribute("aria-pressed", "true");
  await expect(bookInspector.getByRole("heading", { level: 2 })).toHaveText("Effective Java");
  await expect(bookInspector).toContainText("90/90 first-pass author notes");
  await detailButton.click();
  await expect(p.locator(".atlas-book-inspector h2")).toBeFocused();
  await p.getByText("Book coverage provenance", { exact: true }).click();
  await expect(p.getByLabel("Public coverage ledger checksum")).toHaveText(coverage.ledgerSha256);
  await p.getByText("Book coverage provenance", { exact: true }).click();
  await p.getByRole("combobox", { name: "Author-draft status" }).selectOption("unknown-author");
  await expect(p.locator(".atlas-book-card")).toHaveCount(4);
  await expect(bookInspector.getByRole("heading", { level: 2 })).toHaveText("OCP Java SE 17 Guide");
  await expect(p.locator(".atlas-book-card").first()).toHaveAttribute("aria-pressed", "true");
  await p.getByLabel("Find a reported book").fill("java-coding-problems");
  await expect(p.locator(".atlas-book-card")).toHaveCount(1);
  await expect(bookInspector.getByRole("heading", { level: 2 })).toHaveText("Java Coding Problems");
  await p.locator(".atlas-book-card").focus();
  await p.keyboard.press("Enter");
  await expect(p.locator(".atlas-book-card")).toHaveAttribute("aria-pressed", "true");
  await p.getByLabel("Find a reported book").fill("not-a-book");
  await expect(p.getByText("No matching reported books.")).toBeVisible();
  await expect(bookInspector).toContainText("No book matches the current filters.");
  await expect(bookInspector.getByRole("heading", { level: 2 })).toHaveCount(0);
  await expect(detailButton).toBeDisabled();
  await p.getByRole("button", { name: "Clear book filters" }).click();
  await expect(p.locator(".atlas-book-card")).toHaveCount(5);
  await expect(detailButton).toBeEnabled();
  await expect(bookInspector.getByRole("heading", { level: 2 })).toHaveText("Java Coding Problems");
  for (const skill of result.result.preview.skills) {
    await p.locator(".atlas-cards .atlas-card").filter({ hasText: skill.id }).click();
    await expect(p.getByRole("region", { name: "Selected draft details" }).getByRole("heading", { level: 2 })).toHaveText(skill.title);
    await expect(p.getByRole("region", { name: "Selected draft details" })).toContainText("Skill objectives");
    await expect(p.getByRole("region", { name: "Selected draft details" })).toContainText("UNKNOWN · not supplied in author metadata.");
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
  const firstSkill = result.result.preview.skills[0];
  await p.getByLabel("Find a draft skill").fill(firstSkill.stages[0].id);
  await expect(p.locator(".atlas-cards .atlas-card")).toHaveCount(1);
  await p.getByLabel("Find a draft skill").fill(firstSkill.prerequisites[0]);
  await expect(p.locator(".atlas-cards .atlas-card").filter({ hasText: firstSkill.id })).toHaveCount(1);
  await p.getByLabel("Find a draft skill").fill("");
  await p.locator(".atlas-cards .atlas-card").first().click();
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
  expect(externalRequests).toEqual([]);
  expect(browserErrors).toEqual([]);
  expect(browserLogs.join("\n")).not.toMatch(/private_checkpoint|PRIVATE-|answerKey/);
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
  await expect(p.locator(".atlas-book-card")).toHaveCount(0);
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
  await p.locator(".atlas-cards .atlas-card").last().click();
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
  await expect(p.getByRole("region", { name: "Source-attributed book coverage" })).toHaveCount(0);
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
  await expect(p.locator(".atlas-book-card")).toHaveCount(0);
});

test("corrupt, stale and source-mismatched coverage fails closed without retained metadata", async () => {
  const p = await app.firstWindow();
  await open(p);
  await loaded(p);
  const original = await p.evaluate(() => window.codefit.readCurriculumPreview());
  expect(original.ok).toBe(true);
  if (!original.ok) throw Error(original.code);
  for (const mutation of [
    "stale-version",
    "stale-source",
    "source-mismatch",
    "pin-mismatch",
    "corrupt-count",
    "invented-title",
    "promoted-review",
    "missing-coverage",
  ]) {
    await app.evaluate(({ ipcMain }, { original, mutation }) => {
      const reply: any = structuredClone(original);
      switch (mutation) {
        case "stale-version": reply.result.v = 1; break;
        case "stale-source": reply.result.coverage.provenance.sourceVersion = "v0.40"; break;
        case "source-mismatch": reply.result.coverage.provenance.source = "Unverified substitute source"; break;
        case "pin-mismatch": reply.result.coverage.ledgerSha256 = "0".repeat(64); break;
        case "corrupt-count": reply.result.coverage.reportedClaims.structuralUnits = 505; break;
        case "invented-title": reply.result.coverage.books[0].title = "Fabricated book"; break;
        case "promoted-review": reply.result.coverage.books[0].independentReview.count = 90; break;
        case "missing-coverage": delete reply.result.coverage; break;
      }
      ipcMain.removeHandler("codefit:curriculum:read");
      ipcMain.handle("codefit:curriculum:read", () => reply);
    }, { original, mutation });
    const recheck = p.getByRole("button", { name: "Recheck catalog" });
    if (await recheck.count()) await recheck.click();
    else await p.getByRole("button", { name: "Retry preview read" }).click();
    await expect(p.getByRole("alert")).toContainText("could not be verified");
    await expect(p.locator(".atlas-card")).toHaveCount(0);
    await expect(p.getByRole("region", { name: "Source-attributed book coverage" })).toHaveCount(0);
    await expect(p.getByRole("region", { name: "Selected draft details" })).toHaveCount(0);
    await expect(p.getByText("Fabricated book")).toHaveCount(0);
    await expect(p.getByLabel("Public catalog checksum")).toHaveCount(0);
  }
});

test("cancelling a recheck clears both previously displayed catalogs and fences late coverage", async () => {
  const p = await app.firstWindow();
  await open(p);
  await loaded(p);
  await app.evaluate(({ ipcMain }) => {
    const original = (ipcMain as any)._invokeHandlers.get("codefit:curriculum:read");
    ipcMain.removeHandler("codefit:curriculum:read");
    ipcMain.handle("codefit:curriculum:read", async (event, ...args) => {
      const reply = await original(event, ...args);
      await new Promise<void>((resolve) => { (globalThis as any).releaseCoverageRecheck = resolve; });
      return reply;
    });
  });
  await p.getByRole("button", { name: "Recheck catalog" }).click();
  await expect(p.getByRole("button", { name: "Cancel preview read" })).toBeVisible();
  await expect(p.getByRole("region", { name: "Selected book details" })).toHaveCount(0);
  await expect(p.getByRole("region", { name: "Selected draft details" })).toHaveCount(0);
  await expect.poll(() => app.evaluate(() => typeof (globalThis as any).releaseCoverageRecheck)).toBe("function");
  await p.getByRole("button", { name: "Cancel preview read" }).click();
  await app.evaluate(() => (globalThis as any).releaseCoverageRecheck());
  await expect(p.getByRole("alert")).toContainText("cancelled");
  await expect(p.locator(".atlas-card")).toHaveCount(0);
  await expect(p.getByLabel("Historical reported totals")).toHaveCount(0);
});
