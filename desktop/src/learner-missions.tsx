import { useEffect, useRef, useState } from "react";
import { Badge, Button } from "./components";
import { Icon } from "./icons";
import {
  LearnerMissionsResultSchema,
  type LearnerProjection,
} from "./learner-contract";
import type { LearnerMissions } from "./mission-contract";
import { snapshotTime } from "./learner-profile";
const words = (s: string) => s.toLowerCase().replaceAll("_", " ");

export function LearnerMissionsConsole({
  profile,
  onProfile,
  onDisconnect,
}: {
  profile: LearnerProjection;
  onProfile: () => void;
  onDisconnect: () => void;
}) {
  const [data, setData] = useState<LearnerMissions>();
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState(false);
  const [reload, setReload] = useState(0);
  const [selected, setSelected] = useState("");
  const [filter, setFilter] = useState("all");
  const generation = useRef(0);
  useEffect(() => {
    const current = ++generation.current;
    setBusy(true);
    setData(undefined);
    setError(false);
    setSelected("");
    void window.codefit
      .readLearnerMissions()
      .then((raw) => {
        if (generation.current !== current) return;
        const reply = LearnerMissionsResultSchema.parse(raw);
        if (
          !reply.ok ||
          reply.result.profileId !== profile.profileId ||
          reply.result.sessionId !== profile.sessionId ||
          reply.result.capturedAt !== profile.capturedAt
        )
          throw Error();
        setData(reply.result);
        setSelected(reply.result.activities[0]?.command ?? "");
      })
      .catch(() => {
        if (generation.current === current) setError(true);
      })
      .finally(() => {
        if (generation.current === current) setBusy(false);
      });
    return () => {
      generation.current++;
    };
  }, [profile.profileId, profile.sessionId, profile.capturedAt, reload]);
  const activity = data?.activities.find((a) => a.command === selected);
  const quest =
    activity &&
    data?.quests.find(
      (q) => q.id === activity.questId && q.version === activity.questVersion,
    );
  return (
    <section
      className="recorded-missions"
      aria-label="Recorded Mission Console"
    >
      <div className="screen-subhead skill-source">
        <p>
          Real learning activity. Carefully preserved.
          <br />
          <span>
            Frozen capture · {snapshotTime(profile.capturedAt)}. Newer changes
            require a new capture.
          </span>
        </p>
        <Badge tone="cyan">JAVA-OWNED HISTORY</Badge>
      </div>
      <div className="skill-read-controls">
        <span>Current assignments and eligibility are not evaluated.</span>
        <Button
          secondary
          disabled={busy}
          onClick={() => setReload((n) => n + 1)}
        >
          Refresh frozen view
        </Button>
        <Button secondary onClick={busy ? onDisconnect : onProfile}>
          {busy ? "Cancel mission read and disconnect" : "Profile details"}
        </Button>
      </div>
      {busy || error ? (
        <div className="atlas-unavailable" role={error ? "alert" : "status"}>
          <Icon name={error ? "alert" : "clock"} size={28} />
          <h2>
            {error
              ? "Mission history unavailable"
              : "Reading the frozen record"}
          </h2>
          <p>
            {error
              ? "The snapshot could not be read safely. Reconnect the profile if a retry is unavailable. Demonstration missions are not substituted."
              : "Java is reading recorded metadata. No quest is being assigned or started."}
          </p>
        </div>
      ) : (
        data && (
          <>
            <div className="mission-record-hero">
              <div>
                <p className="eyebrow">YOUR ENGINEERING RECORD</p>
                <h2>
                  Every attempt has context.
                  <br />
                  <em>History is not an assignment.</em>
                </h2>
                <p>
                  Inspect recorded submissions and the published catalog present
                  in this snapshot. Starting, resuming and evaluating work
                  remain in the existing JavaFX application.
                </p>
                <Badge tone="violet">READ-ONLY / NO LEARNING ACTIONS</Badge>
              </div>
              <div className="mission-record-mark" aria-hidden="true">
                <Icon name="mission" size={64} />
                <span>
                  RECORD
                  <br />/ CONTEXT
                </span>
              </div>
            </div>
            <div className="mission-facts">
              <div>
                <strong>
                  {data.historyStatus === "AVAILABLE"
                    ? data.activities.length
                    : "—"}
                  {data.historyTruncated ? "+" : ""}
                </strong>
                <span>Recent saved submissions</span>
              </div>
              <div>
                <strong>{data.review.dueScheduledCards}</strong>
                <span>Scheduled cards due at capture</span>
              </div>
              <div>
                <strong>{data.review.recordedReviews}</strong>
                <span>Recorded review events</span>
              </div>
              <div>
                <Badge tone="violet">NOT EVALUATED</Badge>
                <span>Current recommendation</span>
              </div>
            </div>
            <p className="mission-facts-note">
              Review counts use {data.review.asOfDay} UTC. They are historical
              scheduling facts, not a newly selected review session or evidence
              of mastery.
            </p>
            <div className="mission-history-layout">
              <section className="mission-history">
                <div className="atlas-section-heading">
                  <h2>Submission journal</h2>
                  <label>
                    <span className="sr-only">Filter recorded submissions</span>
                    <select
                      value={filter}
                      onChange={(e) => setFilter(e.target.value)}
                    >
                      <option value="all">All recorded stages</option>
                      <option value="FINALIZED">Finalized records</option>
                      <option value="unfinished">Unfinished at capture</option>
                    </select>
                  </label>
                </div>
                {data.historyStatus !== "AVAILABLE" ? (
                  <div className="mission-empty">
                    <Icon name="alert" />
                    <h3>History cannot be interpreted safely</h3>
                    <p>
                      A required table or durable record is missing, corrupt or
                      incompatible. No result is inferred.
                    </p>
                  </div>
                ) : data.activities.length === 0 ? (
                  <div className="mission-empty">
                    <Icon name="mission" />
                    <h3>No saved submissions in this snapshot</h3>
                    <p>
                      Published quests below are catalog metadata, not assigned
                      work. No learning action was performed by opening this
                      screen.
                    </p>
                  </div>
                ) : (
                  <div className="mission-history-list">
                    {data.activities
                      .filter(
                        (a) =>
                          filter === "all" ||
                          (filter === "unfinished"
                            ? a.stage !== "FINALIZED"
                            : a.stage === filter),
                      )
                      .map((a) => {
                        const q = data.quests.find(
                          (q) =>
                            q.id === a.questId && q.version === a.questVersion,
                        );
                        return (
                          <button
                            key={a.command}
                            aria-pressed={selected === a.command}
                            className={`mission-record ${selected === a.command ? "selected" : ""}`}
                            onClick={() => setSelected(a.command)}
                          >
                            <div>
                              <span className="eyebrow">
                                {snapshotTime(a.submittedAt)}
                              </span>
                              <strong>
                                {q?.title ?? "Exact quest metadata unavailable"}
                              </strong>
                              <code>{a.command}</code>
                            </div>
                            <div>
                              <Badge tone={a.withdrawn ? "violet" : "cyan"}>
                                {a.withdrawn
                                  ? "WITHDRAWN"
                                  : a.outcome
                                    ? words(a.outcome)
                                    : words(a.stage)}
                              </Badge>
                              <small>Recorded · not current status</small>
                              <Icon name="chevron" size={15} />
                            </div>
                          </button>
                        );
                      })}
                  </div>
                )}
                {data.historyTruncated && (
                  <p className="fine-print">
                    Showing the latest 20 saved submissions by durable sequence.
                    Older records remain in JavaFX history.
                  </p>
                )}
              </section>
              <aside
                className="mission-record-inspector"
                aria-label="Recorded submission details"
              >
                <p className="eyebrow">IMMUTABLE CONTEXT / READ-ONLY</p>
                {activity ? (
                  <>
                    <h2>{quest?.title ?? "Unresolved historical quest"}</h2>
                    <Badge tone="violet">HISTORICAL {activity.stage}</Badge>
                    <dl>
                      <dt>Submission identity</dt>
                      <dd>
                        <code>{activity.command}</code>
                      </dd>
                      <dt>Presentation receipt</dt>
                      <dd>
                        <code>{activity.presentation}</code>
                      </dd>
                      <dt>Exact quest</dt>
                      <dd>
                        <code>
                          {activity.questId} / v{activity.questVersion}
                        </code>
                      </dd>
                      <dt>Previously presented challenge</dt>
                      <dd>
                        <code>
                          {activity.challengeId} / v{activity.challengeVersion}
                        </code>
                      </dd>
                      <dt>Submitted</dt>
                      <dd>{snapshotTime(activity.submittedAt)}</dd>
                      <dt>Evaluated</dt>
                      <dd>
                        {activity.evaluatedAt
                          ? snapshotTime(activity.evaluatedAt)
                          : "Not finalized"}
                      </dd>
                      <dt>Recorded outcome</dt>
                      <dd>
                        {activity.withdrawn
                          ? "Withdrawn — no active result asserted"
                          : activity.outcome
                            ? words(activity.outcome)
                            : "No finalized outcome"}
                      </dd>
                      <dt>Evaluation revision</dt>
                      <dd>{activity.revision ?? "Not finalized"}</dd>
                      <dt>Catalog match</dt>
                      <dd>
                        {activity.metadata === "EXACT_PUBLISHED"
                          ? "Exact published quest version"
                          : "Missing or incompatible metadata"}
                      </dd>
                    </dl>
                    <p>
                      A saved RUNNING stage does not mean a process is running
                      now. Results describe recorded evaluation only, not
                      current skill support.
                    </p>
                  </>
                ) : (
                  <>
                    <h2>The context behind the record</h2>
                    <p>
                      Select a saved submission to inspect its exact identities
                      and historical outcome. Private code, answers and
                      evaluator diagnostics remain protected.
                    </p>
                  </>
                )}
                <div className="mission-action-boundary">
                  <Icon name="lock" />
                  <p>
                    Challenge content and recovery require a separately
                    authorized writable workflow.
                  </p>
                  <button disabled>Open / resume unavailable</button>
                  <button disabled>Evaluate / review unavailable</button>
                </div>
              </aside>
            </div>
            <section
              className="published-quests"
              aria-label="Published quest metadata"
            >
              <div className="atlas-section-heading">
                <h2>Published catalog in this snapshot</h2>
                <Badge tone="violet">NOT ASSIGNMENTS</Badge>
              </div>
              <p>
                Only exact versions matching the bundled Java catalog are named.
                Reserved cohorts stay reserved; prompts and candidate challenges
                are not exposed.
              </p>
              {data.catalogStatus !== "AVAILABLE" && (
                <p role="status">
                  Some catalog metadata is missing or incompatible. No newer or
                  similar version is substituted.
                </p>
              )}
              <div className="published-quest-grid">
                {data.quests.map((q) => (
                  <article key={`${q.id}/${q.version}`}>
                    <Badge tone="violet">{q.cohort}</Badge>
                    <h3>{q.title}</h3>
                    <code>
                      {q.id} / v{q.version}
                    </code>
                    <span>
                      <Icon name="lock" size={13} />
                      Eligibility not evaluated
                    </span>
                  </article>
                ))}
              </div>
            </section>
            <details className="mission-decision-history">
              <summary>
                Persisted recommendation record references{" "}
                <Badge tone="violet">INTERPRETATION NOT EVALUATED</Badge>
              </summary>
              <p>
                These timestamps establish that decision records were saved.
                Their outputs, current validity and candidate content are not
                interpreted or exposed here.
              </p>
              {data.decisionStatus !== "AVAILABLE" ? (
                <p>Decision metadata unavailable.</p>
              ) : data.decisions.length === 0 ? (
                <p>No saved decision references in this snapshot.</p>
              ) : (
                <ul>
                  {data.decisions.map((d) => (
                    <li key={d.id}>
                      <time>{snapshotTime(d.asOf)}</time>
                      <code>{d.id}</code>
                    </li>
                  ))}
                </ul>
              )}
              {data.decisionsTruncated && <p>Latest 10 references only.</p>}
            </details>
            <div className="mission-draft-boundary">
              <Icon name="info" />
              <p>
                <strong>Author drafts are separate.</strong> The four Knowledge
                Compiler definitions and their Predict/Debug/Build/Defend
                identifiers are unreviewed preview metadata. They cannot become
                missions, grades, XP or mastery through this screen.
              </p>
            </div>
          </>
        )
      )}
    </section>
  );
}
