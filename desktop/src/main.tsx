import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Icon, Mark, type IconName } from "./icons";
import { MissionArt } from "./art";
import { Badge, Dialog, StateView } from "./components";
import { Library } from "./library";
import { missions, stateLabels, type Mission, type PreviewState } from "./data";
import { CommandCenter } from "./command-center";
import { Tree } from "./ascension-tree";
import { MissionConsole } from "./mission-console";
import { MissionDialog } from "./mission-dialog";
import "./style.css";

type Screen = "command" | "tree" | "missions" | "library";
type Modal = "states" | "profile" | "activity" | "boss" | null;
const nav: { id: Screen; label: string; sub: string; icon: IconName }[] = [
  {
    id: "command",
    label: "Command Center",
    sub: "Your next horizon",
    icon: "command",
  },
  {
    id: "tree",
    label: "Ascension Tree",
    sub: "Connect your capabilities",
    icon: "tree",
  },
  {
    id: "missions",
    label: "Mission Console",
    sub: "Turn intent into practice",
    icon: "mission",
  },
  {
    id: "library",
    label: "Java Library",
    sub: "Isolated engine connection",
    icon: "library",
  },
];
function App() {
  const [screen, setScreen] = useState<Screen>("command");
  const [libraryOpened, setLibraryOpened] = useState(false);
  const [theme, setTheme] = useState<"dark" | "light">("dark");
  const [state, setState] = useState<PreviewState>("ready");
  const [modal, setModal] = useState<Modal>(null);
  const [mission, setMission] = useState<Mission>();
  const [announcement, setAnnouncement] = useState("");
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);
  const navigate = (next: Screen) => {
    if (next === "library") setLibraryOpened(true);
    setScreen(next);
    setState("ready");
    setAnnouncement(`${nav.find((n) => n.id === next)?.label} opened`);
    requestAnimationFrame(() => {
      heading.current?.focus();
      window.scrollTo(0, 0);
    });
  };
  return (
    <div className="shell">
      <a
        className="skip-link"
        href="#main-content"
        onClick={(e) => {
          e.preventDefault();
          document.getElementById("main-content")?.focus();
        }}
      >
        Skip to content
      </a>
      <aside className="rail">
        <a
          href="#command"
          className="brand"
          onClick={(e) => {
            e.preventDefault();
            navigate("command");
          }}
          aria-label="CodeFit Command Center"
        >
          <Mark />
          <span>
            CodeFit<small>THE ASCENT BEGINS WITH YOU</small>
          </span>
        </a>
        <div className="rail-section">
          WORKSPACE <span>01</span>
        </div>
        <nav aria-label="Main navigation">
          {nav.map((n, i) => (
            <button
              key={n.id}
              onClick={() => navigate(n.id)}
              className={`nav-item ${screen === n.id ? "active" : ""}`}
              aria-current={screen === n.id ? "page" : undefined}
              title={n.label}
            >
              <Icon name={n.icon} />
              <span>
                {n.label}
                <small>{n.sub}</small>
              </span>
              <b>{String(i + 1).padStart(2, "0")}</b>
            </button>
          ))}
        </nav>
        <div className="rail-journey">
          <div className="journey-line" />
          <p className="eyebrow">THE LONG GAME</p>
          <p>
            Small insights.
            <br />
            Lasting capability.
          </p>
          <span>Explore. Practice. Evolve.</span>
        </div>
        <div className="rail-bottom">
          <button
            className="preview-settings"
            onClick={() => setModal("states")}
            title="Preview states"
          >
            <Icon name="settings" />
            <span>Preview states</span>
          </button>
          <button
            className="profile"
            onClick={() => setModal("profile")}
            title="Preview profile"
          >
            <span className="avatar">
              E<span />
            </span>
            <span>
              Explorer<small>PREVIEW IDENTITY</small>
            </span>
            <Icon name="chevron" size={14} />
          </button>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div className="breadcrumb">
            WORKSPACE <span>/</span>{" "}
            <strong>{nav.find((n) => n.id === screen)?.label}</strong>
          </div>
          <div className="top-actions">
            <span className="local-status">
              <i />
              LOCAL EXPERIENCE
            </span>
            <button
              className="icon-button theme-button"
              aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}
              onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
            >
              <Icon name={theme === "dark" ? "sun" : "moon"} />
            </button>
            <button
              className="top-avatar"
              onClick={() => setModal("profile")}
              aria-label="Open preview profile"
            >
              E
            </button>
          </div>
        </header>
        <div className="provenance">
          <span>
            <i />
            {screen === "library" ? "EXPERIMENTAL DATA" : "DESIGN PREVIEW"}
          </span>
          <p>
            {screen === "library"
              ? "Live experimental profile. Your learner profile is not connected."
              : "Demonstration data. Your learner profile is not connected."}
          </p>
          <button onClick={() => setModal("states")}>
            {state === "ready" ? "Explore states" : stateLabels[state]}{" "}
            <Icon name="chevron" size={12} />
          </button>
        </div>
        <main id="main-content" tabIndex={-1}>
          <div className="page-heading">
            <div>
              <p className="eyebrow">
                {screen === "command"
                  ? "YOUR PERSONAL OPERATING SYSTEM"
                  : screen === "tree"
                    ? "A MAP OF WHAT YOU CAN BECOME"
                    : screen === "missions"
                      ? "PURPOSEFUL PRACTICE. VISIBLE PROGRESS."
                      : "PRESERVED JAVA ENGINE / EXPERIMENTAL PROFILE"}
              </p>
              <h1 ref={heading} tabIndex={-1}>
                {nav.find((n) => n.id === screen)?.label}
                <span className="heading-period">.</span>
              </h1>
            </div>
            <span className="page-index">
              {screen === "command"
                ? "SYS / 01"
                : screen === "tree"
                  ? "MAP / 02"
                  : screen === "missions"
                    ? "OPS / 03"
                    : "ENG / 04"}
            </span>
          </div>
          {state === "offline" && (
            <div className="state-banner" role="status">
              <Icon name="wifi" />
              <span>
                <strong>Offline preview.</strong> Bundled examples remain
                available. No connection or sync is implied.
              </span>
              <button onClick={() => setState("ready")}>
                Reconnect preview
              </button>
            </div>
          )}
          {state === "unknown" && (
            <div className="state-banner" role="status">
              <Icon name="info" />
              <span>
                <strong>No observations yet.</strong> Unknown means unobserved,
                never zero ability.
              </span>
              <button onClick={() => setState("ready")}>Restore example</button>
            </div>
          )}
          {screen !== "library" &&
          (state === "empty" || state === "error" || state === "loading") ? (
            <StateView kind={state} onReset={() => setState("ready")} />
          ) : screen === "command" ? (
            <CommandCenter
              onMission={setMission}
              onTree={() => navigate("tree")}
              onMissions={() => navigate("missions")}
              onActivity={() => setModal("activity")}
              onBoss={() => setModal("boss")}
              unknown={state === "unknown"}
            />
          ) : screen === "tree" ? (
            <Tree
              onMission={() => setMission(missions[0])}
              unknown={state === "unknown"}
            />
          ) : screen === "missions" ? (
            <MissionConsole onMission={setMission} />
          ) : null}
          <div hidden={screen !== "library"}>
            {libraryOpened && <Library />}
          </div>
          <footer className="app-footer">
            <span>
              <Mark /> BUILT THROUGH PRACTICE.
            </span>
            <span>
              Gate 1 · Visual review <i /> Engine authority preserved
            </span>
          </footer>
        </main>
      </div>
      <div className="sr-only" role="status" aria-live="polite">
        {announcement}
      </div>
      {modal === "states" && (
        <Dialog
          title="Explore the full experience"
          onClose={() => setModal(null)}
        >
          <p className="dialog-intro">
            Inspect designed states without touching a learner profile. All
            controls below affect only this in-memory prototype.
          </p>
          <div className="state-options">
            {(Object.entries(stateLabels) as [PreviewState, string][]).map(
              ([value, label]) => (
                <button
                  className={state === value ? "selected" : ""}
                  key={value}
                  onClick={() => {
                    setState(value);
                    if (screen === "library") setScreen("missions");
                    setModal(null);
                    setAnnouncement(`${label} preview`);
                  }}
                >
                  <Icon
                    name={
                      value === "ready"
                        ? "command"
                        : value === "offline"
                          ? "wifi"
                          : value === "error"
                            ? "alert"
                            : value === "loading"
                              ? "clock"
                              : value === "unknown"
                                ? "info"
                                : "target"
                    }
                  />
                  <span>
                    {label}
                    <small>
                      {value === "ready"
                        ? "A complete example journey"
                        : value === "unknown"
                          ? "No evidence is not a failure"
                          : value === "empty"
                            ? "A clear next step"
                            : value === "offline"
                              ? "Local content remains usable"
                              : value === "loading"
                                ? "A held state; manually finish"
                                : "A recoverable interruption"}
                    </small>
                  </span>
                  <Icon name="arrow" size={16} />
                </button>
              ),
            )}
          </div>
          <p className="fine-print">
            Evidence states live in the skill inspector. Unsaved work,
            interrupted evaluation and unavailable runner states live in the
            mission workspace.
          </p>
        </Dialog>
      )}
      {modal === "profile" && (
        <Dialog
          title="Every expert starts somewhere."
          onClose={() => setModal(null)}
        >
          <div className="profile-summary">
            <span className="avatar large">E</span>
            <div>
              <h3>Explorer</h3>
              <p>Local demonstration identity</p>
            </div>
            <Badge tone="violet">PROTOTYPE</Badge>
          </div>
          <p className="dialog-intro">
            Your profile is deliberately disconnected while this visual
            experience is under review.
          </p>
          <div className="profile-metrics">
            {["Level", "XP balance", "Rank", "Earned rewards"].map((label) => (
              <div key={label}>
                <span>{label}</span>
                <strong>—</strong>
                <small>Not connected</small>
              </div>
            ))}
          </div>
          <p className="callout">
            <Icon name="shield" /> Progression rewards reflect participation.
            Skill understanding requires evidence; this preview never calculates
            either.
          </p>
        </Dialog>
      )}
      {modal === "activity" && (
        <Dialog
          title="A record of your reasoning"
          onClose={() => setModal(null)}
        >
          <Badge tone="violet">EXAMPLE ACTIVITY · NOT LEARNER HISTORY</Badge>
          <ol className="activity-list">
            <li>
              <Icon name="code" />
              <div>
                <strong>Boundary explained</strong>
                <p>Example reflection attached to “The edge of a loop”.</p>
                <small>Sample session · evaluation reference unavailable</small>
              </div>
            </li>
            <li>
              <Icon name="tree" />
              <div>
                <strong>A new direction explored</strong>
                <p>Example selection: debugging and hypothesis testing.</p>
                <small>Sample session · no mastery change claimed</small>
              </div>
            </li>
          </ol>
          <div className="achievement-specimen">
            <span className="achievement-emblem">
              <Icon name="shield" size={30} />
            </span>
            <div>
              <p className="eyebrow">ACHIEVEMENT DESIGN SPECIMEN</p>
              <h3>Boundary thinker</h3>
              <p>
                A proposed recognition for a clear explanation.{" "}
                <strong>Not earned.</strong> No eligibility or entitlement is
                evaluated.
              </p>
            </div>
          </div>
        </Dialog>
      )}
      {modal === "boss" && (
        <Dialog title="The integration trial" onClose={() => setModal(null)}>
          <Badge tone="violet">FUTURE CONCEPT · PROTOTYPE ONLY</Badge>
          <div className="boss-visual">
            <MissionArt />
          </div>
          <p className="dialog-intro">
            One system. Several connected decisions. A future encounter could
            combine debugging, API design and failure handling in one thoughtful
            challenge.
          </p>
          <div className="callout">
            <Icon name="lock" /> No encounter, entitlement, reward or completion
            exists in this preview. This is a visual concept for review.
          </div>
        </Dialog>
      )}
      {mission && (
        <MissionDialog
          mission={mission}
          onClose={() => setMission(undefined)}
          onTree={() => {
            setMission(undefined);
            navigate("tree");
          }}
        />
      )}
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
