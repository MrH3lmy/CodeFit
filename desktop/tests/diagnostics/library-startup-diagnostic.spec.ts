import { expectNativeLibrary } from "../e2e/library-readiness";
import { _electron as electron, expect, test } from "@playwright/test";
import { mkdtempSync, rmSync, writeFileSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
test.use({ trace: "off", screenshot: "off", video: "off" });
for (let sample = 0; sample < 3; sample++)
  test(`bounded native library startup sample ${sample}`, async () => {
    const dir = mkdtempSync(join(tmpdir(), "library-diagnostic-"));
    writeFileSync(join(dir, "codefit.db"), "REAL_PROFILE_UNCHANGED");
    const begin = performance.now();
    const app = await electron.launch({
      chromiumSandbox: true,
      executablePath: process.env.CODEFIT_DESKTOP_EXECUTABLE!,
      args: [],
      cwd: dir,
      env: {
        ...process.env,
        XDG_CONFIG_HOME: dir,
        JAVA_HOME: "/missing",
        CLASSPATH: "invalid",
        JAVA_TOOL_OPTIONS: "-invalid",
      },
    });
    try {
      const p = await app.firstWindow();
      const windowMs = performance.now() - begin;
      await app.evaluate(
        ({ ipcMain }, loadCount) => {
          const events: Array<{ stage: string; ms: number }> = [];
          const start = performance.now();
          const mark = (stage: string) => {
            if (events.length < 16)
              events.push({ stage, ms: Math.round(performance.now() - start) });
          };
          (globalThis as any).startupDiagnostic = { events, loadPids: [] };
          const cp = process.getBuiltinModule("child_process");
          const spawn = cp.spawn;
          cp.spawn = function (...args: any[]) {
            const native =
              Array.isArray(args[1]) &&
              args[1].includes("com.codefit.desktop.DesktopApplicationMain");
            if (native)
              for (let i = 0; i < loadCount; i++) {
                const a = [...args[1]];
                a[a.length - 1] += "-diagnostic-load-" + i;
                const load = (spawn as any)(args[0], a, args[2]);
                (globalThis as any).startupDiagnostic.loadPids.push(load.pid);
                load.stdout?.resume();
                load.stderr?.resume();
                load.stdin?.end();
              }
            const child = (spawn as any)(...args);
            if (native) {
              mark("java_spawn");
              (globalThis as any).startupDiagnostic.pid = child.pid;
              let seen = false,
                buffer = "";
              child.stdout?.on("data", (b: Buffer) => {
                if (!seen && buffer.length + b.length < 65536) {
                  buffer += b.toString();
                  if (buffer.includes('"ready"')) {
                    seen = true;
                    mark("java_ready");
                    buffer = "";
                  }
                }
              });
              child.on("close", () => mark("java_closed"));
            }
            return child;
          } as any;
          for (const [channel, label] of [
            ["codefit:state:read", "state"],
            ["codefit:deck:pending", "pending"],
          ]) {
            const original = (ipcMain as any)._invokeHandlers.get(channel);
            ipcMain.removeHandler(channel);
            ipcMain.handle(channel, async (event, ...args) => {
              mark(label + "_enter");
              const r = await original(event, ...args);
              mark(label + (r.ok ? "_success" : "_failure"));
              return r;
            });
          }
        },
        Math.min(2, Math.max(0, Number(process.env.CODEFIT_STARTUP_LOAD || 0))),
      );
      const click = performance.now();
      await p
        .getByRole("navigation")
        .getByRole("button", { name: /Java Library/ })
        .click();
      const assertionStart = performance.now();
      // Observe the existing supervised startup window; this investigation does not change production or existing tests.
      let readinessFailure: unknown;
      try {
        await expectNativeLibrary(p);
      } catch (failure) {
        readinessFailure = failure;
      }
      const visibleMs = Math.round(performance.now() - click),
        assertionMs = Math.round(performance.now() - assertionStart);
      const diag = await app.evaluate(() => {
        const d = (globalThis as any).startupDiagnostic;
        return { events: d.events, pid: d.pid, loadPids: d.loadPids };
      });
      const closeStart = performance.now();
      await app.close();
      await expect
        .poll(() => {
          return [diag.pid, ...diag.loadPids].every((pid) => {
            try {
              return (
                readFileSync(`/proc/${pid}/stat`, "utf8").split(") ")[1][0] ===
                "Z"
              );
            } catch {
              return true;
            }
          });
        })
        .toBe(true);
      console.log(
        JSON.stringify({
          sample,
          windowMs: Math.round(windowMs),
          visibleMs,
          assertionMs,
          cleanupMs: Math.round(performance.now() - closeStart),
          loadProcesses: diag.loadPids.length,
          outcome: readinessFailure ? "NOT_READY" : "READY",
          events: diag.events,
        }),
      );
      expect(readFileSync(join(dir, "codefit.db"), "utf8")).toBe(
        "REAL_PROFILE_UNCHANGED",
      );
      if (readinessFailure) throw readinessFailure;
      expect(
        diag.events.some(
          (event: { stage: string }) => event.stage === "java_ready",
        ),
      ).toBe(true);
      expect(
        diag.events.some(
          (event: { stage: string }) => event.stage === "state_success",
        ),
      ).toBe(true);
      expect(
        diag.events.some(
          (event: { stage: string }) => event.stage === "pending_success",
        ),
      ).toBe(true);
    } finally {
      await app.close().catch(() => {});
      rmSync(dir, { recursive: true, force: true });
    }
  });
