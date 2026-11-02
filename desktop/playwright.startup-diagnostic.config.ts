import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/diagnostics",
  timeout: 45000,
  workers: 1,
  reporter: "list",
  use: { trace: "off", screenshot: "off", video: "off" },
});
