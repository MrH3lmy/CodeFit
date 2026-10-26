import {
  app,
  BrowserWindow,
  dialog,
  ipcMain,
  session,
  type IpcMainInvokeEvent,
} from "electron";
import { join, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { PracticeOwner } from "./practice-owner";
import { PracticeSession } from "./practice-session";
import { PracticeJournal } from "./practice-journal";
import { JavaSession, runtimeLocation } from "./java-session";
import { DesktopApplication } from "./desktop-application";
import { OperationJournal } from "./operation-journal";
import { EngineOwner } from "./engine-owner";
import { CurriculumOwner, CurriculumSession } from "./curriculum-session";
import { LearnerOwner } from "./learner-session";
import type { LearnerResult } from "../src/learner-contract";

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
  () =>
    new JavaSession(
      runtimeLocation(javaRoot),
      join(app.getPath("userData"), "experimental-library-v1"),
    ),
);

const practice = new PracticeOwner(
  () =>
    new PracticeSession(
      runtimeLocation(javaRoot),
      join(app.getPath("userData"), "experimental-practice-v1"),
    ),
  new PracticeJournal(
    join(app.getPath("userData"), "experimental-practice-operations-v1"),
  ),
);

const learner = new LearnerOwner(
  () => runtimeLocation(javaRoot),
  join(app.getPath("userData"), "learner-snapshots-v1"),
);
const curriculum = new CurriculumOwner(
  process.argv.includes("--enable-curriculum-preview-local"),
  () => new CurriculumSession(runtimeLocation(javaRoot)),
);
let learnerDialogBusy = false;
let learnerGeneration = 0;

const application = new DesktopApplication(
  engine,
  new OperationJournal(
    join(app.getPath("userData"), "experimental-operations-v1"),
  ),
);

function authorized(
  event: IpcMainInvokeEvent,
  args: unknown[],
  count: number,
): boolean {
  return (
    args.length === count &&
    !!window &&
    !window.isDestroyed() &&
    !event.sender.isDestroyed() &&
    event.sender === window.webContents &&
    event.senderFrame === window.webContents.mainFrame &&
    event.senderFrame?.url === entry
  );
}

let practiceDirty = false;
let draftUnloadApproved = false;
function confirmDraftLoss(force = false): boolean {
  if ((!practiceDirty && !force) || !window) return true;
  const discard =
    dialog.showMessageBoxSync(window, {
      type: "warning",
      buttons: ["Keep editing", "Discard unconfirmed edits"],
      defaultId: 0,
      cancelId: 0,
      title: "Unconfirmed Java draft",
      message: "Only Java-acknowledged saves survive restart.",
      detail:
        "There are unsaved edits or unresolved save recovery. A forced exit cannot guarantee saving them.",
    }) === 1;
  if (discard) {
    practiceDirty = false;
    draftUnloadApproved = true;
  }
  return discard;
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
    if (!confirmDraftLoss()) return;
    quitting = true;
    learnerGeneration++;
    void Promise.allSettled([
      engine.shutdown(),
      practice.disconnect(),
      learner.shutdown(),
      curriculum.shutdown(),
    ]).finally(() => {
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
        title: "CodeFit · The Ascent",
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
      window.on("close", (event) => {
        if (!confirmDraftLoss()) event.preventDefault();
      });
      window.webContents.on("will-prevent-unload", (event) => {
        // Renderer beforeunload is authoritative even if its dirty IPC has not arrived yet.
        if (draftUnloadApproved || confirmDraftLoss(true))
          event.preventDefault();
        draftUnloadApproved = false;
      });

      window.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
      window.webContents.on("will-navigate", (event) => event.preventDefault());
      window.webContents.on("will-attach-webview", (event) =>
        event.preventDefault(),
      );
      window.webContents.on(
        "did-start-navigation",
        (_event, _url, _inPlace, mainFrame) => {
          if (mainFrame) {
            learnerGeneration++;
            void learner.disconnect();
            void curriculum.cancel();
            void practice.disconnect();
          }
        },
      );
      window.webContents.on("render-process-gone", () => {
        practiceDirty = false;
        learnerGeneration++;
        void engine.stop();
        void learner.disconnect();
        void curriculum.cancel();
        void practice.disconnect();
      });
      window.on("closed", () => {
        window = undefined;
        learnerGeneration++;
        void engine.shutdown();
        void learner.shutdown();
        void curriculum.shutdown();
        void practice.disconnect();
      });
      ipcMain.handle("codefit:practice:dirty", (event, ...args: unknown[]) => {
        if (authorized(event, args, 1) && typeof args[0] === "boolean") {
          practiceDirty = args[0];
          if (practiceDirty) draftUnloadApproved = false;
        }
      });
      ipcMain.handle("codefit:practice:connect", (event, ...args: unknown[]) =>
        practice.connect(
          () => !quitting && !learnerDialogBusy && authorized(event, args, 0),
        ),
      );
      ipcMain.handle("codefit:practice:open", (event, ...args: unknown[]) =>
        practice.open(
          args[0],
          args[1],
          args[2],
          () => !quitting && !learnerDialogBusy && authorized(event, args, 3),
        ),
      );
      ipcMain.handle("codefit:practice:resume", (event, ...args: unknown[]) =>
        practice.resume(
          args[0],
          args[1],
          args[2],
          () => !quitting && !learnerDialogBusy && authorized(event, args, 3),
        ),
      );
      ipcMain.handle(
        "codefit:practice:draft-read",
        (event, ...args: unknown[]) =>
          practice.readDraft(
            args[0],
            args[1],
            args[2],
            () => !quitting && !learnerDialogBusy && authorized(event, args, 3),
          ),
      );
      ipcMain.handle(
        "codefit:practice:draft-save",
        (event, ...args: unknown[]) =>
          practice.saveDraft(
            args[0],
            args[1],
            args[2],
            () => !quitting && !learnerDialogBusy && authorized(event, args, 3),
          ),
      );
      ipcMain.handle(
        "codefit:practice:draft-reconcile",
        (event, ...args: unknown[]) =>
          practice.reconcileDraft(
            args[0],
            args[1],
            args[2],
            () => !quitting && !learnerDialogBusy && authorized(event, args, 3),
          ),
      );
      ipcMain.handle("codefit:practice:recover", (event, ...args: unknown[]) =>
        practice.recover(
          args[0],
          args[1],
          () => !quitting && !learnerDialogBusy && authorized(event, args, 2),
        ),
      );
      ipcMain.handle(
        "codefit:practice:acknowledge",
        (event, ...args: unknown[]) =>
          practice.acknowledge(
            args[0],
            args[1],
            args[2],
            () => !quitting && !learnerDialogBusy && authorized(event, args, 3),
          ),
      );
      ipcMain.handle(
        "codefit:practice:disconnect",
        async (event, ...args: unknown[]) => {
          if (!quitting && authorized(event, args, 0))
            await practice.disconnect();
        },
      );
      ipcMain.handle("codefit:curriculum:read", (event, ...args: unknown[]) =>
        curriculum.read(() => !quitting && authorized(event, args, 0)),
      );
      ipcMain.handle(
        "codefit:curriculum:cancel",
        async (event, ...args: unknown[]) => {
          if (!quitting && authorized(event, args, 0))
            await curriculum.cancel();
        },
      );
      ipcMain.handle(
        "codefit:learner:choose",
        async (event, ...args: unknown[]): Promise<LearnerResult> => {
          if (quitting || !authorized(event, args, 0))
            return { ok: false, code: "UNAVAILABLE" };
          if (learnerDialogBusy) return { ok: false, code: "BUSY" };
          learnerDialogBusy = true;
          const generation = ++learnerGeneration;
          const permitted = () =>
            !quitting &&
            generation === learnerGeneration &&
            authorized(event, args, 0);
          const owner = window!;
          try {
            await practice.disconnect();
            await learner.disconnect();
            if (!permitted()) return { ok: false, code: "CANCELLED" };
            // No renderer-supplied path, SQL, command, runtime or backup destination is accepted.
            const choice = await dialog.showOpenDialog(owner, {
              title: "Choose a CodeFit learner database",
              defaultPath: join(process.cwd(), "codefit.db"),
              buttonLabel: "Select profile",
              properties: ["openFile"],
              filters: [
                {
                  name: "SQLite database",
                  extensions: ["db", "sqlite", "sqlite3"],
                },
                { name: "All files", extensions: ["*"] },
              ],
            });
            if (!permitted()) return { ok: false, code: "CANCELLED" };
            if (choice.canceled || choice.filePaths.length !== 1)
              return { ok: false, code: "CANCELLED" };
            const confirmation = await dialog.showMessageBox(owner, {
              type: "question",
              title: "Connect a read-only learner snapshot",
              message: "Create a private snapshot of this learner profile?",
              detail: `Selected file: ${JSON.stringify(choice.filePaths[0])}\n\nCodeFit will read this database and keep a full private backup on this device, including sensitive local profile content. Learning records are never modified, migrated or replaced. SQLite may create or update lock and shared-memory coordination files. Close JavaFX first: older JavaFX versions and external tools may ignore ownership locks. This view shows the capture time; select the profile again for newer data. Storage is limited to eight captures; archive backups before making more.`,
              buttons: ["Cancel", "Connect read-only snapshot"],
              defaultId: 0,
              cancelId: 0,
              noLink: true,
            });
            if (!permitted() || confirmation.response !== 1)
              return { ok: false, code: "CANCELLED" };
            return await learner.connect(choice.filePaths[0], permitted);
          } catch {
            return { ok: false, code: "UNAVAILABLE" };
          } finally {
            learnerDialogBusy = false;
          }
        },
      );
      ipcMain.handle("codefit:learner:read", (event, ...args: unknown[]) => {
        const generation = learnerGeneration;
        return learner.read(
          () =>
            !quitting &&
            generation === learnerGeneration &&
            authorized(event, args, 0),
        );
      });
      ipcMain.handle(
        "codefit:learner:missions",
        (event, ...args: unknown[]) => {
          const generation = learnerGeneration;
          return learner.readMissions(
            () =>
              !quitting &&
              generation === learnerGeneration &&
              authorized(event, args, 0),
          );
        },
      );
      ipcMain.handle("codefit:learner:skills", (event, ...args: unknown[]) => {
        const generation = learnerGeneration;
        return learner.readSkills(
          () =>
            !quitting &&
            generation === learnerGeneration &&
            authorized(event, args, 0),
        );
      });
      ipcMain.handle(
        "codefit:learner:disconnect",
        async (event, ...args: unknown[]) => {
          if (!quitting && authorized(event, args, 0)) {
            learnerGeneration++;
            await practice.disconnect();
            await learner.disconnect();
          }
        },
      );
      ipcMain.handle("codefit:state:read", (event, ...args: unknown[]) =>
        application.read(
          args[0],
          () => !quitting && authorized(event, args, 1),
        ),
      );
      ipcMain.handle("codefit:deck:create", (event, ...args: unknown[]) =>
        application.create(
          args[0],
          () => !quitting && authorized(event, args, 1),
        ),
      );
      ipcMain.handle("codefit:deck:pending", (event, ...args: unknown[]) =>
        application.pending(() => !quitting && authorized(event, args, 0)),
      );
      ipcMain.handle("codefit:deck:recover", (event, ...args: unknown[]) =>
        application.recover(() => !quitting && authorized(event, args, 0)),
      );
      ipcMain.handle("codefit:deck:acknowledge", (event, ...args: unknown[]) =>
        application.acknowledge(
          args[0],
          () => !quitting && authorized(event, args, 1),
        ),
      );
      ipcMain.handle(
        "codefit:operation:cancel",
        async (event, ...args: unknown[]) => {
          if (!quitting && authorized(event, args, 0)) await engine.stop();
        },
      );
      await window.loadFile(join(contentRoot, "index.html"));
      window.show();
    })
    .catch(() => app.quit());
}
