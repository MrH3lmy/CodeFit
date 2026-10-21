import {
  app,
  BrowserWindow,
  ipcMain,
  session,
  type IpcMainInvokeEvent,
} from "electron";
import { join, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { JavaSession, runtimeLocation } from "./java-session";
import { type Result } from "../src/contract";
import { EngineOwner } from "./engine-owner";

app.setName("CodeFit Gate 0");
// Deliberately independent from JavaFX preferences and every learner DB location.
app.setPath("userData", join(app.getPath("appData"), "CodeFit-Gate0"));
app.commandLine.appendSwitch("disable-background-networking");
app.commandLine.appendSwitch("disable-component-update");
app.commandLine.appendSwitch("disable-sync");
app.commandLine.appendSwitch("no-pings");

let window: BrowserWindow | undefined;
let quitting = false;
let quitReady = false;
const contentRoot = resolve(__dirname, "..", "dist");
const entry = pathToFileURL(join(contentRoot, "index.html")).href;
const javaRoot = app.isPackaged
  ? join(process.resourcesPath, "java")
  : resolve(__dirname, "..", "java");
const engine = new EngineOwner(
  () => new JavaSession(runtimeLocation(javaRoot)),
);

function authorized(event: IpcMainInvokeEvent, args: unknown[]): boolean {
  return (
    args.length === 0 &&
    !!window &&
    !window.isDestroyed() &&
    !event.sender.isDestroyed() &&
    event.sender === window.webContents &&
    event.senderFrame === window.webContents.mainFrame &&
    event.senderFrame?.url === entry
  );
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => {
    if (window?.isMinimized()) window.restore();
    window?.focus();
  });
  app.on("before-quit", (event) => {
    if (quitReady) return;
    event.preventDefault();
    if (quitting) return;
    quitting = true;
    void engine.shutdown().finally(() => {
      quitReady = true;
      app.quit();
    });
  });
  app.on("window-all-closed", () => app.quit());
  void app
    .whenReady()
    .then(async () => {
      const isolated = session.fromPartition("codefit-gate0"); // ephemeral Chromium session
      isolated.setPermissionRequestHandler((_web, _permission, callback) =>
        callback(false),
      );
      isolated.setPermissionCheckHandler(() => false);
      isolated.on("will-download", (event) => event.preventDefault());
      isolated.webRequest.onBeforeRequest((details, callback) => {
        let allowed = false;
        try {
          if (details.url.startsWith("file:")) {
            const path = resolve(fileURLToPath(details.url));
            allowed = path.startsWith(contentRoot + sep);
          }
        } catch {
          /* reject invalid URLs */
        }
        callback({ cancel: !allowed });
      });
      window = new BrowserWindow({
        width: 1280,
        height: 900,
        minWidth: 760,
        minHeight: 640,
        show: false,
        title: "CodeFit · The Ascent — Gate 1 preview",
        backgroundColor: "#080e1b",
        webPreferences: {
          preload: join(__dirname, "preload.cjs"),
          session: isolated,
          contextIsolation: true,
          sandbox: true,
          nodeIntegration: false,
          webSecurity: true,
          allowRunningInsecureContent: false,
          webviewTag: false,
          devTools: !app.isPackaged,
        },
      });
      window.removeMenu();
      window.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
      window.webContents.on("will-navigate", (event) => event.preventDefault());
      window.webContents.on("will-attach-webview", (event) =>
        event.preventDefault(),
      );
      window.webContents.on("render-process-gone", () => {
        void engine.stop();
      });
      window.on("closed", () => {
        window = undefined;
        void engine.shutdown();
      });
      ipcMain.handle(
        "codefit:decks:list",
        async (event, ...args: unknown[]): Promise<Result> => {
          return engine.read(() => !quitting && authorized(event, args));
        },
      );
      ipcMain.handle(
        "codefit:read:cancel",
        async (event, ...args: unknown[]) => {
          if (authorized(event, args)) await engine.stop();
        },
      );
      await window.loadFile(join(contentRoot, "index.html"));
      window.show();
    })
    .catch(() => app.quit());
}
