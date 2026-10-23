import { useRef, useState, type CSSProperties } from "react";
import { Icon } from "./icons";
import { Badge, Button, tabKeys } from "./components";
import { skills } from "./data";
import { spatialNeighbor, focusSkill, preferredScroll } from "./skill-layout";
export function Tree({
  onMission,
  unknown,
}: {
  onMission: () => void;
  unknown: boolean;
}) {
  const [selected, setSelected] = useState("debug");
  const [zoom, setZoom] = useState(1);
  const [tab, setTab] = useState<"overview" | "evidence">("overview");
  const [evidence, setEvidence] = useState("Current");
  const [branch, setBranch] = useState("All branches");
  const map = useRef<HTMLDivElement>(null);
  const node = skills.find((n) => n.id === selected)!;
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
  const keyboard = (
    e: React.KeyboardEvent<HTMLButtonElement>,
    index: number,
  ) => {
    if (
      [
        "ArrowRight",
        "ArrowDown",
        "ArrowLeft",
        "ArrowUp",
        "Home",
        "End",
      ].includes(e.key)
    ) {
      e.preventDefault();
      const next = spatialNeighbor(skills, skills[index].id, e.key);
      if (next) focusSkill(`skill-${next.id}`);
    }
  };
  return (
    <>
      <div className="screen-subhead">
        <p>
          Capability grows through connections.
          <br />
          <span>
            Explore a sample path. Inspect the evidence behind the idea.
          </span>
        </p>
        <Badge tone="violet">ILLUSTRATIVE GRAPH</Badge>
      </div>
      <div className="tree-layout">
        <section
          className="tree-panel"
          aria-label="Interactive sample skill tree"
        >
          <div className="tree-toolbar">
            <div className="segmented" aria-label="Skill branch filter">
              {["All branches", "Java", "Engineering"].map((b) => (
                <button
                  key={b}
                  aria-pressed={branch === b}
                  onClick={() => setBranch(b)}
                >
                  {b}
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
            aria-label="Scrollable skill map. Arrow keys on a node move focus. Use scrollbars or trackpad to explore."
            onPointerDown={(e) => {
              if (e.target === e.currentTarget) e.currentTarget.focus();
            }}
          >
            <div className={`map-canvas zoom-${Math.round(zoom * 100)}`}>
              <div className="map-coordinates">
                <span>ENGINEERING ATLAS</span>
                <span>EXAMPLE / V.01</span>
              </div>
              <svg
                className="connections"
                preserveAspectRatio="none"
                viewBox="0 0 900 640"
                aria-hidden="true"
              >
                <defs>
                  <linearGradient id="pathColor" x1="0" y1="1" x2="0" y2="0">
                    <stop stopColor="var(--cyan)" />
                    <stop offset="1" stopColor="var(--violet)" />
                  </linearGradient>
                </defs>
                <ellipse
                  cx="450"
                  cy="555"
                  rx="115"
                  ry="50"
                  className="origin-orbit"
                />
                {skills.flatMap((n) =>
                  n.parents.map((id) => {
                    const p = skills.find((s) => s.id === id)!;
                    return (
                      <path
                        key={id + n.id}
                        className={`${n.state === "Locked" ? "locked-path" : ""} ${n.id === selected || p.id === selected ? "selected-path" : ""}`}
                        d={`M${p.x} ${p.y - 20} C${p.x} ${p.y - 90},${n.x} ${n.y + 100},${n.x} ${n.y + 15}`}
                      />
                    );
                  }),
                )}
                <path className="map-axis" d="M450 25V620M80 555H820" />
              </svg>
              {skills.map((s, i) => (
                <button
                  id={`skill-${s.id}`}
                  key={s.id}
                  className={`skill-node ${s.state.toLowerCase().replace(" ", "-")} ${selected === s.id ? "selected" : ""} ${branch !== "All branches" && !s.branches.includes(branch as "Java" | "Engineering") ? "dimmed" : ""} ${unknown ? "unknown-node" : ""}`}
                  style={
                    {
                      "--x": `${s.x / 9}%`,
                      "--y": `${s.y / 6.4}%`,
                    } as CSSProperties
                  }
                  onClick={() => select(s.id)}
                  onKeyDown={(e) => keyboard(e, i)}
                  aria-pressed={selected === s.id}
                  aria-label={`${s.name}, ${unknown ? "Unknown" : s.state}, example skill`}
                >
                  <span className="node-shape">
                    <Icon name={s.icon} size={s.id === "core" ? 26 : 21} />
                    {!unknown && (
                      <i>
                        <Icon
                          name={
                            s.state === "Completed"
                              ? "check"
                              : s.state === "Locked"
                                ? "lock"
                                : s.state === "In progress"
                                  ? "bolt"
                                  : "plus"
                          }
                          size={9}
                        />
                      </i>
                    )}
                  </span>
                  <strong>{s.name}</strong>
                  <small>{unknown ? "UNOBSERVED" : s.subtitle}</small>
                  <span className="node-tooltip">
                    {unknown ? "Unknown" : s.state} · Select to inspect
                  </span>
                </button>
              ))}
            </div>
          </div>
          <div className="map-bottom">
            <button
              className="narrow-inspect text-button"
              onClick={() => {
                document.getElementById("selected-skill-title")?.focus();
                document
                  .getElementById("selected-skill-title")
                  ?.scrollIntoView({
                    block: "center",
                    behavior: preferredScroll(),
                  });
              }}
            >
              Inspect {node.name} <span aria-hidden="true">↓</span>
            </button>
            <div className="map-legend">
              <span>
                <i className="complete" />
                Completed
              </span>
              <span>
                <i className="progress" />
                In progress
              </span>
              <span>
                <i />
                Available
              </span>
              <span>
                <Icon name="lock" size={11} />
                Locked
              </span>
            </div>
            <div className="zoom-controls">
              <button
                aria-label="Zoom out"
                disabled={zoom <= 1}
                onClick={() => setZoom(Math.max(1, zoom - 0.25))}
              >
                <Icon name="minus" size={15} />
              </button>
              <output aria-label="Tree zoom">{Math.round(zoom * 100)}%</output>
              <button
                aria-label="Zoom in"
                disabled={zoom >= 1.5}
                onClick={() => setZoom(Math.min(1.5, zoom + 0.25))}
              >
                <Icon name="plus" size={15} />
              </button>
            </div>
          </div>
        </section>
        <aside className="skill-inspector" aria-label="Selected skill">
          <div className="inspector-top">
            <span className="eyebrow">NODE INSPECTOR</span>
            <span className="node-id">
              S / {String(skills.indexOf(node) + 1).padStart(2, "0")}
            </span>
          </div>
          <div
            className={`inspector-symbol ${node.state === "Locked" ? "muted-symbol" : ""}`}
          >
            <Icon name={node.icon} size={30} />
          </div>
          <Badge tone={node.state === "Locked" ? "neutral" : "cyan"}>
            {unknown ? "UNKNOWN" : node.state.toUpperCase()} · EXAMPLE
          </Badge>
          <h2 id="selected-skill-title" tabIndex={-1}>
            {node.name}
          </h2>
          <p className="inspector-description">{node.description}</p>
          <div
            className="tabs"
            role="tablist"
            onKeyDown={tabKeys}
            aria-label="Skill information"
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
                {node.parents.length ? (
                  node.parents.map((id) => (
                    <button
                      key={id}
                      className="prerequisite"
                      onClick={() => select(id)}
                    >
                      <Icon name="tree" size={15} />
                      {skills.find((s) => s.id === id)!.name}
                      <Icon name="arrow" size={13} />
                    </button>
                  ))
                ) : (
                  <p className="small-muted">
                    A starting point. No prerequisite in this sample.
                  </p>
                )}
                <div className="evidence-note">
                  <span className="mini-line" />
                  <h3>Understanding, not a score.</h3>
                  <p>
                    {unknown
                      ? "No direct evidence. Support and freshness are unknown."
                      : "This state illustrates the design. It is not a mastery estimate or an XP unlock."}
                  </p>
                </div>
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
                <label className="field-label" htmlFor="evidence-state">
                  Evidence scenario
                </label>
                <select
                  id="evidence-state"
                  value={evidence}
                  onChange={(e) => setEvidence(e.target.value)}
                >
                  {["Current", "Unknown", "Stale", "Contradictory"].map((s) => (
                    <option key={s}>{s}</option>
                  ))}
                </select>
                <div className="evidence-detail">
                  <Badge tone="violet">SIMULATED EVIDENCE</Badge>
                  <h3>
                    {unknown || evidence === "Unknown"
                      ? "No direct observations"
                      : evidence === "Stale"
                        ? "A fresh observation is due"
                        : evidence === "Contradictory"
                          ? "The evidence disagrees"
                          : "A hypothesis to build on"}
                  </h3>
                  <p>
                    {unknown || evidence === "Unknown"
                      ? "Band: UNKNOWN. Support: INSUFFICIENT. Freshness: NO_OBSERVATION."
                      : evidence === "Stale"
                        ? "Earlier observations would remain visible with a STALE freshness label. Recency is never silently erased."
                        : evidence === "Contradictory"
                          ? "A newer observation challenges the earlier estimate. Both must be explained; no progress percentage can resolve this."
                          : "Example band: DEVELOPING. Example support: LIMITED. No real evaluation or evidence reference is connected."}
                  </p>
                </div>
              </>
            )}
          </div>
          <div className="inspector-action">
            <Button onClick={onMission} icon="arrow">
              Explore a mission
            </Button>
            <small>Opens a demonstration briefing</small>
          </div>
        </aside>
      </div>
      <p className="map-note">
        <Icon name="info" size={14} /> Example topology and states; not the
        authoritative Java skill graph. Scroll to pan, + to zoom, arrow keys to
        explore nodes.
      </p>
    </>
  );
}
