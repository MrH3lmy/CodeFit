import { useEffect, useRef, useState } from "react";
import { ResultSchema, type Catalog, type ErrorCode } from "./contract";
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

export function Library() {
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
  return (
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
        Existing CodeFit starter content in a disposable SQLite database. These
        are deck descriptions, not learner progress or challenge answers.
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
  );
}
