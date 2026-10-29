import { useEffect, useRef, useState } from "react";
import { Button, Badge } from "./components";
import {
  AnalyticsResponse,
  type AnalyticsData,
  type AnalyticsRevision,
} from "./analytics-contract";
const bands = [
  "UNKNOWN",
  "DEVELOPING",
  "INDEPENDENT",
  "TRANSFER_SUPPORTED",
  "RETENTION_SUPPORTED",
];
const words = (s: string) => s.replaceAll("_", " ").toLowerCase();
const date = (s: string) =>
  new Date(s).toISOString().slice(0, 16).replace("T", " ") + " UTC";
function Band({ value }: { value: string }) {
  return (
    <span className="analytics-band">
      <span aria-hidden="true">
        {bands.map((b) => (
          <i key={b} className={b === value ? "active" : ""} />
        ))}
      </span>
      {words(value)}
    </span>
  );
}
export function SkillAnalytics() {
  const [identity, setIdentity] = useState<{
      profileId: string;
      sessionId: string;
    }>(),
    [data, setData] = useState<AnalyticsData>(),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const [skill, setSkill] = useState(""),
    [selected, setSelected] = useState<number>(),
    [event, setEvent] = useState<string>(),
    [from, setFrom] = useState(""),
    [to, setTo] = useState(""),
    [left, setLeft] = useState(""),
    [right, setRight] = useState(""),
    [zoom, setZoom] = useState(1);
  const generation = useRef(0),
    running = useRef(false);
  useEffect(
    () => () => {
      generation.current++;
      void window.codefit.disconnectPractice();
    },
    [],
  );
  async function connect() {
    if (running.current) return;
    running.current = true;
    const token = ++generation.current;
    setBusy(true);
    setData(undefined);
    setError("");
    try {
      const r = await window.codefit.connectPractice();
      if (token !== generation.current) return;
      if (!r.ok) throw Error();
      setIdentity(r);
    } catch {
      if (token === generation.current) {
        setIdentity(undefined);
        setError("Practice profile unavailable. No analytics were loaded.");
      }
    } finally {
      if (token === generation.current) {
        running.current = false;
        setBusy(false);
      }
    }
  }
  async function disconnect() {
    generation.current++;
    running.current = false;
    setIdentity(undefined);
    setData(undefined);
    setSelected(undefined);
    setEvent(undefined);
    setLeft("");
    setRight("");
    setError("");
    setBusy(false);
    await window.codefit.disconnectPractice();
  }
  async function read(page = 0) {
    if (!identity || running.current) return;
    running.current = true;
    const token = ++generation.current;
    setBusy(true);
    setError("");
    try {
      const r = await window.codefit.readPracticeAnalytics(
        { page, snapshot: page === 0 ? "" : data!.snapshot },
        identity.profileId,
        identity.sessionId,
      );
      if (token !== generation.current) return;
      const parsed = AnalyticsResponse.safeParse(r);
      if (
        !parsed.success ||
        parsed.data.profileId !== identity.profileId ||
        parsed.data.sessionId !== identity.sessionId
      )
        throw Error();
      const d = parsed.data;
      setData(d);
      setSkill((s) => (d.skills.some((x) => x.id === s) ? s : d.skills[0].id));
      setSelected(undefined);
      setEvent(undefined);
      setLeft("");
      setRight("");
    } catch {
      if (token === generation.current) {
        setData(undefined);
        setSelected(undefined);
        setEvent(undefined);
        setLeft("");
        setRight("");
        setError(
          "Analytics unavailable or records changed. Explicitly refresh to capture a coherent page. No learning state was changed.",
        );
      }
    } finally {
      if (token === generation.current) {
        running.current = false;
        setBusy(false);
      }
    }
  }
  const inRange = (at: string) =>
    (!from || at.slice(0, 10) >= from) && (!to || at.slice(0, 10) <= to);
  const points =
    data?.revisions
      .filter((r) => r.skill === skill && inRange(r.asOf))
      .sort((a, b) => a.asOf.localeCompare(b.asOf) || a.id - b.id) || [];
  const events =
    data?.events
      .filter(
        (e) =>
          (!e.skills.length || e.skills.includes(skill)) &&
          inRange(e.recordedAt),
      )
      .sort(
        (a, b) =>
          a.recordedAt.localeCompare(b.recordedAt) || a.sequence - b.sequence,
      ) || [];
  const chosen = points.find((r) => r.id === selected) || points.at(-1),
    chosenEvent = events.find((e) => e.id === event);
  const a = points.find(
      (r) => String(r.id) === (left || String(points[0]?.id)),
    ),
    b = points.find(
      (r) => String(r.id) === (right || String(points.at(-1)?.id)),
    );
  const compatible =
    !!a &&
    !!b &&
    a.id !== b.id &&
    a.status === "HISTORICAL" &&
    b.status === "HISTORICAL" &&
    a.policy === b.policy &&
    a.graphVersion === b.graphVersion &&
    a.version === b.version;
  const pick = (id: string, revision?: number) => {
    setSkill(id);
    setSelected(revision);
    setEvent(undefined);
    setLeft("");
    setRight("");
  };
  const times = points.map((r) => Date.parse(r.asOf)),
    min = Math.min(...times),
    span = Math.max(...times) - min;
  return (
    <div className="analytics-page">
      <section className="analytics-hero panel">
        <div>
          <span className="eyebrow">THE RECORD / LEARNING INSIGHTS</span>
          <h1>Skill Analytics</h1>
          <p>Trace the evidence. Understand the interpretation.</p>
          <p className="fine-print">
            Saved history from your disposable Java practice profile. No scores,
            inferred growth, or automatic updates.
          </p>
        </div>
        <div className="analytics-seal" aria-hidden="true">
          ⌁
          <small>
            HISTORY
            <br />
            NOT A SCORE
          </small>
        </div>
      </section>
      <div className="analytics-toolbar panel">
        <div className="draft-actions">
          {!identity ? (
            <Button onClick={() => void connect()} disabled={busy}>
              Connect analytics practice profile
            </Button>
          ) : (
            <>
              <Button onClick={() => void read()} disabled={busy}>
                Refresh analytics
              </Button>
              <Button secondary onClick={() => void disconnect()}>
                Disconnect analytics
              </Button>
            </>
          )}
        </div>
        <p className="fine-print">
          {identity
            ? `Profile ${identity.profileId} · session ${identity.sessionId}`
            : "Explicit practice opt-in required. This never connects to a real learner snapshot."}
        </p>
        {busy && <p role="status">Reading Java-owned history…</p>}
        {error && <p role="alert">{error}</p>}
        {identity && !data && !busy && !error && (
          <p>
            Read the saved record with Refresh analytics. This does not publish
            skill interpretations.
          </p>
        )}
      </div>
      {data && (
        <>
          <div className="analytics-provenance">
            <Badge>READ ONLY</Badge>
            <span>Captured {date(data.capturedAt)}</span>
            <span>
              Java graph v{data.graphVersion} · page {data.page + 1}
            </span>
          </div>
          <div className="analytics-controls panel">
            <label>
              Skill
              <select
                aria-label="Skill"
                value={skill}
                onChange={(e) => pick(e.target.value)}
              >
                {data.skills.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              From (UTC)
              <input
                type="date"
                value={from}
                onChange={(e) => setFrom(e.target.value)}
              />
            </label>
            <label>
              Through (UTC)
              <input
                type="date"
                value={to}
                onChange={(e) => setTo(e.target.value)}
              />
            </label>
            <Button
              secondary
              onClick={() => {
                setFrom("");
                setTo("");
              }}
            >
              Clear dates
            </Button>
            <p>
              Filters apply to this page: up to 16 saved revisions and 32
              observations. Older pages are never silently combined.
            </p>
          </div>
          <nav className="analytics-index" aria-label="Analytics charts">
            {[
              "Development",
              "Evidence",
              "Coverage",
              "Dependencies",
              "Compare",
            ].map((x, i) => (
              <a key={x} href={`#analytics-${i}`}>
                0{i + 1} / {x}
              </a>
            ))}
          </nav>
          <section
            className="panel analytics-chart"
            id="analytics-0"
            aria-label="Ordinal skill development"
          >
            <ChartTitle
              n="01"
              title="The shape of your record"
              subtitle="Ordinal skill development · each dot is one saved interpretation. No interpolated growth."
            />
            {points.length ? (
              <>
                <div
                  className="analytics-timeline"
                  role="group"
                  aria-label="Saved ordinal events"
                >
                  <svg
                    viewBox="0 0 900 280"
                    preserveAspectRatio="none"
                    aria-hidden="true"
                  >
                    {bands.map((band, i) => (
                      <g key={band}>
                        <text x="8" y={250 - i * 49}>
                          {words(band)}
                        </text>
                        <line
                          x1="200"
                          x2="870"
                          y1={245 - i * 49}
                          y2={245 - i * 49}
                        />
                      </g>
                    ))}
                  </svg>
                  {points
                    .filter((r) => r.interpretation !== null)
                    .map((r) => {
                      const x =
                          25 +
                          (span ? (Date.parse(r.asOf) - min) / span : 0.5) * 68,
                        y =
                          87.5 -
                          bands.indexOf(r.interpretation?.band || "UNKNOWN") *
                            17.5;
                      return (
                        <button
                          key={r.id}
                          className={`analytics-point ${chosen?.id === r.id ? "selected" : ""}`}
                          style={{
                            left: `${x}%`,
                            top: `${y}%`,
                            transform: "translate(-50%,-50%)",
                          }}
                          onClick={() => setSelected(r.id)}
                          aria-label={`Revision ${r.id}, ${r.interpretation ? words(r.interpretation.band) : r.status}, ${date(r.asOf)}`}
                          title={`Revision ${r.id} · ${date(r.asOf)}`}
                        >
                          {r.status === "HISTORICAL" ? "●" : "×"}
                        </button>
                      );
                    })}
                </div>
                <div className="analytics-axis">
                  <span>{date(points[0].asOf)}</span>
                  <span>{date(points.at(-1)!.asOf)}</span>
                </div>
                <p className="fine-print">
                  ● Saved event. Unavailable interpretations appear only in the
                  ledger. Equal-time revisions remain distinct. Epoch and policy
                  boundaries are shown below.
                </p>
              </>
            ) : (
              <Empty text="No saved interpretations match this skill and date range. Nothing has been inferred." />
            )}
            {chosen && <RevisionDetail r={chosen} />}
            <details>
              <summary>Accessible revision ledger ({points.length})</summary>
              <div className="analytics-ledger">
                {points.map((r) => (
                  <button key={r.id} onClick={() => setSelected(r.id)}>
                    #{r.id} · {date(r.asOf)} ·{" "}
                    {r.interpretation?.band || r.status} · epoch {r.epoch}
                  </button>
                ))}
              </div>
            </details>
          </section>
          <section
            className="panel analytics-chart"
            id="analytics-1"
            aria-label="Persisted evidence activity"
          >
            <ChartTitle
              n="02"
              title="Behind the interpretation"
              subtitle="Canonical observation events by recorded time. Corrections and withdrawals are not new independent successes."
            />
            {events.length ? (
              <>
                <div
                  className="analytics-evidence-track"
                  role="group"
                  aria-label="Evidence activity timeline"
                >
                  {events.map((e) => (
                    <button
                      className={`analytics-event ${e.change.toLowerCase()} ${event === e.id ? "selected" : ""}`}
                      key={e.id}
                      onClick={() => setEvent(e.id)}
                    >
                      <span aria-hidden="true">
                        {e.change === "RETRACTION"
                          ? "−"
                          : e.change === "SUPERSESSION"
                            ? "↻"
                            : "•"}
                      </span>
                      <strong>#{e.sequence}</strong>
                      <small>{e.recordedAt.slice(5, 10)}</small>
                      <em>{words(e.change)}</em>
                    </button>
                  ))}
                </div>
                <p className="fine-print">
                  • Original observation · ↻ correction · − withdrawal. Spacing
                  is by event order, not elapsed time. Unmapped observations
                  remain visible.
                </p>
              </>
            ) : (
              <Empty text="No persisted evidence events on this page match the filters." />
            )}
            {chosenEvent && (
              <div className="analytics-inspector">
                <h3>
                  {words(chosenEvent.change)} · observation #
                  {chosenEvent.sequence}
                </h3>
                <p>
                  {words(chosenEvent.outcome)} · {words(chosenEvent.provenance)}{" "}
                  ·{" "}
                  {chosenEvent.head
                    ? "Current recorded head; source not reverified"
                    : "Superseded historical record"}
                </p>
                <p>
                  Recorded {date(chosenEvent.recordedAt)} · source event{" "}
                  {chosenEvent.observedAt
                    ? date(chosenEvent.observedAt)
                    : "time unknown"}
                </p>
                <p>
                  Assistance: {words(chosenEvent.assistance)} · Exposure:{" "}
                  {words(chosenEvent.exposure)} · Availability:{" "}
                  {words(chosenEvent.availability)}
                </p>
                <p className="analytics-ref">
                  Evidence {chosenEvent.id}
                  <br />
                  Correlated source root {chosenEvent.root} · revision{" "}
                  {chosenEvent.revision}
                </p>
              </div>
            )}
            <details>
              <summary>Accessible observation ledger ({events.length})</summary>
              {events.map((e) => (
                <p key={e.id}>
                  <button
                    className="text-button"
                    onClick={() => setEvent(e.id)}
                  >
                    #{e.sequence} · {date(e.recordedAt)} · {words(e.change)} ·{" "}
                    {words(e.outcome)}
                  </button>
                </p>
              ))}
            </details>
          </section>
          <section
            className="panel analytics-chart"
            id="analytics-2"
            aria-label="Observable behavior coverage"
          >
            <ChartTitle
              n="03"
              title="What was actually supported"
              subtitle="Exact observable behaviors × saved interpretations. Coverage is historical and is not a mastery percentage."
            />
            <div
              className="analytics-matrix"
              role="table"
              aria-label="Behavior coverage matrix"
            >
              <div role="row" className="analytics-matrix-head">
                <span role="columnheader">Published behavior</span>
                <span role="columnheader">Saved support</span>
              </div>
              {data.skills.map((s) => {
                const r =
                  s.id === skill
                    ? chosen
                    : data.revisions.find(
                        (r) => r.skill === s.id && inRange(r.asOf),
                      );
                return s.behaviors.map((behavior, i) => {
                  const state =
                    r && !r.interpretation
                      ? "UNAVAILABLE"
                      : r?.interpretation?.coverage[i] || "NOT_EVALUATED";
                  return (
                    <div role="row" key={s.id + i}>
                      <div role="cell">
                        <strong>{s.name}</strong>
                        <p>{behavior}</p>
                      </div>
                      <div role="cell">
                        <button
                          className={`coverage-cell ${state.toLowerCase()}`}
                          onClick={() => pick(s.id, r?.id)}
                        >
                          {words(state)}
                          <small>
                            {r
                              ? `revision ${r.id}`
                              : "No saved revision on page"}
                          </small>
                        </button>
                      </div>
                    </div>
                  );
                });
              })}
            </div>
            <p className="fine-print">
              Supported · unsupported · contradicted · excluded · not evaluated.
              Exclusions and assistance remain part of the saved policy
              interpretation; a check is not an independent family.
            </p>
          </section>
          <section
            className="panel analytics-chart"
            id="analytics-3"
            aria-label="Exact skill prerequisites"
          >
            <ChartTitle
              n="04"
              title="The engineering pathway"
              subtitle="Exact published prerequisite edges. Structure does not mean an earned unlock."
            />
            <div className="draft-actions">
              <Button
                secondary
                onClick={() => setZoom((z) => Math.max(0.75, z - 0.25))}
              >
                Zoom out
              </Button>
              <Button
                secondary
                onClick={() => setZoom((z) => Math.min(2, z + 0.25))}
              >
                Zoom in
              </Button>
              <span>Scroll to pan · arrow keys move between nodes</span>
            </div>
            <div
              className="analytics-graph-scroll"
              tabIndex={0}
              aria-label="Scrollable prerequisite graph"
            >
              <div className="analytics-graph" style={{ width: 1000 * zoom }}>
                <svg viewBox="0 0 1000 190" aria-hidden="true">
                  <defs>
                    <marker
                      id="analytics-arrow"
                      viewBox="0 0 10 10"
                      refX="9"
                      refY="5"
                      markerWidth="7"
                      markerHeight="7"
                      orient="auto"
                    >
                      <path d="M0 0 L10 5 L0 10z" />
                    </marker>
                  </defs>
                  {data.edges.map((e) => (
                    <line
                      key={e.from + e.to}
                      x1={
                        data.skills.findIndex((s) => s.id === e.from) * 245 +
                        195
                      }
                      y1="95"
                      x2={
                        data.skills.findIndex((s) => s.id === e.to) * 245 + 15
                      }
                      y2="95"
                      markerEnd="url(#analytics-arrow)"
                    />
                  ))}
                </svg>
                {data.skills.map((s, i) => (
                  <button
                    key={s.id}
                    className={`analytics-node ${skill === s.id ? "selected" : ""}`}
                    style={{ left: `${i * 24.5 + 1.5}%` }}
                    onClick={() => pick(s.id)}
                    onKeyDown={(e) => {
                      if (
                        !["ArrowLeft", "ArrowRight", "Home", "End"].includes(
                          e.key,
                        )
                      )
                        return;
                      e.preventDefault();
                      const n =
                        e.key === "Home"
                          ? 0
                          : e.key === "End"
                            ? 3
                            : Math.max(
                                0,
                                Math.min(
                                  3,
                                  i + (e.key === "ArrowLeft" ? -1 : 1),
                                ),
                              );
                      e.currentTarget.parentElement
                        ?.querySelectorAll<HTMLButtonElement>(".analytics-node")
                        [n]?.focus();
                    }}
                  >
                    <small>JAVA / {i + 1}</small>
                    <strong>{s.name}</strong>
                    <span>Definition v{s.version}</span>
                  </button>
                ))}
              </div>
            </div>
            <details>
              <summary>Prerequisite outline and exact references</summary>
              {data.edges.map((e) => (
                <p key={e.from + e.to}>
                  {e.from} v1 → {e.to} v1
                </p>
              ))}
            </details>
            <p>
              Selected: {data.skills.find((s) => s.id === skill)?.name}. Its
              saved record, evidence and behavior coverage are shown above.
            </p>
          </section>
          <section
            className="panel analytics-chart"
            id="analytics-4"
            aria-label="Historical interpretation comparison"
          >
            <ChartTitle
              n="05"
              title="Two moments, one capability"
              subtitle="Compare actual saved revisions of the selected skill. Different epochs are disclosed; incompatible versions are never silently compared."
            />
            <div className="analytics-compare-controls">
              {[
                [
                  "Earlier interpretation",
                  left || String(a?.id || ""),
                  setLeft,
                ],
                [
                  "Later interpretation",
                  right || String(b?.id || ""),
                  setRight,
                ],
              ].map(([label, value, setter]) => (
                <label key={label as string}>
                  {label as string}
                  <select
                    aria-label={label as string}
                    value={value as string}
                    onChange={(e) =>
                      (setter as (s: string) => void)(e.target.value)
                    }
                  >
                    <option value="">Select saved revision</option>
                    {points.map((r) => (
                      <option value={r.id} key={r.id}>
                        #{r.id} · {date(r.asOf)} · {r.status}
                      </option>
                    ))}
                  </select>
                </label>
              ))}
            </div>
            {compatible ? (
              <>
                <div className="analytics-compare">
                  <RevisionDetail r={a!} />
                  <RevisionDetail r={b!} />
                </div>
                <p>
                  {a!.epoch !== b!.epoch
                    ? "Epoch boundary: these interpretations use different reset scopes."
                    : "Same epoch. A difference in band is a saved interpretation, not a quantified amount of learning."}
                </p>
              </>
            ) : (
              <Empty text="Choose two distinct, available revisions with the same exact skill, graph and policy. No compatible pair is selected." />
            )}
          </section>
          <div className="analytics-pagination">
            <Button
              secondary
              disabled={busy || data.page === 0}
              onClick={() => void read(data.page - 1)}
            >
              {data.page === 1 ? "Latest page / refresh" : "Newer page"}
            </Button>
            <span>Page {data.page + 1} · coherent capture</span>
            <Button
              secondary
              disabled={busy || !data.more}
              onClick={() => void read(data.page + 1)}
            >
              Older page
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
function ChartTitle({
  n,
  title,
  subtitle,
}: {
  n: string;
  title: string;
  subtitle: string;
}) {
  return (
    <header className="analytics-chart-heading">
      <span className="analytics-number">{n}</span>
      <div>
        <h2>{title}</h2>
        <p>{subtitle}</p>
      </div>
    </header>
  );
}
function Empty({ text }: { text: string }) {
  return (
    <div className="analytics-empty">
      <span aria-hidden="true">◇</span>
      <p>{text}</p>
    </div>
  );
}
function RevisionDetail({ r }: { r: AnalyticsRevision }) {
  return (
    <article className="analytics-inspector">
      <div className="analytics-inspector-head">
        <h3>Saved interpretation #{r.id}</h3>
        <Badge>{r.status}</Badge>
      </div>
      <p>{date(r.asOf)} · as-of time (publication time is not recorded)</p>
      {r.interpretation && (
        <>
          <Band value={r.interpretation.band} />
          <p>
            {words(r.interpretation.support)} support ·{" "}
            {words(r.interpretation.freshness)} at saved time ·{" "}
            {r.interpretation.families} independent families
          </p>
          <p>
            Behavior coverage: {r.interpretation.coverage.map(words).join(", ")}
          </p>
          <ul>
            {r.interpretation.explanations.map((x) => (
              <li key={x}>{x}</li>
            ))}
          </ul>
          {r.interpretation.exclusions.length > 0 && (
            <p>
              Excluded: {r.interpretation.exclusions.map(words).join(" · ")}
            </p>
          )}
          <details>
            <summary>
              Contributing evidence references (
              {r.interpretation.evidence.length}
              {r.interpretation.evidenceTruncated ? "+" : ""})
            </summary>
            {r.interpretation.evidence.map((id) => (
              <p className="analytics-ref" key={id}>
                {id}
              </p>
            ))}
          </details>
        </>
      )}
      {r.warnings.length > 0 && (
        <p className="analytics-warning">
          {r.warnings.map(words).join(" · ")}. This saved interpretation is not
          current support.
        </p>
      )}
      <p className="analytics-ref">
        {r.skill} v{r.version} · graph v{r.graphVersion} · {r.policy} · epoch{" "}
        {r.epoch}
      </p>
    </article>
  );
}
