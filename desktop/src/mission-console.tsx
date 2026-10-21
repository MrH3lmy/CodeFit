import { useEffect, useState } from "react";
import { Icon } from "./icons";
import { MissionArt } from "./art";
import { Badge, Button, StateView, tabKeys } from "./components";
import { missions, type Mission } from "./data";
export function MissionConsole({
  onMission,
}: {
  onMission: (m: Mission) => void;
}) {
  const [filter, setFilter] = useState("All missions");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState(missions[0]);
  const [saved, setSaved] = useState<string[]>([]);
  const visible = missions.filter(
    (m) =>
      (filter === "All missions" || m.status === filter) &&
      (m.title + " " + m.category).toLowerCase().includes(search.toLowerCase()),
  );
  useEffect(() => {
    if (visible.length && !visible.some((m) => m.id === selected.id))
      setSelected(visible[0]);
  }, [filter, search]);
  return (
    <>
      <div className="screen-subhead">
        <p>
          Choose a challenge that moves you forward.
          <br />
          <span>Depth over volume. Understanding over points.</span>
        </p>
        <Badge tone="violet">DEMONSTRATION CATALOG</Badge>
      </div>
      <div className="mission-controls">
        <div
          className="tabs"
          role="tablist"
          onKeyDown={tabKeys}
          aria-label="Mission status filter"
        >
          {[
            "All missions",
            "Available",
            "In progress",
            "Completed",
            "Blocked",
          ].map((f) => (
            <button
              role="tab"
              id={`filter-${f.replaceAll(" ", "-")}`}
              aria-controls="mission-results"
              key={f}
              aria-selected={filter === f}
              tabIndex={filter === f ? 0 : -1}
              onClick={() => {
                setFilter(f);
                const next = missions.find(
                  (m) => f === "All missions" || m.status === f,
                );
                if (next) setSelected(next);
              }}
            >
              {f}
              {f === "All missions" && <span>04</span>}
            </button>
          ))}
        </div>
        <label className="search">
          <Icon name="search" size={16} />
          <input
            aria-label="Search missions"
            placeholder="Find your next challenge"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          {search && (
            <button
              className="icon-button"
              onClick={() => setSearch("")}
              aria-label="Clear mission search"
            >
              <Icon name="close" size={14} />
            </button>
          )}
        </label>
      </div>
      <div
        id="mission-results"
        role="tabpanel"
        aria-labelledby={`filter-${filter.replaceAll(" ", "-")}`}
      >
        {visible.length === 0 ? (
          <StateView
            kind="empty"
            onReset={() => {
              setSearch("");
              setFilter("All missions");
            }}
          />
        ) : (
          <div className="mission-layout">
            <section className="mission-list" aria-label="Sample missions">
              <div className="list-caption">
                <span>{visible.length} MISSIONS TO EXPLORE</span>
                <span>CURATED EXAMPLE</span>
              </div>
              {visible.map((m, i) => (
                <button
                  key={m.id}
                  className={`mission-item ${selected.id === m.id ? "selected" : ""} ${m.status === "Blocked" ? "blocked" : ""}`}
                  onClick={() => setSelected(m)}
                  aria-pressed={selected.id === m.id}
                >
                  <span className="mission-number">
                    0{missions.indexOf(m) + 1}
                    <span />
                  </span>
                  <div className="mission-item-copy">
                    <div className="mission-item-meta">
                      <span>{m.category.toUpperCase()}</span>
                      <Badge
                        tone={
                          m.status === "In progress"
                            ? "cyan"
                            : m.status === "Completed"
                              ? "violet"
                              : "neutral"
                        }
                      >
                        {m.status === "Blocked" && (
                          <Icon name="lock" size={10} />
                        )}{" "}
                        {m.status}
                      </Badge>
                    </div>
                    <h2>{m.title}</h2>
                    <p>{m.subtitle}</p>
                    <div className="mission-item-bottom">
                      <span>
                        <i
                          className={`difficulty difficulty-${m.difficulty.toLowerCase()}`}
                        >
                          <b />
                          <b />
                          <b />
                        </i>
                        {m.difficulty}
                      </span>
                      <span>
                        <Icon name="clock" size={12} />
                        {m.duration}
                      </span>
                      <span>{m.id}</span>
                    </div>
                  </div>
                  <Icon name="chevron" size={16} />
                </button>
              ))}
              <div className="mission-list-note">
                <Icon name="shield" size={18} />
                <p>
                  A focused session is enough.
                  <br />
                  <span>Pick one idea. Give it your attention.</span>
                </p>
              </div>
            </section>
            <aside className="mission-brief" aria-label="Selected mission">
              <div className="brief-top">
                <span className="eyebrow">MISSION BRIEFING</span>
                <button
                  className={`icon-button ${saved.includes(selected.id) ? "is-saved" : ""}`}
                  aria-label={
                    saved.includes(selected.id)
                      ? "Remove mission bookmark"
                      : "Bookmark mission"
                  }
                  aria-pressed={saved.includes(selected.id)}
                  onClick={() =>
                    setSaved(
                      saved.includes(selected.id)
                        ? saved.filter((id) => id !== selected.id)
                        : [...saved, selected.id],
                    )
                  }
                >
                  <Icon name="bookmark" size={19} />
                </button>
              </div>
              <div className="brief-art">
                <MissionArt />
                <span>
                  {selected.id}
                  <small>EXAMPLE MISSION</small>
                </span>
              </div>
              <Badge tone={selected.status === "Blocked" ? "neutral" : "cyan"}>
                {selected.difficulty.toUpperCase()} /{" "}
                {selected.skill.toUpperCase()}
              </Badge>
              <h2>{selected.title}</h2>
              <p>{selected.subtitle}</p>
              <div className="brief-primary">
                <Button
                  onClick={() => onMission(selected)}
                  secondary={selected.status === "Blocked"}
                  icon={selected.status === "Blocked" ? "lock" : "arrow"}
                >
                  {selected.status === "Blocked"
                    ? "Inspect requirements"
                    : selected.status === "Completed"
                      ? "Review example"
                      : "Open mission preview"}
                </Button>
                <small className="brief-disclaimer">
                  No XP, reward or mastery outcome is promised.
                </small>
              </div>
              <div className="brief-objectives">
                <p className="eyebrow">YOUR OBJECTIVES</p>
                {selected.objectives.map((o, i) => (
                  <div key={o}>
                    <span>0{i + 1}</span>
                    <p>{o}</p>
                  </div>
                ))}
              </div>
              <div className="brief-prerequisite">
                <Icon
                  name={selected.status === "Blocked" ? "lock" : "tree"}
                  size={17}
                />
                <div>
                  <span>PREREQUISITE</span>
                  <p>{selected.prerequisite}</p>
                </div>
              </div>
            </aside>
          </div>
        )}
      </div>
    </>
  );
}
