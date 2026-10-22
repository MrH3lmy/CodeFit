import { useEffect, useRef, useState } from "react";
import {
  AckResultSchema,
  OperationResultSchema,
  ResultSchema,
  type ErrorCode,
  type Operation,
  type State,
} from "./contract";
const errors: Record<ErrorCode, string> = {
  START_FAILED:
    "The bundled Java engine could not start. Check the complete app artifact, then retry.",
  UNAVAILABLE: "The engine stopped. Retry to reopen the experimental profile.",
  READ_FAILED:
    "Java could not read the experimental profile. Your pending command remains available for recovery.",
  COMMAND_FAILED:
    "Java could not confirm this command. Check its saved receipt before trying again.",
  PROTOCOL_ERROR:
    "The engine returned an invalid response. Its connection was closed. Check the saved operation after reconnecting.",
  TIMEOUT:
    "The engine did not respond in time. Its connection was stopped; a write may have committed.",
  CANCELLED:
    "Connection cancelled. A write may have committed; check its saved receipt.",
  BUSY: "Another request is running. Wait for it to finish or cancel the connection.",
  INVALID_INPUT:
    "Enter a nonblank deck name of up to 256 characters and a description of up to 2,048 characters, without control characters.",
  PROFILE_BUSY:
    "Another CodeFit process owns this experimental profile. Close it before reconnecting.",
  PROFILE_INVALID:
    "This experimental profile could not be opened safely. Keep its files for review; they have not been replaced.",
  PROFILE_MISMATCH:
    "The saved command belongs to a different experimental profile. Recovery is blocked to protect both profiles.",
  COMMAND_ID_CONFLICT:
    "The saved command does not match its Java receipt. Recovery is blocked for review.",
  JOURNAL_INVALID:
    "The saved operation could not be read or written safely. Keep its files for review.",
  RECOVERY_REQUIRED:
    "Resolve and acknowledge the saved creation before starting another one.",
};

export function Library() {
  const [state, setState] = useState<State>();
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<ErrorCode>();
  const [operation, setOperation] = useState<Operation | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const generation = useRef(0);
  const applyOperation = (response: unknown) => {
    const parsed = OperationResultSchema.parse(response);
    if (parsed.ok) setOperation(parsed.operation);
    else setError(parsed.code);
  };
  const refresh = async (afterId = 0) => {
    const current = ++generation.current;
    setBusy(true);
    setError(undefined);
    try {
      const response = ResultSchema.parse(
        await window.codefit.readState(afterId),
      );
      if (current !== generation.current) return;
      if (response.ok && response.result.kind === "state")
        setState(response.result);
      else {
        setState(undefined);
        setError(response.ok ? "PROTOCOL_ERROR" : response.code);
      }
      const pending = await window.codefit.pendingDeck();
      if (current === generation.current) applyOperation(pending);
    } catch {
      if (current === generation.current) setError("UNAVAILABLE");
    } finally {
      if (current === generation.current) setBusy(false);
    }
  };
  const mutate = async (recover: boolean) => {
    const current = ++generation.current;
    setBusy(true);
    setError(undefined);
    try {
      const result = recover
        ? await window.codefit.recoverDeck()
        : await window.codefit.createDeck({ name, description });
      if (current === generation.current) applyOperation(result);
    } catch {
      // A lost renderer invocation is ambiguous. Read the durable main-owned intent on reconnect.
      if (current === generation.current) setError("UNAVAILABLE");
    } finally {
      if (current === generation.current) setBusy(false);
    }
  };
  const acknowledge = async () => {
    if (!operation) return;
    const current = ++generation.current;
    setBusy(true);
    setError(undefined);
    try {
      const result = AckResultSchema.parse(
        await window.codefit.acknowledgeDeck(operation.commandId),
      );
      if (current !== generation.current) return;
      if (result.ok) {
        setOperation(null);
        setName("");
        setDescription("");
        await refresh();
      } else setError(result.code);
    } catch {
      if (current === generation.current) setError("UNAVAILABLE");
    } finally {
      if (current === generation.current) setBusy(false);
    }
  };
  const cancel = async () => {
    const current = ++generation.current;
    setBusy(true);
    try {
      await window.codefit.cancelOperation();
      if (current !== generation.current) return;
      setError("CANCELLED");
      const pending = await window.codefit.pendingDeck();
      if (current === generation.current) applyOperation(pending);
    } catch {
      if (current === generation.current) setError("UNAVAILABLE");
    } finally {
      if (current === generation.current) setBusy(false);
    }
  };
  useEffect(() => {
    void refresh();
    return () => {
      ++generation.current;
    };
  }, []);
  const receipt = operation?.receipt;
  return (
    <section
      className="library"
      aria-labelledby="library-title"
      aria-busy={busy}
    >
      <div className="section-heading">
        <div>
          <p className="eyebrow">LIVE JAVA SERVICE</p>
          <h2 id="library-title">Experimental training library</h2>
        </div>
        <div className="actions">
          {busy ? (
            <button onClick={() => void cancel()}>Cancel connection</button>
          ) : (
            <button className="primary" onClick={() => void refresh()}>
              ↻ Refresh from Java
            </button>
          )}
        </div>
      </div>
      <p className="source-note">
        A separate, durable experimental profile with CodeFit starter decks. New
        decks are saved locally by Java and survive restart. Your learner
        profile is not connected. Progression is not supported in this slice.
      </p>
      <div className="status" role="status" aria-live="polite">
        {busy
          ? "Working with the local Java engine…"
          : error
            ? errors[error]
            : `${state?.decks.length ?? 0} deck descriptions received from Java · Read-only projection`}
      </div>
      {error && (
        <div className="notice">
          <h3>Connection needs attention</h3>
          <p>{errors[error]}</p>
        </div>
      )}
      <section className="library-create" aria-labelledby="create-title">
        <div>
          <p className="eyebrow">EXPERIMENTAL PROFILE ONLY</p>
          <h3 id="create-title">Create a training deck</h3>
        </div>
        {operation ? (
          <div className="operation-receipt" role="status">
            <h4>
              {receipt?.status === "created"
                ? "Deck created"
                : receipt?.status === "rejected"
                  ? "Name already exists"
                  : "Creation needs reconciliation"}
            </h4>
            <p>
              {receipt?.status === "created"
                ? `Java confirmed “${receipt.deck.name}” (deck ${receipt.deck.id}).`
                : receipt?.status === "rejected"
                  ? "Java rejected this name. No additional deck was created."
                  : "Completion is unknown. Check and finish the saved command using its original identity."}
            </p>
            <p className="source-note">
              Saved command: <code>{operation.commandId}</code>
            </p>
            {operation.error && <p>{errors[operation.error]}</p>}
            {receipt ? (
              <button
                className="button primary"
                disabled={busy}
                onClick={() => void acknowledge()}
              >
                Acknowledge and continue
              </button>
            ) : (
              <button
                className="button primary"
                disabled={busy}
                onClick={() => void mutate(true)}
              >
                Check and finish saved creation
              </button>
            )}
          </div>
        ) : (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void mutate(false);
            }}
          >
            <label htmlFor="deck-name">Deck name</label>
            <input
              id="deck-name"
              name="deck-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={256}
              required
              disabled={busy}
              autoComplete="off"
            />
            <label htmlFor="deck-description">
              Description <span>(optional)</span>
            </label>
            <input
              id="deck-description"
              name="deck-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              maxLength={2048}
              disabled={busy}
              autoComplete="off"
            />
            <button
              className="button primary"
              type="submit"
              disabled={busy || !name.trim()}
            >
              Create deck in experimental profile
            </button>
          </form>
        )}
      </section>
      {state?.decks.length === 0 && (
        <div className="notice">
          <h3>No decks on this page</h3>
          <p>The experimental profile returned no deck descriptors.</p>
        </div>
      )}
      <div className="deck-grid">
        {state?.decks.map((deck) => (
          <article className="deck" key={deck.id}>
            <div className="deck-top">
              <span className="deck-number">
                {String(deck.id).padStart(2, "0")}
              </span>
              <span className="deck-label">LOCAL DECK</span>
            </div>
            <h3>{deck.name}</h3>
            <p>{deck.description}</p>
            <div className="deck-footer">
              <span aria-hidden="true">◇</span> Java-owned experimental profile
            </div>
          </article>
        ))}
      </div>
      {state?.nextAfterId && (
        <button
          className="library-next"
          disabled={busy}
          onClick={() => void refresh(state.nextAfterId!)}
        >
          Next page of decks
        </button>
      )}
    </section>
  );
}
