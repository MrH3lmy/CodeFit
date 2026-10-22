import { contextBridge, ipcRenderer } from "electron";
import type { CodeFitBridge, DeckInput } from "../src/contract";

// This file is bundled as a single sandbox-compatible CJS preload; no Node APIs cross the bridge.
const bridge: CodeFitBridge = Object.freeze({
  readState: (afterId: number) =>
    ipcRenderer.invoke("codefit:state:read", afterId),
  createDeck: (input: DeckInput) =>
    ipcRenderer.invoke("codefit:deck:create", input),
  pendingDeck: () => ipcRenderer.invoke("codefit:deck:pending"),
  recoverDeck: () => ipcRenderer.invoke("codefit:deck:recover"),
  acknowledgeDeck: (commandId: string) =>
    ipcRenderer.invoke("codefit:deck:acknowledge", commandId),
  cancelOperation: () => ipcRenderer.invoke("codefit:operation:cancel"),
});
contextBridge.exposeInMainWorld("codefit", bridge);
