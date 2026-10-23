import { useEffect, useRef, useState, type CSSProperties } from "react";
import { Badge, Button, tabKeys } from "./components";
import { Icon } from "./icons";
import {
  LearnerSkillsResultSchema,
  type LearnerProjection,
} from "./learner-contract";
import type { LearnerSkills, LearnerSkill } from "./skill-contract";
import { snapshotTime } from "./learner-profile";
import {
  focusSkill,
  layoutGraph,
  preferredScroll,
  spatialNeighbor,
} from "./skill-layout";

const words = (value: string) => value.toLowerCase().replaceAll("_", " ");
const warnings: Record<string, string> = {
  EVIDENCE_WITHDRAWN:
    "Evidence used by this saved revision was later withdrawn. Its historical band is not active support.",
  EVIDENCE_REVISED:
    "Evidence used by this saved revision has a newer interpretation. This history has not been revalidated.",
  EVIDENCE_MISSING:
    "A referenced evidence record is unavailable in this snapshot.",
  MAPPING_CHANGED: "An evidence mapping has changed since this interpretation.",
  EPOCH_CHANGED:
    "A later learner reset places this interpretation in a previous estimate epoch.",
};
const reasons: Record<string, string> = {
  NO_DIRECT_EVIDENCE: "No adequate direct evidence was available.",
  UNAVAILABLE_SOURCE: "A source was unavailable.",
  SUPERSEDED: "A newer interpretation superseded evidence.",
  MAPPING_UNAVAILABLE: "An exact evidence mapping was unavailable.",
  UNKNOWN_TIME: "An observation lacked a trustworthy event time.",
  FUTURE_EVENT: "An event was after the estimate cutoff.",
  BEFORE_EPOCH: "Evidence predated the active estimate epoch.",
  CORRELATED: "Related observations did not add independent opportunities.",
  UNCERTAIN_PROVENANCE: "Provenance did not establish independent performance.",
  UNAVAILABLE_EVALUATION:
    "An unavailable evaluation made no negative capability claim.",
  INVALID_EVALUATION: "An evaluation could not be validated.",
  ASSISTED:
    "Assisted performance supported practice progress rather than independence.",
  UNKNOWN_ASSISTANCE: "Assistance conditions were unknown.",
  FAMILIAR: "A familiar task did not establish unfamiliar transfer.",
  UNKNOWN_EXPOSURE: "Prior familiarity was unknown.",
  DIAGNOSTIC_SCOPE: "Diagnostic performance had a limited assessment scope.",
  MISSING_BEHAVIORS: "Required behavior coverage was incomplete.",
  OBSERVED_PROGRESS: "Direct observations supported developing capability.",
  INDEPENDENT_OPPORTUNITY: "An independent opportunity contributed evidence.",
  NEED_DIVERSITY: "More unrelated challenge families were needed.",
  INDEPENDENT_FAMILIES:
    "Distinct independent families supported the tested behavior.",
  TRANSFER: "The policy found qualifying transfer evidence.",
  RETENTION: "The policy found qualifying delayed retention evidence.",
  RETENTION_NOT_DELAYED: "The retention interval did not qualify.",
  CONTRADICTED: "New observations contradicted earlier support.",
  REPEATED_FAILURE: "Repeated eligible failures capped the interpretation.",
  RESTORATION_PENDING:
    "New independent validation was still needed after contradiction.",
  STALE: "The observations were older than the policy freshness window.",
  INDEPENDENT_VALIDATION_DUE: "Fresh independent validation was needed.",
  CURRENT_SCOPE:
    "Support met the policy's current-scope conditions at the original estimate time.",
};
function nodeLabel(node: LearnerSkill) {
  const e = node.estimate;
  if (e.status === "missing") return "Not yet evaluated";
  if (e.status === "unavailable") return "Estimate unavailable";
  if (e.warnings.length) return "History needs review";
  if (e.contradicted) return "Historical contradiction";
  return `Historical ${words(e.band!)}`;
}

/** Mounted with a session key. Every asynchronous response also checks both frozen identities. */
export function LearnerTree({
  profile,
  onProfile,
  onDisconnect,
}: {
  profile: LearnerProjection;
  onProfile: () => void;
  onDisconnect: () => void;
}) {
  const [data, setData] = useState<LearnerSkills>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);
  const generation = useRef(0);
  useEffect(() => {
    const current = ++generation.current;
    setLoading(true);
    setError(undefined);
    setData(undefined);
    void window.codefit
      .readLearnerSkills()
      .then((raw) => {
        if (current !== generation.current) return;
        const reply = LearnerSkillsResultSchema.parse(raw);
        if (!reply.ok) throw new Error(reply.code);
        if (
          reply.result.profileId !== profile.profileId ||
          reply.result.sessionId !== profile.sessionId ||
          reply.result.capturedAt !== profile.capturedAt
        )
          throw new Error("PROFILE_MISMATCH");
        setData(reply.result);
      })
      .catch(() => {
        if (current === generation.current)
          setError(
            "The skill snapshot could not be read safely. Reconnect the profile if a retry is unavailable.",
          );
      })
      .finally(() => {
        if (current === generation.current) setLoading(false);
      });
    return () => {
      generation.current++;
    };
  }, [profile.profileId, profile.sessionId, profile.capturedAt, reload]);
  return (
    <>
      <div className="screen-subhead skill-source">
        <p>
          Explore the evidence behind your engineering capabilities.
          <br />
          <span>
            Frozen capture · {snapshotTime(profile.capturedAt)}. Newer changes
            require a new capture.
          </span>
        </p>
        <Badge tone="cyan">JAVA-OWNED GRAPH</Badge>
      </div>
      <div className="skill-read-controls">
        <span>Current capability is not evaluated by this read-only view.</span>
        <Button
          secondary
          disabled={loading}
          onClick={() => setReload((n) => n + 1)}
        >
          Refresh frozen view
        </Button>
        <Button secondary onClick={loading ? onDisconnect : onProfile}>
          {loading ? "Cancel skill read and disconnect" : "Profile details"}
        </Button>
      </div>
      {loading ? (
        <div className="skill-read-state" role="status">
          <Icon name="tree" size={32} />
          <h2>Reading your skill atlas</h2>
          <p>
            Loading published definitions and saved interpretations from the
            private snapshot.
          </p>
        </div>
      ) : error ? (
        <div className="skill-read-state" role="alert">
          <h2>Skill view unavailable</h2>
          <p>{error}</p>
        </div>
      ) : data?.status === "unavailable" ? (
        <div className="skill-read-state" role="status">
          <h2>Graph unavailable</h2>
          <p>
            {data.reason === "LIMIT_EXCEEDED"
              ? "This graph exceeds the supported read limits."
              : "The exact supported graph is missing or incompatible. A different version was not substituted."}
          </p>
          <p>
            This is a data availability limit, not a judgment of your ability.
          </p>
        </div>
      ) : (
        data && <Atlas data={data} />
      )}
    </>
  );
}
function Atlas({ data }: { data: LearnerSkills }) {
  const geometry = layoutGraph(data.nodes, data.edges);
  const [selected, setSelected] = useState(data.nodes[0].ref.id);
  const [zoom, setZoom] = useState(1);
  const [tab, setTab] = useState<"overview" | "evidence">("overview");
  const [branch, setBranch] = useState("all");
  const map = useRef<HTMLDivElement>(null);
  const node = data.nodes.find((n) => n.ref.id === selected)!;
  const e = node.estimate;
  const domains = [...new Set(data.nodes.map((n) => n.domainId))];
  const select = (id: string) => {
    setSelected(id);
    setTab("overview");
  };
  const fit = () => {
    setZoom(1);
    if (map.current) {
      map.current.scrollLeft = 0;
      map.current.scrollTop = 0;
    }
  };
  const points = geometry.points;
  const parents = data.edges
    .filter((edge) => edge.dependent.id === selected)
    .map((edge) => data.nodes.find((n) => n.ref.id === edge.prerequisite.id)!);
  return (
    <>
      {data.newerGraphAvailable && (
        <p className="skill-warning" role="status">
          A newer graph version is present. This view uses the exact supported
          graph below; prerequisites are not mixed across versions.
        </p>
      )}
      <div className="tree-layout learner-atlas">
        <section className="tree-panel" aria-label="Authoritative skill tree">
          <div className="tree-toolbar">
            <div className="segmented" aria-label="Skill domain filter">
              {["all", ...domains].map((d) => (
                <button
                  key={d}
                  aria-pressed={branch === d}
                  onClick={() => setBranch(d)}
                >
                  {d === "all" ? "All domains" : d.replaceAll("-", " ")}
                </button>
              ))}
            </div>
            <button
              className="icon-button"
              onClick={fit}
              aria-label="Reset tree view"
            >
              <Icon name="expand" size={17} />
            </button>
          </div>
          <div
            className="map-viewport"
            ref={map}
            tabIndex={0}
            aria-label="Scrollable skill map. Arrow keys on a node move spatially. Scroll or use a trackpad to pan."
          >
            <div
              className="map-canvas authoritative-canvas"
              style={{
                width: `${zoom * 100}%`,
                height: `${zoom * 100}%`,
                minWidth: Math.max(530, geometry.width * 0.6) * zoom,
                minHeight: Math.max(490, geometry.height * 0.68) * zoom,
              }}
            >
              <div className="map-coordinates">
                <span>ENGINEERING ATLAS</span>
                <span>GRAPH V.{data.graph!.version}</span>
              </div>
              <svg
                className="connections"
                preserveAspectRatio="none"
                viewBox={`0 0 ${geometry.width} ${geometry.height}`}
                aria-hidden="true"
              >
                {data.edges.map((edge) => {
                  const p = points.find((n) => n.id === edge.prerequisite.id)!,
                    n = points.find((n) => n.id === edge.dependent.id)!;
                  return (
                    <path
                      key={`${p.id}>${n.id}`}
                      className={
                        p.id === selected || n.id === selected
                          ? "selected-path"
                          : ""
                      }
                      d={`M${p.x} ${p.y - 20} C${p.x} ${p.y - 90},${n.x} ${n.y + 100},${n.x} ${n.y + 15}`}
                    />
                  );
                })}
                <path
                  className="map-axis"
                  d={`M${geometry.width / 2} 40V${geometry.height - 30}`}
                />
              </svg>
              {data.nodes.map((s) => {
                const point = points.find((p) => p.id === s.ref.id)!;
                return (
                  <button
                    id={`real-skill-${s.ref.id}`}
                    key={s.ref.id}
                    className={`skill-node evidence-node ${selected === s.ref.id ? "selected" : ""} ${s.estimate.status === "historical" ? "has-history" : "unknown-node"} ${s.estimate.warnings.length || s.estimate.contradicted ? "needs-review" : ""} ${branch !== "all" && s.domainId !== branch ? "dimmed" : ""}`}
                    style={
                      {
                        "--x": `${(point.x / geometry.width) * 100}%`,
                        "--y": `${(point.y / geometry.height) * 100}%`,
                      } as CSSProperties
                    }
                    aria-label={`${s.name}, ${nodeLabel(s)}, current capability not evaluated`}
                    aria-pressed={selected === s.ref.id}
                    onClick={() => select(s.ref.id)}
                    onKeyDown={(event) => {
                      if (
                        [
                          "ArrowUp",
                          "ArrowDown",
                          "ArrowLeft",
                          "ArrowRight",
                          "Home",
                          "End",
                        ].includes(event.key)
                      ) {
                        event.preventDefault();
                        const next = spatialNeighbor(
                          points,
                          s.ref.id,
                          event.key,
                        );
                        if (next) focusSkill(`real-skill-${next.id}`);
                      }
                    }}
                  >
                    <span className="node-shape">
                      <Icon name="layers" size={22} />
                      <i>
                        <Icon
                          name={
                            s.estimate.warnings.length ||
                            s.estimate.contradicted
                              ? "alert"
                              : "info"
                          }
                          size={10}
                        />
                      </i>
                    </span>
                    <strong>{s.name}</strong>
                    <small>
                      {s.estimate.status === "historical"
                        ? "SAVED INTERPRETATION"
                        : "NO CURRENT ESTIMATE"}
                    </small>
                    <span className="node-tooltip">
                      {nodeLabel(s)} · Select to inspect
                    </span>
                  </button>
                );
              })}
            </div>
          </div>
          <div className="map-bottom">
            <button
              className="narrow-inspect text-button"
              onClick={() => {
                const heading = document.getElementById("selected-skill-title");
                heading?.focus({ preventScroll: true });
                heading?.scrollIntoView({
                  block: "center",
                  behavior: preferredScroll(),
                });
              }}
            >
              Inspect {node.name} <span aria-hidden="true">↓</span>
            </button>
            <div className="map-legend">
              <span>
                <i className="history" />
                Saved history
              </span>
              <span>
                <i />
                Unavailable
              </span>
              <span>
                <Icon name="alert" size={12} />
                Review needed
              </span>
            </div>
            <div className="zoom-controls">
              <button
                aria-label="Zoom out"
                disabled={zoom <= 1}
                onClick={() => setZoom((n) => Math.max(1, n - 0.25))}
              >
                <Icon name="minus" size={15} />
              </button>
              <output aria-label="Tree zoom">{Math.round(zoom * 100)}%</output>
              <button
                aria-label="Zoom in"
                disabled={zoom >= 1.5}
                onClick={() => setZoom((n) => Math.min(1.5, n + 0.25))}
              >
                <Icon name="plus" size={15} />
              </button>
            </div>
          </div>
        </section>
        <aside className="skill-inspector" aria-label="Selected skill">
          <div className="inspector-top">
            <span className="eyebrow">NODE INSPECTOR</span>
            <span className="node-id">DEFINITION V.{node.ref.version}</span>
          </div>
          <div className="inspector-symbol">
            <Icon name="layers" size={30} />
          </div>
          <Badge tone={e.status === "historical" ? "violet" : "neutral"}>
            {nodeLabel(node)}
          </Badge>
          <h2 id="selected-skill-title" tabIndex={-1}>
            {node.name}
          </h2>
          <p className="inspector-description">{node.description}</p>
          <p className="exact-skill-ref">
            {node.ref.id} · v{node.ref.version} · {words(node.lifecycle)}
          </p>
          <div
            className="tabs"
            role="tablist"
            aria-label="Skill information"
            onKeyDown={tabKeys}
          >
            {(["overview", "evidence"] as const).map((t) => (
              <button
                role="tab"
                id={`skill-tab-${t}`}
                aria-controls="skill-tabpanel"
                aria-selected={tab === t}
                tabIndex={tab === t ? 0 : -1}
                key={t}
                onClick={() => setTab(t)}
              >
                {t === "overview" ? "Overview" : "Evidence"}
              </button>
            ))}
          </div>
          <div
            role="tabpanel"
            id="skill-tabpanel"
            aria-labelledby={`skill-tab-${tab}`}
            className="inspector-content"
          >
            {tab === "overview" ? (
              <>
                <p className="eyebrow">PREREQUISITE PATH</p>
                {parents.length ? (
                  parents.map((p) => (
                    <button
                      className="prerequisite"
                      key={p.ref.id}
                      onClick={() => select(p.ref.id)}
                    >
                      <Icon name="tree" size={15} />
                      {p.name} · v{p.ref.version}
                      <Icon name="arrow" size={13} />
                    </button>
                  ))
                ) : (
                  <p className="small-muted">
                    No prerequisites in this exact graph.
                  </p>
                )}
                <p className="small-muted">
                  Prerequisites describe learning order. Current readiness and
                  unlocks are not evaluated here.
                </p>
                <p className="eyebrow">OBSERVABLE BEHAVIORS</p>
                <ul className="skill-behaviors">
                  {node.behaviors.map((b) => (
                    <li key={b}>{b}</li>
                  ))}
                </ul>
                <Button
                  secondary
                  onClick={() => setTab("evidence")}
                  icon="arrow"
                >
                  Inspect evidence
                </Button>
              </>
            ) : (
              <>
                <div className="evidence-note">
                  <h3>Current capability unavailable</h3>
                  <p>
                    This view reads saved interpretations. It does not
                    revalidate evidence or publish a new estimate.
                  </p>
                </div>
                {e.status === "historical" ? (
                  <>
                    <Badge tone="violet">HISTORICAL · NOT ACTIVE SUPPORT</Badge>
                    <h3>Saved interpretation</h3>
                    <dl className="skill-facts">
                      <dt>Band at estimate time</dt>
                      <dd>{e.band}</dd>
                      <dt>Support then</dt>
                      <dd>{e.support}</dd>
                      <dt>Freshness then</dt>
                      <dd>{e.freshness}</dd>
                      <dt>Estimate as of</dt>
                      <dd>{snapshotTime(e.asOf!)}</dd>
                      <dt>Last meaningful observation</dt>
                      <dd>
                        {e.lastMeaningfulObservation
                          ? snapshotTime(e.lastMeaningfulObservation)
                          : "No observation"}
                      </dd>
                      <dt>Revision / policy</dt>
                      <dd>
                        {e.revision} / {e.policyVersion}
                      </dd>
                      <dt>Independent families then</dt>
                      <dd>{e.independentFamilies}</dd>
                      <dt>Behavior coverage then</dt>
                      <dd>
                        {e.supportedBehaviors.length} of {node.behaviors.length}
                      </dd>
                      <dt>Contradicted then</dt>
                      <dd>{e.contradicted ? "Yes" : "No"}</dd>
                      <dt>Regression cap then</dt>
                      <dd>{e.regressionCap ? "Yes" : "No"}</dd>
                    </dl>
                    {e.warnings.map((w) => (
                      <p className="skill-warning" key={w}>
                        {warnings[w]}
                      </p>
                    ))}
                    <p className="small-muted">
                      Absence of a warning does not establish current evidence
                      validity. The original snapshot may omit later changes.
                    </p>
                    <h3>Reasons at estimate time</h3>
                    <ul className="skill-reasons">
                      {e.reasons.map((r) => (
                        <li key={r}>{reasons[r]}</li>
                      ))}
                    </ul>
                    <details>
                      <summary>Permitted evidence summary</summary>
                      <p>
                        {e.contributionCount} contributions · {e.exclusionCount}{" "}
                        exclusions in this saved interpretation.
                      </p>
                      {(
                        [
                          ["Provenance", e.provenanceCounts],
                          ["Assistance", e.assistanceCounts],
                          ["Exposure", e.exposureCounts],
                        ] as const
                      ).map(([name, counts]) => (
                        <div key={name}>
                          <h4>{name} at estimate time</h4>
                          <ul>
                            {counts.map((c) => (
                              <li key={c.code}>
                                {words(c.code)}: {c.count}
                              </li>
                            ))}
                          </ul>
                        </div>
                      ))}
                    </details>
                  </>
                ) : (
                  <p className="small-muted">
                    {e.status === "missing"
                      ? "No saved estimate exists for this exact skill. Unknown means unobserved, never zero ability."
                      : `The latest revision is unavailable (${words(e.reason!)}). An older positive estimate was not substituted.`}
                  </p>
                )}
              </>
            )}
          </div>
        </aside>
      </div>
      <p className="map-note">
        <Icon name="info" size={14} />
        <span>
          Java catalog · {data.graph!.id} v{data.graph!.version}. Historical
          interpretations use exact skill versions. No XP, completion score or
          inferred proficiency.
        </span>
      </p>
    </>
  );
}
