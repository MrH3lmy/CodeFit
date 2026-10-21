// Record the actual Electron renderer; never substitutes a browser mock.
import { _electron as electron } from "@playwright/test";
import electronPath from "electron";
import { spawn } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve, join } from "node:path";
const temp = mkdtempSync(join(tmpdir(), "codefit-walkthrough-"));
const output = resolve("../docs/architecture/gate1");
mkdirSync(output, { recursive: true });
const keepAlive = setInterval(() => {}, 1000);
let app;
let recorder;
let recorded;

async function stopRecording() {
  if (!recorder) return;
  const child = recorder;
  recorder = undefined;
  const timeout = setTimeout(() => child.kill("SIGKILL"), 5000);
  try {
    if (child.exitCode === null && !child.stdin.destroyed) child.stdin.end("q");
    const error = await recorded;
    if (error) throw error;
  } finally {
    clearTimeout(timeout);
  }
}

try {
  app = await electron.launch({
    chromiumSandbox: true,
    executablePath: process.env.CODEFIT_DESKTOP_EXECUTABLE || electronPath,
    args: process.env.CODEFIT_DESKTOP_EXECUTABLE ? [] : [resolve(".")],
    cwd: temp,
    env: { ...process.env, XDG_CONFIG_HOME: temp },
  });
  const page = await app.firstWindow();
  const pause = () => new Promise((resolve) => setTimeout(resolve, 1300));
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].setContentSize(1280, 900),
  );
  await page.waitForSelector("h1");
  await page.evaluate(() => document.fonts.ready.then(() => true));
  await app.evaluate(({ BrowserWindow }) => {
    const window = BrowserWindow.getAllWindows()[0];
    window.setPosition(0, 0);
    window.focus();
  });
  recorder = spawn(
    process.env.CODEFIT_FFMPEG || "ffmpeg",
    [
      "-y", "-loglevel", "error", "-video_size", "1280x900",
      "-framerate", "20", "-f", "x11grab", "-i",
      `${process.env.DISPLAY || ":0"}+0,0`,
      "-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "6",
      "-b:v", "0", "-crf", "35", join(output, "walkthrough.webm"),
    ],
    { stdio: ["pipe", "ignore", "inherit"] },
  );
  // Resolve with an error rather than rejecting before the walkthrough awaits it.
  recorded = new Promise((resolve) => {
    let failure;
    recorder.once("error", (error) => {
      failure = error;
    });
    recorder.stdin.on("error", (error) => {
      failure = error;
    });
    recorder.once("close", (code) =>
      resolve(
        failure || (code === 0 ? undefined : new Error(`ffmpeg exited ${code}`)),
      ),
    );
  });
  await new Promise((resolve, reject) => {
    recorder.once("spawn", resolve);
    recorder.once("error", reject);
  });
  await pause();
  await page.getByRole("button", { name: "Enter your next mission" }).click();
  await pause();
  await page.getByRole("button", { name: "View skill path" }).click();
  await pause();
  await page.getByRole("button", { name: /Collections, Available/ }).click();
  await pause();
  await page.getByRole("tab", { name: "Evidence", exact: true }).click();
  await page.getByLabel("Evidence scenario").selectOption("Contradictory");
  await pause();
  await page
    .getByRole("navigation")
    .getByRole("button", { name: /Mission Console/ })
    .click();
  await pause();
  await page.getByRole("tab", { name: "Available", exact: true }).click();
  await pause();
  await page.getByRole("button", { name: "Open mission preview" }).click();
  await page.getByRole("button", { name: "Enter sample workspace" }).click();
  await page
    .getByRole("textbox", { name: "Example mission editor" })
    .fill(
      "// What should an empty collection promise?\n// Test the boundary before changing the contract.",
    );
  await pause();
  await page.keyboard.press("Escape");
  await pause();
  await page.getByRole("button", { name: "Keep editing" }).click();
  await page.getByRole("button", { name: "Keep in this preview" }).click();
  await page.getByRole("button", { name: "Simulate evaluation" }).click();
  await pause();
  await page.getByLabel("Evaluation scenario").selectOption("Interrupted");
  await pause();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Switch to light theme" }).click();
  await pause();
  await app.evaluate(({ BrowserWindow }) =>
    BrowserWindow.getAllWindows()[0].setContentSize(760, 900),
  );
  await page
    .getByRole("navigation")
    .getByRole("button", { name: /Ascension Tree/ })
    .click();
  await pause();
  await page.getByRole("button", { name: "Inspect Debugging" }).click();
  await pause();
  await page
    .getByRole("button", { name: "Preview states", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: /Offline/ })
    .click();
  await page.evaluate(() => scrollTo(0, 0));
  await pause();
  await stopRecording();
  console.log(
    "Saved native walkthrough: docs/architecture/gate1/walkthrough.webm",
  );
} finally {
  try {
    await stopRecording();
  } finally {
    try {
      await app?.close();
    } finally {
      clearInterval(keepAlive);
      rmSync(temp, { recursive: true, force: true });
    }
  }
}
