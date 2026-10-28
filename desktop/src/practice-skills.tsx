import { useEffect, useRef, useState } from "react";
import { Button, Badge, Dialog } from "./components";
import type { SkillsView } from "./practice-skills-contract";

/** Explicit publication only. Opening this component never calls a write or a policy. */
export function PracticeSkills({
  identity,
  disabled,
}: {
  identity: { profileId: string; sessionId: string };
  disabled: boolean;
}) {
  const [view, setView] = useState<SkillsView>();
  const [busy, setBusy] = useState(false),
    [confirm, setConfirm] = useState(false),
    [error, setError] = useState("");
  const [recovery, setRecovery] = useState(false);
  const fence = useRef(0),
    running = useRef(false);
  useEffect(
    () => () => {
      fence.current++;
    },
    [],
  );
  async function act(action: "read" | "publish") {
    if (running.current) return;
    running.current = true;
    const token = ++fence.current;
    setBusy(true);
    setError("");
    setConfirm(false);
    try {
      const r = await window.codefit.practiceSkillAction(
        action,
        identity.profileId,
        identity.sessionId,
      );
      if (token !== fence.current) return;
      if (
        !r.ok ||
        !("reconciliation" in r) ||
        r.profileId !== identity.profileId ||
        r.sessionId !== identity.sessionId
      )
        throw new Error();
      setView(r);
      setRecovery(false);
    } catch {
      if (token === fence.current) {
        setRecovery(true);
        setError(
          "Completion unavailable. Check the original command before any new update. No replacement command was created.",
        );
      }
    } finally {
      running.current = false;
      if (token === fence.current) setBusy(false);
    }
  }
  return (
    <section
      className="panel practice-panel"
      aria-label="Skill interpretations"
    >
      <span className="eyebrow">EVIDENCE / INTERPRETATION</span>
      <h3>Update skill interpretations</h3>
      <p>
        Ask Java to interpret recorded evidence for the published pilot skills.
        This saves ordinal estimates only. It does not rerun a challenge, grant
        XP, or assign a mission.
      </p>
      <div className="draft-actions">
        <Button
          secondary
          disabled={busy || disabled}
          onClick={() => void act("read")}
        >
          Read saved interpretations / recover
        </Button>
        <Button
          disabled={busy || disabled || recovery || !view}
          onClick={() => setConfirm(true)}
        >
          Update skill interpretations
        </Button>
      </div>
      {busy && <p role="status">Waiting for durable Java acknowledgement…</p>}
      {error && <p role="alert">{error}</p>}
      {view && (
        <>
          <p className="fine-print">
            Exact graph: {view.graphId} v{view.graphVersion} · policy{" "}
            {view.policy}. Reads do not publish.
          </p>
          {!view.reconciliation && (
            <p>
              No saved command receipt. A new interpretation requires explicit
              confirmation.
            </p>
          )}
          {view.reconciliation && (
            <>
              <p className="practice-reference">
                Command {view.reconciliation.commandId} ·{" "}
                {view.reconciliation.state} · {view.reconciliation.reason}
              </p>
              <p>
                Recorded as of {view.reconciliation.asOf}. This receipt is
                historical; later evidence can change support.
              </p>
              {view.reconciliation.state !== "PUBLISHED" && (
                <p>
                  No skill estimates were published by this command. Review the
                  current record and deliberately confirm a new update when
                  ready.
                </p>
              )}
              <div className="debrief-grid">
                {view.reconciliation.skills.map((s) => (
                  <article className="debrief-card" key={s.id}>
                    <h4>{s.name}</h4>
                    <p className="practice-reference">
                      {s.id} v{s.version} · saved revision {s.revision}
                    </p>
                    <Badge>{s.band}</Badge>
                    <p>
                      {s.support} support · {s.freshness} at the recorded time
                    </p>
                    <p>
                      {s.independentFamilies} independent families ·{" "}
                      {s.exclusionCount} exclusions
                      {s.contradicted ? " · Contradictory evidence" : ""}
                      {s.regressionCap ? " · Regression cap applied" : ""}
                    </p>
                    <p>
                      {s.created
                        ? "New saved interpretation"
                        : "Existing identical interpretation reused"}
                    </p>
                    <ul>
                      {s.reasons.map((reason) => (
                        <li key={reason}>{reason}</li>
                      ))}
                    </ul>
                  </article>
                ))}
              </div>
            </>
          )}
          <p className="fine-print">
            After a confirmed update, explicitly refresh Learning Debrief to
            inspect its saved interpretations. The separate real-learner
            Ascension snapshot is unchanged.
          </p>
        </>
      )}
      {confirm && view && (
        <Dialog
          title="Publish skill interpretations?"
          eyebrow="EXPLICIT JAVA ESTIMATE WRITE"
          onClose={() => setConfirm(false)}
        >
          <p>
            Java will read available evidence, assistance, exposure, mappings
            and epoch for this exact graph using its current UTC time. It will
            atomically save all four skill interpretations and a receipt.
            Missing evidence can remain UNKNOWN; a pass is not automatic
            mastery.
          </p>
          <ul>
            {view.catalog.map((s) => (
              <li key={s.id}>
                {s.name} — {s.id} v{s.version}
              </li>
            ))}
          </ul>
          <p>
            No recommendations, rewards, preferences, challenges or submissions
            will be changed.
          </p>
          <Button disabled={busy} onClick={() => void act("publish")}>
            Confirm skill interpretation update
          </Button>
        </Dialog>
      )}
    </section>
  );
}
