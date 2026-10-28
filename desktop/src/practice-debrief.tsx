import { useEffect, useRef, useState } from "react";
import { Badge, Button } from "./components";
import type { LearningDebrief, SubmissionResult } from "./practice-contract";

const label = (value: string) => value.replaceAll("_", " ").toLowerCase();
/** Explicit read only. No status polling, reconnect, execution or policy publication. */
export function PracticeDebrief({
  evaluation,
  identity,
  disabled,
}: {
  evaluation: SubmissionResult;
  identity: { profileId: string; sessionId: string };
  disabled: boolean;
}) {
  const [data, setData] = useState<LearningDebrief>();
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const fence = useRef(0);
  // The parent keys this component by authoritative semantic identity. An unchanged
  // status object does not invalidate the captured read; unmount fences pending IPC.
  useEffect(
    () => () => {
      fence.current++;
    },
    [],
  );
  async function read() {
    const token = ++fence.current;
    setBusy(true);
    setFailed(false);
    setData(undefined);
    try {
      const r = await window.codefit.readPracticeDebrief(
        {
          commandId: evaluation.commandId,
          presentationId: evaluation.presentationId,
        },
        identity.profileId,
        identity.sessionId,
      );
      if (token !== fence.current) return;
      if (
        !r.ok ||
        r.method !== "practice.submission.debrief" ||
        r.profileId !== identity.profileId ||
        r.sessionId !== identity.sessionId ||
        r.commandId !== evaluation.commandId ||
        r.presentationId !== evaluation.presentationId
      )
        throw new Error();
      setData(r.debrief);
    } catch {
      if (token === fence.current) setFailed(true);
    } finally {
      if (token === fence.current) setBusy(false);
    }
  }
  return (
    <section className="learning-debrief" aria-label="Learning debrief">
      <header className="debrief-heading">
        <div>
          <span className="eyebrow">THE LEARNING RECORD</span>
          <h3>Learning debrief</h3>
          <p>
            Evidence, scope and the next question. A saved result is not a
            mastery award.
          </p>
        </div>
        <Button
          secondary
          disabled={disabled || busy}
          onClick={() => void read()}
        >
          {busy
            ? "Reading learning record…"
            : data
              ? "Refresh learning debrief"
              : "Read learning debrief"}
        </Button>
      </header>
      {failed && (
        <p role="alert">
          Evaluation saved. Learning summary unavailable. Retry this read; your
          submission will not run again.
        </p>
      )}
      {!data && !failed && (
        <p className="fine-print" role="status">
          {busy
            ? "Reading Java’s persisted record."
            : "Read the finalized evaluation and its persisted evidence. No learning state changes."}
        </p>
      )}
      {data && (
        <>
          <div className="debrief-provenance">
            <Badge>Read-only · Java-owned</Badge>
            <span>
              Captured {data.readAt} · evaluation revision{" "}
              {data.evaluationRevision}
            </span>
          </div>
          <p className="fine-print">
            Captured learning record, not live state. Evidence and mappings can
            change independently; refresh this debrief to read them again.
          </p>
          <div className="debrief-grid">
            <article className="debrief-card debrief-summary">
              <span className="eyebrow">01 / EVALUATION SUMMARY</span>
              <h4>{label(data.evaluation.outcome ?? "UNAVAILABLE")}</h4>
              {(data.evaluation.withdrawn || data.corrected) && (
                <p role="status">
                  {data.evaluation.withdrawn
                    ? "Withdrawn result — this observation does not support completion."
                    : "Corrected evaluation — this revision replaces an earlier interpretation."}
                </p>
              )}
              <p>
                Java recorded the check results below against the exact
                submitted artifact. The result is limited to the evaluator’s
                supported mechanisms.
              </p>
              <details>
                <summary>
                  Published rubric limitations (immutable specification)
                </summary>
                <p>{data.limitations}</p>
                <p className="fine-print">
                  This is the original versioned rubric text, not a claim that
                  later evaluator work is still pending.
                </p>
              </details>
              <p className="fine-print">
                A finite passing check supports only its tested scope. Stress
                observations do not prove safety across all schedules.
                Unsupported verification and tool failures do not establish lack
                of ability.
              </p>
              <details>
                <summary>Inspect check scope and behavior targets</summary>
                <ul className="debrief-checks">
                  {data.checks.map((c) => (
                    <li key={c.id}>
                      <div>
                        <strong>{c.id}</strong> <Badge>{c.status}</Badge>
                      </div>
                      <p>
                        {c.required ? "Required" : "Optional"} · {label(c.kind)}
                      </p>
                      <p>{c.feedback}</p>
                      <p className="fine-print">{c.limitations}</p>
                      {c.targets.map((t) => (
                        <p
                          className="debrief-id"
                          key={`${t.skillId}:${t.skillVersion}:${t.behavior}`}
                        >
                          Target: {t.skillId} v{t.skillVersion} · behavior{" "}
                          {t.behavior + 1}
                          <br />
                          {data.skills.find(
                            (s) =>
                              s.id === t.skillId &&
                              s.version === t.skillVersion,
                          )?.behaviors[t.behavior] ??
                            "Exact behavior definition unavailable."}
                        </p>
                      ))}
                    </li>
                  ))}
                </ul>
              </details>
            </article>
            <article className="debrief-card">
              <span className="eyebrow">02 / PERSISTED EVIDENCE</span>
              <h4>
                {data.evidence.status === "RECORDED"
                  ? "One correlated observation"
                  : "Evidence unavailable"}
              </h4>
              <p>
                Checks belong to one deliberate submission. They are not
                independent solves.
              </p>
              {data.evidence.status === "RECORDED" ? (
                <>
                  <dl className="debrief-facts">
                    <dt>Observed outcome</dt>
                    <dd>{data.evidence.outcome}</dd>
                    <dt>Availability</dt>
                    <dd>{data.evidence.availability}</dd>
                    <dt>Provenance</dt>
                    <dd>{data.evidence.provenance}</dd>
                    <dt>Assistance</dt>
                    <dd>{data.evidence.assistance}</dd>
                    <dt>Exposure</dt>
                    <dd>{data.evidence.exposure}</dd>
                    <dt>Familiarity declaration</dt>
                    <dd>{data.familiarity}</dd>
                    <dt>Solution revealed</dt>
                    <dd>
                      {data.solutionRevealed ? "Yes" : "No recorded reveal"}
                    </dd>
                    <dt>Prior family submissions</dt>
                    <dd>{data.priorFamilySubmissions}</dd>
                  </dl>
                  {data.evidence.mappingStatus !== "EXACT" && (
                    <p role="status">
                      Skill mapping {label(data.evidence.mappingStatus)}. This
                      view does not substitute a newer mapping or claim current
                      skill support.
                    </p>
                  )}
                  <p className="fine-print">
                    First recorded presentation does not prove an unseen task.
                    Assistance declarations are not proctoring.
                  </p>
                  <details>
                    <summary>Evidence identity and limitations</summary>
                    <p className="debrief-id">
                      Observation {data.evidence.id} · revision{" "}
                      {data.evidence.revision}
                    </p>
                    <p className="debrief-id">
                      {data.evidence.previousId
                        ? `Replaces ${data.evidence.previousId}`
                        : "Original observation"}
                    </p>
                    <p>Recorded {data.evidence.recordedAt}</p>
                    <p className="debrief-id">
                      Mapping {data.evidence.mappingId} v
                      {data.evidence.mappingVersion} ·{" "}
                      {data.evidence.mappingStatus}
                    </p>
                    <ul>
                      {data.evidence.limitations.map((l) => (
                        <li key={l}>{label(l)}</li>
                      ))}
                    </ul>
                  </details>
                </>
              ) : (
                <p role="status">
                  {label(data.evidence.status)}. No positive evidence conclusion
                  is made.
                </p>
              )}
            </article>
            <article className="debrief-card">
              <span className="eyebrow">03 / SKILL DEVELOPMENT</span>
              <h4>Capability stays evidence-based</h4>
              <p className="debrief-id">
                Exact graph: {data.graphId} v{data.graphVersion}
              </p>
              <p>No new proficiency estimate was published by this view.</p>
              {data.skillStatus !== "available" && (
                <p role="status">
                  Exact skill graph unavailable. No alternative version was
                  substituted.
                </p>
              )}
              {data.skills.map((s) => (
                <section className="debrief-skill" key={`${s.id}:${s.version}`}>
                  <h5>{s.name}</h5>
                  <p className="debrief-id">
                    {s.id} v{s.version}
                  </p>
                  {s.interpretation.status === "historical" ? (
                    <>
                      <Badge>Historical interpretation</Badge>
                      <p>
                        {s.interpretation.band} · {s.interpretation.support}{" "}
                        support
                      </p>
                      <p className="fine-print">
                        As of {s.interpretation.asOf} · revision{" "}
                        {s.interpretation.revision}. Saved freshness:{" "}
                        {s.interpretation.freshness}. Current support has not
                        been evaluated; this may predate the submission.
                      </p>
                      {s.interpretation.contradicted && (
                        <p>
                          Conflicting evidence was recorded in this
                          interpretation.
                        </p>
                      )}
                      <p>
                        {s.interpretation.exclusionCount} excluded observations.
                        Behavior coverage below belongs only to that saved
                        revision.
                      </p>
                      <ul>
                        {s.interpretation.supportedBehaviors.map((b) => (
                          <li key={b}>
                            {s.behaviors[b] ?? `Behavior ${b + 1}`}
                          </li>
                        ))}
                      </ul>
                      <details>
                        <summary>Java reason codes and later changes</summary>
                        <ul>
                          {[
                            ...s.interpretation.reasons,
                            ...s.interpretation.warnings,
                          ].map((v, i) => (
                            <li key={`${v}:${i}`}>{label(v)}</li>
                          ))}
                        </ul>
                      </details>
                    </>
                  ) : (
                    <p>
                      {s.interpretation.status === "missing"
                        ? "Not yet evaluated. No saved proficiency interpretation exists."
                        : `Interpretation unavailable: ${label(s.interpretation.reason ?? "UNAVAILABLE")}.`}
                    </p>
                  )}
                </section>
              ))}
            </article>
            <article className="debrief-card debrief-next">
              <span className="eyebrow">04 / CONTINUE LEARNING</span>
              <h4>Choose your next investigation</h4>
              <p>
                Return to your current draft, inspect this saved result, or
                close this view and deliberately choose another published
                practice challenge.
              </p>
              <Badge>Exploratory practice</Badge>
              <p>
                <a href="#practice-controls">Return to practice controls</a>
              </p>
              <p className="fine-print">
                Current adaptive eligibility: NOT_EVALUATED. This is not a new
                recommendation or assignment. Starting another challenge still
                requires Java authorization and a committed exposure.
              </p>
              <details>
                <summary>Exact learning context</summary>
                <p className="debrief-id">
                  Quest {data.questId} v{data.questVersion}
                </p>
                <p className="debrief-id">
                  Rubric {data.rubricId} v{data.rubricVersion}
                </p>
                <p className="debrief-id">
                  Family {data.familyId} · variant {data.variantId} ·{" "}
                  {data.cohort}
                </p>
                <p className="debrief-id">
                  Submission {data.evaluation.commandId}
                </p>
              </details>
            </article>
          </div>
        </>
      )}
    </section>
  );
}
