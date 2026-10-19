import { useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { ResultSchema, type Catalog, type ErrorCode } from "./contract";
import "./style.css";

const errors: Record<ErrorCode, string> = {
  START_FAILED:
    "The bundled Java engine could not start. Check that the complete app artifact is present, then retry.",
  UNAVAILABLE: "The engine stopped. Retry to start a new isolated session.",
  READ_FAILED:
    "Java could not read the sample library. Retry to start a new isolated session.",
  PROTOCOL_ERROR:
    "The engine returned an invalid response. The connection was closed. Retry to start a fresh session.",
  TIMEOUT:
    "The engine did not respond in time. Its session has been stopped. You can retry.",
  CANCELLED:
    "Read cancelled. You can start a fresh isolated session whenever you are ready.",
  BUSY: "A library read is already running. Wait for it to finish or cancel it.",
};

function App() {
  const [theme, setTheme] = useState<"dark" | "light">("dark");
  const [catalog, setCatalog] = useState<Catalog>();
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<ErrorCode>();
  const generation = useRef(0);
  const refresh = async () => {
    const current = ++generation.current;
    setBusy(true);
    setError(undefined);
    setCatalog(undefined);
    try {
      const response = ResultSchema.parse(await window.codefit.listDecks());
      if (current !== generation.current) return;
      if (response.ok) setCatalog(response.result);
      else setError(response.code);
    } catch {
      if (current === generation.current) setError("UNAVAILABLE");
    } finally {
      if (current === generation.current) setBusy(false);
    }
  };
  const cancel = async () => {
    ++generation.current;
    await window.codefit.cancelRead();
    setBusy(false);
    setError("CANCELLED");
    setCatalog(undefined);
  };
  useEffect(() => {
    void refresh();
  }, []);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  return (
    <div className="shell">
      <aside className="rail" aria-label="Preview context">
        <div className="brand">
          <span className="sigil" aria-hidden="true">
            C<span>F</span>
          </span>
          <div>
            CODEFIT<small>THE SYSTEM</small>
          </div>
        </div>
        <div className="rail-label">ARCHITECTURE PREVIEW</div>
        <div className="rail-active">
          <span aria-hidden="true">◇</span> Engine connection
        </div>
        <div className="rail-note">
          One real workflow.
          <br />A foundation for what comes next.
        </div>
        <div className="rail-bottom">
          <span className="gate">GATE 0</span>
          <p>Experimental · read only</p>
          <p className="muted">
            Your learner profile is not opened by this preview.
          </p>
        </div>
      </aside>
      <main>
        <header className="topbar">
          <span>
            CODEFIT <span className="slash">/</span> ENGINE CONNECTION
          </span>
          <button
            className="theme-button"
            onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
            aria-label={`Switch to ${theme === "dark" ? "light" : "dark"} theme`}
          >
            {theme === "dark" ? "☼" : "☾"}{" "}
            <span>{theme === "dark" ? "Light" : "Dark"}</span>
          </button>
        </header>
        <section className="hero" aria-labelledby="title">
          <div className="hero-copy">
            <p className="eyebrow">THE NEXT CHAPTER / TECHNICAL PREVIEW</p>
            <h1 id="title">
              Your engine.
              <br />
              <span>A new interface.</span>
            </h1>
            <p className="intro">
              The existing Java learning engine, connected to a modern desktop.
              Explore the real sample library through a protected, read-only
              connection.
            </p>
            <div className="hero-tags">
              <span>JAVA 21</span>
              <span>LOCAL & OFFLINE</span>
              <span>ISOLATED SAMPLE</span>
            </div>
          </div>
          <div className="engine-art" aria-hidden="true">
            <svg viewBox="0 0 300 300">
              <defs>
                <linearGradient id="line">
                  <stop stopColor="#4edfe6" />
                  <stop offset="1" stopColor="#a08bff" />
                </linearGradient>
              </defs>
              <circle cx="150" cy="150" r="124" />
              <circle cx="150" cy="150" r="101" />
              <path d="M150 24L259 87V213L150 276L41 213V87Z" />
              <path
                className="core"
                d="M150 75L215 112V188L150 225L85 188V112Z"
              />
              <path
                className="glyph"
                d="M170 121H137L119 150L137 179H170M143 150H185"
              />
              <circle className="pin" cx="150" cy="26" r="5" />
              <circle className="pin" cx="258" cy="212" r="5" />
              <circle className="pin" cx="42" cy="212" r="5" />
            </svg>
            <span>ENGINE / PRESERVED</span>
          </div>
        </section>
        <section
          className="library"
          aria-labelledby="library-title"
          aria-busy={busy}
        >
          <div className="section-heading">
            <div>
              <p className="eyebrow">LIVE JAVA SERVICE</p>
              <h2 id="library-title">Sample training library</h2>
            </div>
            <div className="actions">
              {busy ? (
                <button onClick={() => void cancel()}>Cancel read</button>
              ) : (
                <button className="primary" onClick={() => void refresh()}>
                  ↻ Refresh from Java
                </button>
              )}
            </div>
          </div>
          <p className="source-note">
            Existing CodeFit starter content in a disposable SQLite database.
            These are deck descriptions, not learner progress or challenge
            answers.
          </p>
          <div className="status" role="status" aria-live="polite">
            {busy
              ? "Connecting to the local Java engine…"
              : error
                ? errors[error]
                : `${catalog?.decks.length ?? 0} deck descriptions received from Java · Read only`}
          </div>
          {error && (
            <div className="notice">
              <h3>
                {error === "CANCELLED"
                  ? "Session cancelled"
                  : "Connection needs attention"}
              </h3>
              <p>{errors[error]}</p>
            </div>
          )}
          {catalog?.decks.length === 0 && (
            <div className="notice">
              <h3>No decks in this sample</h3>
              <p>The engine returned an empty library.</p>
            </div>
          )}
          <div className="deck-grid">
            {catalog?.decks.map((deck, index) => (
              <article className="deck" key={deck.id}>
                <div className="deck-top">
                  <span className="deck-number">
                    {String(index + 1).padStart(2, "0")}
                  </span>
                  <span className="deck-label">SAMPLE DECK</span>
                </div>
                <h3>{deck.name}</h3>
                <p>{deck.description}</p>
                <div className="deck-footer">
                  <span aria-hidden="true">◇</span> Java-owned catalog
                </div>
              </article>
            ))}
          </div>
        </section>
        <footer>
          <span>Gate 0 proves the connection.</span> Command Center, Ascension
          Tree and Mission Console await a separate design approval.
        </footer>
      </main>
    </div>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
