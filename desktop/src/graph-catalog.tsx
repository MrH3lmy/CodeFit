import { useEffect, useRef, useState } from "react";
import { Badge, Button, Dialog } from "./components";
import {
  GraphResponse,
  type GraphAction,
  type GraphReply,
} from "./graph-contract";
/** Catalog inspection is explicit; publication requires a separate deliberate confirmation. */
export function GraphCatalog() {
  const [identity, setIdentity] = useState<{
      profileId: string;
      sessionId: string;
    }>(),
    [reply, setReply] = useState<GraphReply>(),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [confirm, setConfirm] = useState(false);
  const fence = useRef(0),
    running = useRef(false);
  useEffect(
    () => () => {
      fence.current++;
      void window.codefit.disconnectPractice();
    },
    [],
  );
  async function disconnect() {
    fence.current++;
    setIdentity(undefined);
    setReply(undefined);
    setConfirm(false);
    setError("");
    setBusy(false);
    await window.codefit.disconnectPractice();
  }
  async function connect() {
    if (running.current) return;
    running.current = true;
    const token = ++fence.current;
    setBusy(true);
    setError("");
    try {
      const r = await window.codefit.connectPractice();
      if (token !== fence.current) return;
      if (!r.ok) throw Error();
      setIdentity(r);
    } catch {
      if (token === fence.current)
        setError("Disposable practice profile unavailable.");
    } finally {
      running.current = false;
      if (token === fence.current) setBusy(false);
    }
  }
  async function act(action: GraphAction) {
    if (!identity || running.current) return;
    running.current = true;
    const token = ++fence.current;
    setBusy(true);
    setError("");
    setConfirm(false);
    try {
      const r = GraphResponse.parse(
        await window.codefit.practiceGraphAction(
          action,
          identity.profileId,
          identity.sessionId,
        ),
      );
      if (token !== fence.current) return;
      if (
        r.profileId !== identity.profileId ||
        r.sessionId !== identity.sessionId
      )
        throw Error();
      setReply((old) => ({ ...r, view: r.view ?? old?.view ?? null }));
    } catch {
      if (token === fence.current) {
        setReply(undefined);
        setError(
          "Response unavailable. Read the catalog to recover the original pending command before choosing again.",
        );
      }
    } finally {
      running.current = false;
      if (token === fence.current) setBusy(false);
    }
  }
  const view = reply?.view,
    selected = view?.graphs.find(
      (g) =>
        g.graphId === view.inspected.graphId &&
        g.graphVersion === view.inspected.graphVersion,
    );
  return (
    <section className="analytics-page" aria-label="Engineering graph catalog">
      <header className="panel practice-panel">
        <span className="eyebrow">CATALOG / EXACT VERSION SELECTION</span>
        <h2>Engineering graphs</h2>
        <p>
          Explore published capabilities. Selecting a graph changes planning for
          this disposable practice track. Catalog publication, curriculum review
          and demonstrated ability are separate.
        </p>
        <div className="draft-actions">
          {!identity ? (
            <Button disabled={busy} onClick={() => void connect()}>
              Connect disposable practice profile
            </Button>
          ) : (
            <>
              <Button secondary onClick={() => void disconnect()}>
                Disconnect graph session
              </Button>
              <Button
                disabled={busy}
                onClick={() =>
                  void act({ kind: "read", graphId: "", graphVersion: 0 })
                }
              >
                Read graph catalog / recover
              </Button>
            </>
          )}
        </div>
        {busy && <p role="status">Waiting for Java acknowledgement…</p>}
        {error && <p role="alert">{error}</p>}
      </header>
      {reply?.pending && (
        <section
          className="panel practice-panel"
          aria-label="Graph selection recovery"
        >
          <h3>Unacknowledged selection</h3>
          <p className="practice-reference">
            {reply.pending.commandId} · {reply.pending.graphId} v
            {reply.pending.graphVersion}
          </p>
          <p>
            Check the original command. Retry uses its original identity and
            expected revision.
          </p>
          <Button disabled={busy} onClick={() => void act({ kind: "status" })}>
            Check selection status
          </Button>
          <Button
            secondary
            disabled={busy}
            onClick={() => void act({ kind: "retry" })}
          >
            Retry original selection
          </Button>
        </section>
      )}
      {reply?.receipt && (
        <p role="status" className="panel practice-panel">
          Saved receipt: {reply.receipt.state} · {reply.receipt.reason} ·
          revision {reply.receipt.revision} · {reply.receipt.recordedAt}. Read
          the catalog for the current selection.
        </p>
      )}
      {view && (
        <>
          <section className="panel practice-panel">
            <h3>Selected planning graph</h3>
            <p className="practice-reference">
              {view.selected.graphId} v{view.selected.graphVersion} · selection
              revision {view.revision}
            </p>
            <Badge>{view.selectionStatus}</Badge>
            <p>
              Captured {view.capturedAt}. Ascension and Analytics retain their
              exact historical pilot references. Unsupported selections do not
              enable estimation or practice.
            </p>
          </section>
          <div className="debrief-grid">
            {view.graphs.map((g) => (
              <article
                className="panel practice-panel"
                key={`${g.graphId}:${g.graphVersion}`}
              >
                <span className="eyebrow">{g.domain}</span>
                <h3>{g.graphId}</h3>
                <p>
                  Graph version {g.graphVersion} · {g.skillCount} definitions
                </p>
                <Badge>{g.lifecycle}</Badge>
                <p>
                  Mappings: {g.mappingStatus} ({g.mappingCount})
                </p>
                <p>Estimator: {g.estimator}</p>
                <p>Independent content review: {g.contentReview}</p>
                <Button
                  secondary
                  disabled={busy}
                  onClick={() =>
                    void act({
                      kind: "read",
                      graphId: g.graphId,
                      graphVersion: g.graphVersion,
                    })
                  }
                >
                  Inspect {g.graphId} v{g.graphVersion}
                </Button>
              </article>
            ))}
          </div>
          <section
            className="panel practice-panel"
            aria-label="Graph definitions"
          >
            <h3>
              {view.inspected.graphId} v{view.inspected.graphVersion}
            </h3>
            {!view.detail ? (
              <p>Exact graph unavailable. No fallback version was selected.</p>
            ) : (
              <>
                {selected && (
                  <>
                    <p>
                      Practice: {selected.practice} · selection:{" "}
                      {selected.selectionStatus}
                    </p>
                    {selected.replacement && (
                      <p>
                        Declared replacement: {selected.replacement.graphId} v
                        {selected.replacement.graphVersion}. No automatic
                        switch.
                      </p>
                    )}
                    <Button
                      disabled={
                        busy ||
                        !!reply.pending ||
                        selected.selectionStatus !== "ELIGIBLE"
                      }
                      onClick={() => setConfirm(true)}
                    >
                      Choose this exact graph
                    </Button>
                  </>
                )}
                <div className="debrief-grid">
                  {view.detail.skills.map((s) => (
                    <article className="debrief-card" key={s.id}>
                      <h4>{s.name}</h4>
                      <p className="practice-reference">
                        {s.id} v{s.version} · {s.lifecycle}
                      </p>
                      <p>{s.description}</p>
                      <ul>
                        {s.behaviors.map((b) => (
                          <li key={b}>{b}</li>
                        ))}
                      </ul>
                      <p>
                        Prerequisites:{" "}
                        {view
                          .detail!.edges.filter((e) => e.to === s.id)
                          .map((e) => `${e.from} v${e.fromVersion}`)
                          .join(", ") || "None declared"}
                      </p>
                    </article>
                  ))}
                </div>
              </>
            )}
          </section>
        </>
      )}
      {confirm && view && (
        <Dialog
          title="Change planning graph?"
          eyebrow="EXPLICIT JAVA SELECTION"
          onClose={() => setConfirm(false)}
        >
          <p>
            Choose {view.inspected.graphId} v{view.inspected.graphVersion} at
            expected selection revision {view.revision}.
          </p>
          <p>
            Existing evidence, estimates and evaluations keep their original
            references. Selection does not approve content, award mastery,
            publish recommendations or execute challenges. Unsupported graphs
            remain inspection-only.
          </p>
          <Button
            disabled={busy}
            onClick={() =>
              void act({
                kind: "select",
                ...view.inspected,
                selectionRevision: view.revision,
              })
            }
          >
            Confirm exact graph selection
          </Button>
        </Dialog>
      )}
    </section>
  );
}
