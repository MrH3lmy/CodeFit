import { contextBridge, ipcRenderer } from "electron";
import type { CodeFitBridge } from "../src/contract";

// This file is bundled as a single sandbox-compatible CJS preload; no Node APIs cross the bridge.
const bridge: CodeFitBridge = Object.freeze({
  listDecks: () => ipcRenderer.invoke("codefit:decks:list"),
  cancelRead: () => ipcRenderer.invoke("codefit:read:cancel"),
});
contextBridge.exposeInMainWorld("codefit", bridge);
