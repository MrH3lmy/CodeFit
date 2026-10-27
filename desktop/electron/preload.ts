import { contextBridge, ipcRenderer } from "electron";
import type { CodeFitBridge, DeckInput } from "../src/contract";

// This file is bundled as a single sandbox-compatible CJS preload; no Node APIs cross the bridge.
const bridge: CodeFitBridge = Object.freeze({
  preparePracticeSubmission: (
    input: import("../src/practice-contract").SubmissionInput,
    profileId: string,
    sessionId: string,
  ) =>
    ipcRenderer.invoke(
      "codefit:practice:submission-prepare",
      input,
      profileId,
      sessionId,
    ),
  practiceSubmissionAction: (
    input: import("../src/practice-contract").SubmissionAction,
    profileId: string,
    sessionId: string,
  ) =>
    ipcRenderer.invoke(
      "codefit:practice:submission-action",
      input,
      profileId,
      sessionId,
    ),
  listPracticeSubmissions: (
    presentationId: string,
    profileId: string,
    sessionId: string,
  ) =>
    ipcRenderer.invoke(
      "codefit:practice:submissions",
      presentationId,
      profileId,
      sessionId,
    ),
  setPracticeDraftDirty: (dirty: boolean) =>
    ipcRenderer.invoke("codefit:practice:dirty", dirty),
  resumePractice: (
    presentationId: string,
    profileId: string,
    sessionId: string,
  ) =>
    ipcRenderer.invoke(
      "codefit:practice:resume",
      presentationId,
      profileId,
      sessionId,
    ),
  readPracticeDraft: (
    presentationId: string,
    profileId: string,
    sessionId: string,
  ) =>
    ipcRenderer.invoke(
      "codefit:practice:draft-read",
      presentationId,
      profileId,
      sessionId,
    ),
  savePracticeDraft: (
    input: import("../src/practice-contract").DraftInput,
    profileId: string,
    sessionId: string,
  ) =>
    ipcRenderer.invoke(
      "codefit:practice:draft-save",
      input,
      profileId,
      sessionId,
    ),
  reconcilePracticeDraft: (
    presentationId: string,
    profileId: string,
    sessionId: string,
  ) =>
    ipcRenderer.invoke(
      "codefit:practice:draft-reconcile",
      presentationId,
      profileId,
      sessionId,
    ),
  connectPractice: () => ipcRenderer.invoke("codefit:practice:connect"),
  openAuthorizedPracticeChallenge: (
    input: import("../src/practice-contract").PracticeInput,
    profileId: string,
    sessionId: string,
  ) => ipcRenderer.invoke("codefit:practice:open", input, profileId, sessionId),
  recoverPractice: (profileId: string, sessionId: string) =>
    ipcRenderer.invoke("codefit:practice:recover", profileId, sessionId),
  acknowledgePractice: (
    commandId: string,
    profileId: string,
    sessionId: string,
  ) =>
    ipcRenderer.invoke(
      "codefit:practice:acknowledge",
      commandId,
      profileId,
      sessionId,
    ),
  disconnectPractice: () => ipcRenderer.invoke("codefit:practice:disconnect"),
  readCurriculumPreview: () => ipcRenderer.invoke("codefit:curriculum:read"),
  cancelCurriculumPreview: () =>
    ipcRenderer.invoke("codefit:curriculum:cancel"),
  readState: (afterId: number) =>
    ipcRenderer.invoke("codefit:state:read", afterId),
  createDeck: (input: DeckInput) =>
    ipcRenderer.invoke("codefit:deck:create", input),
  pendingDeck: () => ipcRenderer.invoke("codefit:deck:pending"),
  recoverDeck: () => ipcRenderer.invoke("codefit:deck:recover"),
  acknowledgeDeck: (commandId: string) =>
    ipcRenderer.invoke("codefit:deck:acknowledge", commandId),
  cancelOperation: () => ipcRenderer.invoke("codefit:operation:cancel"),
  chooseLearnerProfile: () => ipcRenderer.invoke("codefit:learner:choose"),
  readLearnerMissions: () => ipcRenderer.invoke("codefit:learner:missions"),
  readLearnerSkills: () => ipcRenderer.invoke("codefit:learner:skills"),
  readLearnerProfile: () => ipcRenderer.invoke("codefit:learner:read"),
  disconnectLearnerProfile: () =>
    ipcRenderer.invoke("codefit:learner:disconnect"),
});
contextBridge.exposeInMainWorld("codefit", bridge);
