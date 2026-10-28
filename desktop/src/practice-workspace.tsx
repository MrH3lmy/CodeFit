import { PracticeSkills } from "./practice-skills";
import { useEffect, useRef, useState } from "react";
import { PracticeEvaluation } from "./practice-evaluation";
import { PracticeEditor } from "./practice-editor";
import { mayLeaveDraft } from "./draft-navigation";
import { Badge, Button, Dialog } from "./components";
import type {
  PracticeDraft,
  PracticeCandidate,
  PracticeResult,
  PracticeState,
} from "./practice-contract";

export function PracticeWorkspace({ onBack }: { onBack: () => void }) {
  const [state, setState] = useState<Extract<PracticeState, { ok: true }>>();
  const [inspect, setInspect] = useState<string>();
  const [draft, setDraft] = useState<PracticeDraft>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [confirm, setConfirm] = useState<PracticeCandidate>();
  const [opened, setOpened] =
    useState<Extract<PracticeResult, { ok: true; method: "practice.open" }>>();
  const generation = useRef(0);
  useEffect(
    () => () => {
      generation.current++;
      void window.codefit.disconnectPractice();
    },
    [],
  );
  async function connect() {
    if (!mayLeaveDraft()) return;
    const token = ++generation.current;
    setBusy(true);
    setError("");
    setOpened(undefined);
    setInspect(undefined);
    try {
      const result = await window.codefit.connectPractice();
      if (token !== generation.current) return;
      if (result.ok) setState(result);
      else {
        setState(undefined);
        setError(result.code);
      }
    } catch {
      if (token === generation.current) {
        setState(undefined);
        setError("UNAVAILABLE");
      }
    } finally {
      if (token === generation.current) setBusy(false);
    }
  }
  async function run(candidate?: PracticeCandidate) {
    if (!state) return;
    const token = ++generation.current;
    setBusy(true);
    setError("");
    setConfirm(undefined);
    setOpened(undefined);
    setInspect(undefined);
    try {
      const result = candidate
        ? await window.codefit.openAuthorizedPracticeChallenge(
            {
              questId: candidate.questId,
              questVersion: candidate.questVersion,
              challengeId: candidate.challengeId,
              challengeVersion: candidate.challengeVersion,
            },
            state.profileId,
            state.sessionId,
          )
        : await window.codefit.recoverPractice(
            state.profileId,
            state.sessionId,
          );
      if (token !== generation.current) return;
      if (result.ok && result.method === "practice.open") {
        const resumed = await window.codefit.resumePractice(
          result.receipt.presentationId,
          state.profileId,
          state.sessionId,
        );
        if (token !== generation.current) return;
        if (resumed.ok && resumed.method === "practice.presentation.resume") {
          setOpened(result);
          setDraft(resumed.draft);
        } else setError(resumed.ok ? "PROTOCOL_ERROR" : resumed.code);
      } else setError(result.ok ? "PROTOCOL_ERROR" : result.code);
      // Refresh metadata and the durable intent, never generate a replacement command.
      const refreshed = await window.codefit.connectPractice();
      if (token === generation.current) {
        if (refreshed.ok) setState(refreshed);
        else {
          setState(undefined);
          setOpened(undefined);
          setInspect(undefined);
          setError(refreshed.code);
        }
      }
    } catch {
      if (token === generation.current) {
        setState(undefined);
        setOpened(undefined);
        setInspect(undefined);
        setError("UNAVAILABLE");
      }
    } finally {
      if (token === generation.current) setBusy(false);
    }
  }
  async function disconnect() {
    if (!mayLeaveDraft()) return;
    generation.current++;
    setOpened(undefined);
    setInspect(undefined);
    setState(undefined);
    setConfirm(undefined);
    setError("");
    setBusy(false);
    await window.codefit.disconnectPractice();
  }
  async function closeInstructions() {
    if (!opened || !state || !mayLeaveDraft()) return;
    const token = ++generation.current;
    setBusy(true);
    setOpened(undefined);
    setInspect(undefined);
    try {
      if (!state.pending) {
        await connect();
        return;
      }
      const result = await window.codefit.acknowledgePractice(
        opened.commandId,
        state.profileId,
        state.sessionId,
      );
      if (token !== generation.current) return;
      if (result.ok && result.method === "practice.status" && result.receipt)
        await connect();
      else {
        setState(undefined);
        setError(result.ok ? "RECOVERY_REQUIRED" : result.code);
      }
    } catch {
      if (token === generation.current) {
        setState(undefined);
        setError("UNAVAILABLE");
      }
    } finally {
      if (token === generation.current) setBusy(false);
    }
  }
  async function resume(presentationId: string) {
    if (!state || !mayLeaveDraft()) return;
    const token = ++generation.current;
    setBusy(true);
    setError("");
    setOpened(undefined);
    setInspect(undefined);
    setDraft(undefined);
    try {
      const result = await window.codefit.resumePractice(
        presentationId,
        state.profileId,
        state.sessionId,
      );
      if (token !== generation.current) return;
      if (result.ok && result.method === "practice.presentation.resume") {
        setOpened({
          ...result,
          method: "practice.open",
          commandId: result.receipt.commandId,
        });
        setDraft(result.draft);
      } else setError(result.ok ? "PROTOCOL_ERROR" : result.code);
    } catch {
      if (token === generation.current) setError("UNAVAILABLE");
    } finally {
      if (token === generation.current) setBusy(false);
    }
  }
  return (
    <section
      className="practice-workspace"
      aria-label="Authorized practice workspace"
    >
      <div role="status" className="sr-only">
        {opened
          ? "Exposure committed. Authorized instructions are available."
          : ""}
      </div>
      <div className="practice-hero panel">
        <div>
          <p className="eyebrow">JAVA TRAINING PACK / LIVE EXPERIMENT</p>
          <h2 id="practice-controls" tabIndex={-1}>
            Begin with a real problem.
          </h2>
          <p>
            Explore published engineering practice in a disposable Java-owned
            profile. Your learner snapshot and real learning records stay
            separate.
          </p>
          <div className="practice-badges">
            <Badge tone="cyan">Original published content</Badge>
            <Badge>Java-owned evaluation</Badge>
            <Badge>Linux experimental profile</Badge>
          </div>
        </div>
        <Button secondary onClick={onBack}>
          Back to Mission Console
        </Button>
      </div>
      {!state && (
        <div className="panel practice-panel">
          <p className="eyebrow">EXPLICIT OPT-IN</p>
          <h3>A separate space to practice</h3>
          <p>
            Connecting creates or reopens a marked disposable profile. Starting
            records exposure and creates the existing Java solving session and
            starter draft. No real profile is imported or modified.
          </p>
          <p>
            Do not open this experimental profile in an older JavaFX version.
            Real-profile writing and non-Linux platforms are unavailable.
          </p>
          <Button disabled={busy} onClick={() => void connect()}>
            Connect disposable practice profile
          </Button>
        </div>
      )}
      {busy && (
        <p role="status">
          Waiting for the Java owner. Instructions remain withheld until
          exposure is committed.
        </p>
      )}
      {error && (
        <div className="panel practice-panel" role="alert">
          <h3>Practice action unavailable</h3>
          <p>{error}</p>
          <p>
            A lost response may follow a committed exposure. Reconnect and
            recover the original command; do not assume cancellation undid it.
          </p>
          <Button disabled={busy} onClick={() => void connect()}>
            Reconnect and inspect recovery
          </Button>
        </div>
      )}
      {(state || busy) && (
        <button className="button secondary" onClick={() => void disconnect()}>
          Disconnect / cancel transport
        </button>
      )}
      {state && (
        <>
          <div className="practice-identity">
            <Badge tone="cyan">Live Java owner</Badge>
            <span>Profile {state.profileId}</span>
            <span>Session {state.sessionId}</span>
          </div>
          {state.pending && !opened && (
            <div className="panel practice-panel">
              <p className="eyebrow">DURABLE COMMAND RECOVERY</p>
              <h3>Resume the original start</h3>
              <p>Command {state.pending.commandId}</p>
              <p>
                {state.pending.input.challengeId} · v
                {state.pending.input.challengeVersion}
              </p>
              <p>
                Java checks the original receipt first. If no exposure
                committed, this explicit action retries the same immutable
                command.
              </p>
              <Button disabled={busy} onClick={() => void run()}>
                Recover original start
              </Button>
            </div>
          )}
          <PracticeSkills
            key={`${state.profileId}:${state.sessionId}`}
            identity={state}
            disabled={busy}
          />
          {!opened && state.presentations.length > 0 && (
            <div
              className="panel practice-panel"
              aria-label="Saved practice presentations"
            >
              <p className="eyebrow">CONTINUE YOUR WORK</p>
              <h3>Previously opened practice</h3>
              <p>
                Resume the same authorized presentation and latest Java draft.
                No new exposure is recorded. Showing the latest 32
                presentations.
              </p>
              {state.presentations.map((p) => (
                <div className="practice-resume-row" key={p.presentationId}>
                  <p>
                    {p.challengeId} · v{p.challengeVersion}
                    <br />
                    <small>{p.presentationId}</small>
                  </p>
                  <Button
                    secondary
                    disabled={busy}
                    onClick={() => void resume(p.presentationId)}
                  >
                    Resume saved practice
                  </Button>
                  <Button
                    secondary
                    disabled={busy}
                    onClick={() => setInspect(p.presentationId)}
                  >
                    Inspect saved submissions
                  </Button>
                </div>
              ))}
            </div>
          )}
          {!opened && inspect && (
            <div className="panel practice-panel">
              <PracticeEvaluation
                key={inspect}
                presentationId={inspect}
                identity={state}
                canSubmit={false}
              />
            </div>
          )}
          {!opened && (
            <div
              className="practice-grid"
              aria-label="Eligible published practice"
            >
              {state.candidates.map((c) => (
                <article
                  className="panel practice-panel"
                  key={`${c.challengeId}:${c.challengeVersion}`}
                >
                  <p className="eyebrow">EXPLORATORY PRACTICE</p>
                  <h3>{c.title}</h3>
                  <p className="practice-reference">
                    {c.challengeId} · v{c.challengeVersion}
                  </p>
                  <p>
                    {c.effortMinutes} minutes expected effort · exact quest{" "}
                    {c.questId} v{c.questVersion}
                  </p>
                  <p>
                    Java permits exploration. This is not a mastery unlock or an
                    assigned recommendation.
                  </p>
                  <Button
                    disabled={busy || !!state.pending}
                    onClick={() => setConfirm(c)}
                  >
                    Review start
                  </Button>
                </article>
              ))}
              {!state.candidates.length && (
                <p role="status">
                  No eligible published practice is available under the current
                  Java policy.
                </p>
              )}
            </div>
          )}
        </>
      )}
      {opened && (
        <article
          className="panel practice-panel"
          aria-label="Authorized challenge instructions"
        >
          <Badge tone="cyan">Exposure committed</Badge>
          <h3>
            {opened.receipt.challengeId} · v{opened.receipt.challengeVersion}
          </h3>
          <dl>
            <dt>Quest</dt>
            <dd>
              {opened.receipt.questId} · v{opened.receipt.questVersion}
            </dd>
            <dt>Presentation</dt>
            <dd>{opened.receipt.presentationId}</dd>
            <dt>Command</dt>
            <dd>{opened.commandId}</dd>
            <dt>Recorded at</dt>
            <dd>{opened.receipt.presentedAt}</dd>
          </dl>
          <details className="practice-instruction-details" open>
            <summary>Authorized instructions</summary>
            <pre className="practice-instructions" data-protected-content>
              {opened.instructions}
            </pre>
          </details>
          {draft && state && !busy && (
            <PracticeEditor
              key={draft.presentationId}
              initial={draft}
              identity={state}
              pending={!!state.pendingSave}
            />
          )}
          <p>
            Hints and solutions remain unavailable. Evaluation is explicit and
            uses a frozen saved revision.
          </p>
          <Button
            disabled={busy}
            secondary
            onClick={() => void closeInstructions()}
          >
            Close instructions and acknowledge receipt
          </Button>
        </article>
      )}
      {confirm && (
        <Dialog
          title="Start this published practice?"
          eyebrow="DURABLE EXPOSURE"
          onClose={() => setConfirm(undefined)}
        >
          <p>
            {confirm.challengeId} · v{confirm.challengeVersion}
          </p>
          <p>
            Java will recheck eligibility and record your exposure before
            showing instructions. Familiarity is recorded as unknown. This does
            not grant XP, completion or mastery.
          </p>
          <Button onClick={() => void run(confirm)}>Confirm start</Button>
        </Dialog>
      )}
    </section>
  );
}
