import { packager } from "@electron/packager";
import { existsSync } from "node:fs";
if (!existsSync("java/runtime/release") || !existsSync("dist/index.html"))
  throw new Error("Run npm run build and npm run prepare:java first.");
const paths = await packager({
  dir: ".",
  name: "CodeFit-Gate0",
  executableName: "codefit-gate0",
  out: "release",
  overwrite: true,
  asar: true,
  prune: true,
  extraResource: ["java"],
  ignore: [
    /^\/java($|\/)/,
    /^\/release($|\/)/,
    /^\/tests($|\/)/,
    /^\/test-results($|\/)/,
    /^\/playwright-report($|\/)/,
    /^\/src($|\/)/,
    /^\/electron($|\/)/,
    /^\/scripts($|\/)/,
  ],
});
console.log(paths.join("\n"));
