import { useEffect, useRef, useState } from "react";
import { Button, Badge } from "./components";
import { PracticeEvaluation } from "./practice-evaluation";
import { JavaEditor } from "./java-editor";
import { registerDraftGuard } from "./draft-navigation";
import type { PracticeDraft } from "./practice-contract";

type Identity = { profileId: string; sessionId: string };
export function PracticeEditor({
  initial,
  identity,
  pending,
}: {
  initial: PracticeDraft;
  identity: Identity;
  pending: boolean;
}) {
  const [source, setSource] = useState(initial.source),
    [reflection, setReflection] = useState(initial.reflection);
  const [persisted, setPersisted] = useState(initial),
    [status, setStatus] = useState(pending ? "Recovery required" : "Saved"),
    [error, setError] = useState("");
  const [reconciled, setReconciled] = useState(false);
  const [blocked, setBlocked] = useState(pending),
    [saving, setSaving] = useState(false);
  const current = useRef({ source, reflection }),
    saved = useRef(persisted),
    active = useRef(true),
    running = useRef(false),
    session = useRef(identity),
    blockedRef = useRef(pending);
  current.current = { source, reflection };
  saved.current = persisted;
  blockedRef.current = blocked;
  const dirty =
    source !== persisted.source || reflection !== persisted.reflection;
  const risk = useRef(false);
  risk.current = dirty || saving || blocked;
  useEffect(() => {
    active.current = true;
    const release = registerDraftGuard(
      () =>
        !risk.current ||
        window.confirm(
          "This draft has unconfirmed edits or recovery pending. Leave and discard in-memory edits? Only Java-acknowledged saves survive restart.",
        ),
    );
    const unload = (event: BeforeUnloadEvent) => {
      if (risk.current) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", unload);
    return () => {
      active.current = false;
      release();
      window.removeEventListener("beforeunload", unload);
      void window.codefit.setPracticeDraftDirty(false);
    };
  }, []);
  useEffect(() => {
    void window.codefit.setPracticeDraftDirty(dirty || saving || blocked);
  }, [dirty, saving, blocked]);
  async function save() {
    if (running.current || blockedRef.current || !active.current) return;
    const captured = { ...current.current },
      baseline = saved.current;
    if (
      captured.source === baseline.source &&
      captured.reflection === baseline.reflection
    )
      return;
    running.current = true;
    setSaving(true);
    setStatus("Saving");
    setError("");
    try {
      const result = await window.codefit.savePracticeDraft(
        {
          presentationId: initial.presentationId,
          expectedRevision: baseline.revision,
          ...captured,
        },
        session.current.profileId,
        session.current.sessionId,
      );
      if (!active.current) return;
      if (
        result.ok &&
        result.method === "practice.draft.save" &&
        result.saved.outcome === "SAVED"
      ) {
        const next = {
          presentationId: initial.presentationId,
          revision: result.saved.revision,
          ...captured,
        };
        saved.current = next;
        setPersisted(next);
        setStatus(
          current.current.source === captured.source &&
            current.current.reflection === captured.reflection
            ? "Saved"
            : "Unsaved",
        );
      } else {
        blockedRef.current = true;
        setBlocked(true);
        setReconciled(false);
        setStatus(result.ok ? "Conflict" : "Recovery required");
        setError(
          result.ok
            ? "A newer revision exists. Your edits remain here; compare before saving again."
            : result.code,
        );
      }
    } catch {
      if (active.current) {
        blockedRef.current = true;
        setBlocked(true);
        setReconciled(false);
        setStatus("Recovery required");
        setError("Save completion is unknown. Reconcile before another save.");
      }
    } finally {
      running.current = false;
      if (active.current) setSaving(false);
    }
  }
  useEffect(() => {
    if (!dirty || saving || blocked) return;
    setStatus("Unsaved");
    const timer = setTimeout(() => void save(), 700);
    return () => clearTimeout(timer);
  }, [source, reflection, dirty, saving, blocked]);
  async function reconcile() {
    if (running.current) return;
    running.current = true;
    setSaving(true);
    setReconciled(false);
    setError("");
    try {
      const connected = await window.codefit.connectPractice();
      if (!active.current) return;
      if (!connected.ok || connected.profileId !== identity.profileId)
        throw new Error("Owner unavailable");
      session.current = connected;
      const result = await window.codefit.reconcilePracticeDraft(
        initial.presentationId,
        connected.profileId,
        connected.sessionId,
      );
      if (!active.current) return;
      if (!result.ok) throw new Error("Recovery unavailable");
      const read = await window.codefit.readPracticeDraft(
        initial.presentationId,
        connected.profileId,
        connected.sessionId,
      );
      if (!active.current) return;
      if (!read.ok || read.method !== "practice.draft.read")
        throw new Error("Draft unavailable");
      saved.current = read.draft;
      setPersisted(read.draft);
      setReconciled(true);
      // Do not overwrite the current editor. Manual acceptance is required before resuming autosave.
      setStatus(
        current.current.source === read.draft.source &&
          current.current.reflection === read.draft.reflection
          ? "Saved"
          : "Conflict",
      );
      setError(
        "Reconciliation complete. The stored revision is shown below. Keep your edits only after comparing, or load the stored draft.",
      );
    } catch {
      if (active.current)
        setError(
          "Java recovery is unavailable. Your in-memory edits remain; do not close this window.",
        );
    } finally {
      running.current = false;
      if (active.current) setSaving(false);
    }
  }
  return (
    <section className="practice-editor" aria-label="Durable Java draft">
      <div className="editor-heading">
        <div>
          <p className="eyebrow">JAVA 21 / LOCAL DRAFT</p>
          <h3>Your working solution</h3>
        </div>
        <Badge tone={status === "Saved" ? "cyan" : undefined}>{status}</Badge>
      </div>
      <p role="status" aria-live="polite" className="draft-status">
        {status} · acknowledged revision {persisted.revision}
        {dirty ? " · unconfirmed edits" : ""}
      </p>
      <JavaEditor
        value={source}
        onChange={setSource}
        onSave={() => void save()}
      />
      <p id="java-editor-help" className="fine-print">
        Ctrl/Cmd+S saves · Tab inserts four spaces · Ctrl/Cmd+Z undoes ·
        Shift+Tab leaves the editor. 32,768 source characters maximum. Saving
        does not execute code.
      </p>
      <label className="draft-notes">
        Working reflection
        <textarea
          aria-label="Working reflection"
          maxLength={8000}
          value={reflection}
          onChange={(e) => setReflection(e.target.value)}
        />
      </label>
      <div className="draft-actions">
        <Button
          disabled={saving || blocked || !dirty}
          onClick={() => void save()}
        >
          Save draft
        </Button>
        {blocked && (
          <Button secondary disabled={saving} onClick={() => void reconcile()}>
            Reconcile saved draft
          </Button>
        )}
      </div>
      {error && <p role="alert">{error}</p>}
      {blocked && (
        <details className="draft-conflict">
          <summary>
            Inspect last known stored draft · revision {persisted.revision}
          </summary>
          <pre>{persisted.source}</pre>
          <p>{persisted.reflection}</p>
          <Button
            secondary
            disabled={saving || !reconciled}
            onClick={() => {
              if (
                window.confirm(
                  "Replace the editor with this stored draft? Unconfirmed in-memory edits will be discarded.",
                )
              ) {
                setSource(persisted.source);
                setReflection(persisted.reflection);
                setError("");
                setStatus("Saved");
                setBlocked(false);
              }
            }}
          >
            Load stored draft
          </Button>
          <Button
            secondary
            disabled={saving || !reconciled}
            onClick={() => {
              if (
                window.confirm(
                  "Use this stored revision as the base for your current edits? A concurrent change will still be rejected.",
                )
              ) {
                setBlocked(false);
                setError("");
              }
            }}
          >
            Keep my edits against this revision
          </Button>
        </details>
      )}
      <p className="fine-print">
        Only Java-acknowledged saves survive restart. A forced exit can lose
        unacknowledged edits. This draft is shared by presentations of the same
        published problem.
      </p>
      <PracticeEvaluation
        draft={persisted}
        presentationId={initial.presentationId}
        identity={session.current}
        canSubmit={!dirty && !saving && !blocked}
      />
    </section>
  );
}
