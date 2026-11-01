import { GraphResponse } from "../../src/graph-contract";
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
  mkdirSync,
  writeFileSync,
  readFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { seedGraphs } from "../graph-fixture";
import { seedAnalytics, databaseHashes } from "../analytics-fixture";
import { expectPracticeReady } from "./practice-readiness";
test.use({ trace: "off", screenshot: "off", video: "off" });
test("different graphs refresh the authoritative header, persist, recover receipts and roll back without rewriting history", async () => {
  test.setTimeout(120000);
  const dir = mkdtempSync(join(tmpdir(), "cross-graphs-"));
  let app: ElectronApplication | undefined;
  const launch = () =>
    electron.launch({
      chromiumSandbox: true,
      executablePath:
        process.env.CODEFIT_DESKTOP_EXECUTABLE ||
        (electronPath as unknown as string),
      args: process.env.CODEFIT_DESKTOP_EXECUTABLE ? [] : [resolve(".")],
      cwd: dir,
      env: { ...process.env, XDG_CONFIG_HOME: dir, JAVA_HOME: "/missing" },
    });
  async function close() {
    if (app) {
      await app.evaluate(async ({ BrowserWindow }) => {
        await BrowserWindow.getAllWindows()[0].webContents.executeJavaScript(
          "window.codefit.disconnectPractice()",
        );
      });
      app.process().kill("SIGKILL");
      await app.close().catch(() => {});
      app = undefined;
    }
  }
  async function enter() {
    const p = await app!.firstWindow();
    await p.getByRole("button", { name: /Engineering Graphs Inspect/ }).click();
    await p
      .getByRole("button", { name: "Connect disposable practice profile" })
      .click();
    await expectPracticeReady(
      p.getByRole("button", { name: "Read graph catalog / recover" }),
    );
    await p
      .getByRole("button", { name: "Read graph catalog / recover" })
      .click();
    await expect(
      p.getByRole("region", { name: "Selected planning graph", exact: true }),
    ).toBeVisible();
    return p;
  }
  async function choose(p: Page, version: number) {
    await p
      .getByRole("button", {
        name: `Inspect java-debugging-concurrency v${version}`,
        exact: true,
      })
      .click();
    await expect(
      p.getByRole("region", { name: "Graph definitions" }),
    ).toContainText(`java-debugging-concurrency v${version}`);
    await p.getByRole("button", { name: "Choose this exact graph" }).click();
    await p
      .getByRole("button", { name: "Confirm exact graph selection" })
      .click();
  }
  try {
    writeFileSync(join(dir, "codefit.db"), "REAL_PROFILE_UNCHANGED");
    app = await launch();
    const base = await app.evaluate(({ app }) => app.getPath("userData"));
    await close();
    const profile = join(base, "experimental-practice-v1");
    seedAnalytics(profile);
    seedGraphs(profile);
    const file = join(profile, "codefit.db");
    const before = databaseHashes(file);
    app = await launch();
    let p = await enter();
    const header = () =>
      p.getByRole("region", { name: "Selected planning graph", exact: true });
    await expect(header()).toContainText("v1 · selection revision 0");
    await app.evaluate(({ ipcMain }) => {
      const original = (ipcMain as any)._invokeHandlers.get(
        "codefit:practice:graphs",
      );
      ipcMain.removeHandler("codefit:practice:graphs");
      ipcMain.handle("codefit:practice:graphs", async (event, ...args) => {
        const result = await original(event, ...args);
        if ((args[0] as any)?.kind === "select") {
          (globalThis as any).originalSelection = result;
          (globalThis as any).holdGraphRefresh = true;
        } else if (
          (args[0] as any)?.kind === "read" &&
          (globalThis as any).holdGraphRefresh
        ) {
          (globalThis as any).holdGraphRefresh = false;
          return new Promise((resolve) => {
            (globalThis as any).releaseGraphRefresh = () => resolve(result);
          });
        }
        return result;
      });
    });
    await choose(p, 2);
    await expect
      .poll(() =>
        app!.evaluate(() => !!(globalThis as any).releaseGraphRefresh),
      )
      .toBe(true);
    await expect(header()).toHaveCount(0);
    await expect(
      p.getByRole("status").filter({ hasText: "Saved receipt:" }),
    ).toContainText("SELECTED");
    await app.evaluate(() => (globalThis as any).releaseGraphRefresh());
    await expect(header()).toContainText("v2 · selection revision 1");
    await expect(
      p.getByRole("status").filter({ hasText: "Saved receipt:" }),
    ).toContainText("SELECTED");
    await expect(
      p.getByRole("region", { name: "Graph definitions" }),
    ).toContainText("Practice: UNAVAILABLE");
    const screenshots = resolve(
      "../docs/architecture/gate2-graph-followups/screenshots",
    );
    mkdirSync(screenshots, { recursive: true });
    for (const theme of ["dark", "light"]) {
      if ((await p.locator("html").getAttribute("data-theme")) !== theme)
        await p
          .getByRole("button", { name: `Switch to ${theme} theme` })
          .click();
      for (const width of [1280, 760]) {
        await p.setViewportSize({ width, height: 1000 });
        await p.screenshot({
          path: join(screenshots, `selected-v2-${theme}-${width}.png`),
          fullPage: true,
        });
      }
    }
    // Java, not renderer labels, enforces the unsupported adapter gate.
    const state = await p.evaluate(async () => {
      const i = await window.codefit.connectPractice();
      if (!i.ok) throw Error();
      const original = i.presentations[0];
      if (!original) throw Error("Expected real fixture presentation");
      const resumed = await window.codefit.resumePractice(
        original.presentationId,
        i.profileId,
        i.sessionId,
      );
      if (!resumed.ok) throw Error("Original exposure must remain resumable");
      return {
        candidates: i.candidates.length,
        skills: await window.codefit.practiceSkillAction(
          "publish",
          i.profileId,
          i.sessionId,
        ),
      };
    });
    expect(state.candidates).toBe(0);
    expect(
      state.skills.ok &&
        "reconciliation" in state.skills &&
        state.skills.reconciliation?.state,
    ).toBe("REJECTED");
    const receipt = await app.evaluate(
      () => (globalThis as any).originalSelection,
    );
    expect(receipt.receipt.state).toBe("SELECTED");
    await close();
    // Simulate death after Java commit but before main clears the original durable metadata intent.
    const journal = join(base, "experimental-practice-operations-v1", "graphs");
    mkdirSync(journal, { recursive: true, mode: 0o700 });
    writeFileSync(
      join(journal, "pending-graph.json"),
      JSON.stringify({
        v: 1,
        operation: "graph-selection-v1",
        profileId: receipt.profileId,
        originSessionId: receipt.receipt.originSessionId,
        commandId: receipt.receipt.commandId,
        ...receipt.receipt.target,
        selectionRevision: receipt.receipt.expectedRevision,
      }),
      { mode: 0o600 },
    );
    app = await launch();
    p = await enter();
    await expect(header()).toContainText("v2 · selection revision 1");
    await p.getByRole("button", { name: "Check selection status" }).click();
    await expect(
      p.getByRole("status").filter({ hasText: "Saved receipt:" }),
    ).toContainText("SELECTED");
    await expect(header()).toContainText("v2 · selection revision 1");
    await expect(
      p.getByRole("region", { name: "Graph selection recovery" }),
    ).toHaveCount(0);
    await choose(p, 1);
    await expect(header()).toContainText("v1 · selection revision 2");
    await p
      .getByRole("button", {
        name: "Inspect synthetic.retired v1",
        exact: true,
      })
      .click();
    await expect(
      p.getByRole("button", { name: "Choose this exact graph" }),
    ).toBeDisabled();
    await p
      .getByRole("button", {
        name: "Inspect synthetic.incompatible v1",
        exact: true,
      })
      .click();
    await expect(
      p.getByRole("button", { name: "Choose this exact graph" }),
    ).toBeDisabled();
    await expect(
      p.getByRole("region", { name: "Graph definitions" }),
    ).toContainText("selection: RETIRED_SKILL");
    const stale = await p.evaluate(async () => {
      const i = await window.codefit.connectPractice();
      if (!i.ok) throw Error();
      return window.codefit.practiceGraphAction(
        {
          kind: "select",
          graphId: "java-debugging-concurrency",
          graphVersion: 2,
          selectionRevision: 0,
        },
        i.profileId,
        i.sessionId,
      );
    });
    expect(GraphResponse.parse(stale).receipt?.reason).toBe(
      "SELECTION_CONFLICT",
    );
    await p
      .getByRole("button", { name: "Read graph catalog / recover" })
      .click();
    await expect(header()).toContainText("v1 · selection revision 2");
    const after = databaseHashes(file);
    for (const table of Object.keys(before)) {
      if (
        table.startsWith("evolution_graph_selection_") ||
        table.startsWith("desktop_skill_")
      )
        continue;
      expect(after[table], table).toEqual(before[table]);
    }
    await close();
    expect(readFileSync(join(dir, "codefit.db"), "utf8")).toBe(
      "REAL_PROFILE_UNCHANGED",
    );
  } finally {
    await close();
    rmSync(dir, { recursive: true, force: true });
  }
});
