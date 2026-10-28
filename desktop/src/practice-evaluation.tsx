import { debriefIdentity } from "./practice-debrief-identity";
import { PracticeDebrief } from "./practice-debrief";
import { useEffect, useRef, useState } from "react";
import { Badge, Button, Dialog } from "./components";
import type {
  PracticeDraft,
  SubmissionIntent,
  SubmissionResult,
  SubmissionInput,
} from "./practice-contract";
type Identity = { profileId: string; sessionId: string };
/** Stages, outcomes and checks are Java projections. No polling or frontend grading. */
export function PracticeEvaluation({
  draft,
  presentationId,
  identity,
  canSubmit,
}: {
  draft?: PracticeDraft;
  presentationId: string;
  identity: Identity;
  canSubmit: boolean;
}) {
  const [rows, setRows] = useState<SubmissionResult[]>([]),
    [selected, setSelected] = useState<string>();
  const [pending, setPending] = useState<SubmissionIntent | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const [confirm, setConfirm] = useState<number>();
  const [assistance, setAssistance] =
    useState<SubmissionInput["assistance"]>("UNKNOWN");
  const live = useRef(true),
    running = useRef(false),
    session = useRef(identity);
  const result = rows.find((r) => r.commandId === selected);
  useEffect(() => {
    live.current = true;
    // The editor becomes interactive without background IPC competing with autosave/recovery.
    // The separate read-only inspector has no editor and may load its history immediately.
    if (!draft) void refresh();
    return () => {
      live.current = false;
    };
  }, []);
  function accept(value: SubmissionResult | null) {
    if (!value) return;
    setRows((old) =>
      [value, ...old.filter((x) => x.commandId !== value.commandId)].slice(
        0,
        32,
      ),
    );
    setSelected(value.commandId);
  }
  async function refresh() {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setLoaded(false);
    setError("");
    try {
      const connected = await window.codefit.connectPractice();
      if (!live.current) return;
      if (!connected.ok || connected.profileId !== identity.profileId)
        throw new Error();
      session.current = connected;
      setPending(connected.pendingSubmission);
      const r = await window.codefit.listPracticeSubmissions(
        presentationId,
        connected.profileId,
        connected.sessionId,
      );
      if (!live.current) return;
      if (!r.ok || r.method !== "practice.submissions") throw new Error();
      setLoaded(true);
      setRows(r.submissions);
      setSelected((old) =>
        r.submissions.some((x) => x.commandId === old)
          ? old
          : r.submissions[0]?.commandId,
      );
    } catch {
      if (live.current) {
        setRows([]);
        setSelected(undefined);
        setError(
          "Java status unavailable. Reconnect and inspect the original command; no replacement submission was made.",
        );
      }
    } finally {
      running.current = false;
      if (live.current) setBusy(false);
    }
  }
  async function prepare() {
    const revision = confirm;
    setConfirm(undefined);
    if (
      !draft ||
      revision === undefined ||
      !canSubmit ||
      draft?.revision !== revision ||
      running.current
    )
      return;
    running.current = true;
    setBusy(true);
    setError("");
    try {
      const r = await window.codefit.preparePracticeSubmission(
        {
          presentationId: presentationId,
          expectedRevision: revision,
          assistance,
        },
        session.current.profileId,
        session.current.sessionId,
      );
      if (!live.current) return;
      if (!r.ok)
        setError(`${r.code}. Check submission status before trying again.`);
      else if ("evaluation" in r && r.evaluation) accept(r.evaluation);
    } catch {
      if (live.current)
        setError(
          "Preparation completion is unknown. Inspect the original command.",
        );
    } finally {
      running.current = false;
      if (live.current) {
        setBusy(false);
        void refresh();
      }
    }
  }
  async function action(
    action: "status" | "resume" | "cancel",
    commandId: string,
    targetPresentation = presentationId,
  ) {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setError("");
    try {
      const r = await window.codefit.practiceSubmissionAction(
        { action, commandId, presentationId: targetPresentation },
        session.current.profileId,
        session.current.sessionId,
      );
      if (!live.current) return;
      if (!r.ok)
        setError(
          `${r.code}. The original durable command remains authoritative.`,
        );
      else if ("evaluation" in r) {
        if (r.evaluation && r.presentationId === presentationId)
          accept(r.evaluation);
        if (pending?.commandId === commandId) setPending(null);
        if (!r.evaluation)
          setError(
            "No PREPARED submission committed. No artifact was reconstructed. Review the saved revision and explicitly submit again if intended.",
          );
      }
    } catch {
      if (live.current)
        setError(
          "Completion is unknown. Refresh status; do not create a replacement submission.",
        );
    } finally {
      running.current = false;
      if (live.current) setBusy(false);
    }
  }
  return (
    <section
      className="practice-evaluation"
      aria-label="Java evaluation and recovery"
    >
      <div className="editor-heading">
        <div>
          <p className="eyebrow">FROZEN ARTIFACT / JAVA EVALUATOR</p>
          <h3>Put your solution to the test.</h3>
        </div>
        <Badge tone="cyan">Local evaluation</Badge>
      </div>
      <p>
        Submit one acknowledged revision. Later edits cannot change its
        artifact. A passing result describes bounded checks, not engineering
        mastery.
      </p>
      <div className="draft-actions">
        <Button
          disabled={
            !draft ||
            !loaded ||
            !canSubmit ||
            busy ||
            !!pending ||
            rows.some((r) => r.stage !== "FINALIZED")
          }
          onClick={() => {
            setAssistance("UNKNOWN");
            setConfirm(draft?.revision);
          }}
        >
          Submit saved revision
        </Button>
        <Button secondary disabled={busy} onClick={() => void refresh()}>
          Refresh submission status
        </Button>
      </div>
      {!loaded && !busy && (
        <p className="fine-print">
          Refresh submission status to inspect saved results and enable a new
          submission.
        </p>
      )}
      {!canSubmit && (
        <p className="fine-print">
          {draft
            ? "Save or reconcile your edits before submitting."
            : "Inspecting frozen submissions without opening the editor or challenge instructions."}
        </p>
      )}
      {busy && <p role="status">Waiting for Java acknowledgement…</p>}
      {error && <p role="alert">{error}</p>}
      {pending && (
        <div className="draft-conflict">
          <h4>Unconfirmed preparation</h4>
          <p className="practice-reference">
            Command {pending.commandId} · revision {pending.expectedRevision}
          </p>
          <p>
            No automatic retry. Inspect the original command before another
            submission.
          </p>
          <Button
            secondary
            disabled={busy}
            onClick={() =>
              void action("status", pending.commandId, pending.presentationId)
            }
          >
            Reconcile original submission
          </Button>
        </div>
      )}
      {rows.length > 0 && (
        <label className="draft-notes">
          Recorded submissions
          <select
            aria-label="Recorded submissions"
            value={selected ?? ""}
            onChange={(e) => setSelected(e.target.value)}
          >
            {rows.map((r) => (
              <option key={r.commandId} value={r.commandId}>
                {r.commandId} · revision {r.draftRevision} · {r.stage}
              </option>
            ))}
          </select>
        </label>
      )}
      {result && (
        <article
          className="practice-evaluation-result"
          aria-label="Frozen submission result"
        >
          <div className="editor-heading">
            <h4>
              {result.challengeId} · v{result.challengeVersion}
            </h4>
            <Badge>
              {result.outcome ?? (result.active ? "Running" : "Recoverable")}
            </Badge>
          </div>
          <dl>
            <dt>Submission</dt>
            <dd>{result.commandId}</dd>
            <dt>Presentation</dt>
            <dd>{result.presentationId}</dd>
            <dt>Frozen revision</dt>
            <dd>{result.draftRevision}</dd>
            <dt>Stage</dt>
            <dd>
              {result.stage}
              {result.active ? " · execution active" : ""}
            </dd>
            <dt>Outcome</dt>
            <dd>{result.outcome ?? "Not yet evaluated"}</dd>
            <dt>Assistance</dt>
            <dd>{result.assistance}</dd>
            <dt>Submitted</dt>
            <dd>{result.submittedAt}</dd>
            <dt>Artifact SHA-256</dt>
            <dd>{result.artifactSha256}</dd>
          </dl>
          {result.withdrawn && (
            <p role="status">Withdrawn evaluation; no effective completion.</p>
          )}
          {result.cancellationRequested && (
            <p role="status">
              Cancellation requested. Already committed results are not undone.
            </p>
          )}
          <div className="draft-actions">
            <Button
              secondary
              disabled={busy}
              onClick={() => void action("status", result.commandId)}
            >
              Check this submission
            </Button>
            {result.stage !== "FINALIZED" && (
              <>
                <Button
                  disabled={busy || result.active || !!pending}
                  onClick={() => void action("resume", result.commandId)}
                >
                  {result.stage === "PREPARED"
                    ? "Run frozen submission"
                    : "Resume frozen submission"}
                </Button>
                <Button
                  secondary
                  disabled={busy || !!pending}
                  onClick={() => void action("cancel", result.commandId)}
                >
                  Cancel frozen submission
                </Button>
              </>
            )}
          </div>
          <ul className="evaluation-checks">
            {result.checks.map((c) => (
              <li key={c.id}>
                <strong>{c.id}</strong>
                <Badge>{c.status}</Badge>
                <p>{c.feedback}</p>
              </li>
            ))}
          </ul>
          <p className="fine-print">
            Feedback excludes private tests, expected outputs and compiler
            diagnostics. Unsupported forms and unavailable tools do not
            establish lack of ability. Refresh explicitly to inspect a running
            result.
          </p>
        </article>
      )}
      {result?.stage === "FINALIZED" && (
        <PracticeDebrief
          key={debriefIdentity(result, session.current)}
          evaluation={result}
          identity={session.current}
          disabled={busy}
        />
      )}
      {confirm !== undefined && (
        <Dialog
          title="Submit this saved revision?"
          eyebrow="IMMUTABLE JAVA SUBMISSION"
          onClose={() => setConfirm(undefined)}
        >
          <p>
            Saved revision <strong>{confirm}</strong> · {presentationId}
          </p>
          <p>
            Java freezes this saved source and reflection before execution.
            Finalization records a local attempt and evidence in this disposable
            profile. This is not a sandbox: use only your own local solution.
          </p>
          <label className="draft-notes">
            Outside assistance
            <select
              aria-label="Outside assistance"
              value={assistance}
              onChange={(e) =>
                setAssistance(e.target.value as SubmissionInput["assistance"])
              }
            >
              <option value="UNKNOWN">Unknown / prefer not to declare</option>
              <option value="NONE_DECLARED">
                I declare no outside assistance
              </option>
              <option value="DECLARED">I used outside assistance</option>
            </select>
          </label>
          <Button
            disabled={!canSubmit || draft?.revision !== confirm || busy}
            onClick={() => void prepare()}
          >
            Confirm frozen submission
          </Button>
        </Dialog>
      )}
    </section>
  );
}
