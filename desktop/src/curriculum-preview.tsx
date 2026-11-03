import { useEffect, useRef, useState } from "react";
import { Badge } from "./components";
import { Icon } from "./icons";
import {
  CurriculumResultSchema,
  type CurriculumPreview,
  type CurriculumError,
  type Coverage,
} from "./curriculum-contract";

const errors: Record<CurriculumError, string> = {
  RELEASE_DISABLED:
    "This draft is available for local review only. Distribution audience authorization is still pending.",
  UNAVAILABLE:
    "The Java preview reader is unavailable. No substitute catalog has been loaded.",
  INVALID_CATALOG:
    "The bundled catalog could not be verified. Its contents remain unavailable.",
  CANCELLED: "The preview read was cancelled. No learner data was accessed.",
  TIMEOUT: "The preview reader did not finish in time. You can try again.",
  PROTOCOL_ERROR:
    "The preview response could not be verified. Its contents remain unavailable.",
  START_FAILED:
    "The bundled Java reader could not start. No learner profile is needed for this view.",
};
const label = (stage: string) => stage[0] + stage.slice(1).toLowerCase();
const coverageStatusLabel = (status: string) => {
  switch (status) {
    case "REPORTED_NOT_ENUMERATED": return "Reported, not enumerated";
    case "ZERO_REPORTED": return "Reported zero, not independently verified";
    case "REPORTED_FIRST_PASS_NOT_REVIEWED": return "First-pass notes, not approved";
    case "UNKNOWN": return "Unknown, not supplied";
    default: return "Unverified status";
  }
};
function CoverageBrowser({ coverage }: { coverage: Coverage }) {
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const [selected, setSelected] = useState(coverage.books[0].id);
  const details = useRef<HTMLHeadingElement>(null);
  const books = coverage.books.filter(
    (b) =>
      `${b.title} ${b.id}`.toLowerCase().includes(query.toLowerCase().trim()) &&
      (status === "all" ||
        (status === "first-pass" && b.authorDraft.count !== null) ||
        (status === "unknown-author" && b.authorDraft.status === "UNKNOWN")),
  );
  const book = books.find((b) => b.id === selected) ?? books[0];
  const claims = coverage.reportedClaims;
  return (
    <section className="atlas-coverage" aria-label="Source-attributed book coverage">
      <div className="atlas-section-heading">
        <h2>Book coverage ledger</h2>
        <span>{coverage.books.length} REPORTED BOOKS</span>
      </div>
      <p className="atlas-ledger-note">
        Historical claims · {coverage.provenance.source} · observed{" "}
        <time dateTime={coverage.provenance.observedAt}>{coverage.provenance.observedAt}</time>.
        {" "}Not independently enumerated, not learning completion.
      </p>
      <dl className="atlas-coverage-totals" aria-label="Historical reported totals">
        <div><dt>Inventory units</dt><dd>{claims.structuralUnits}</dd><small>Reported, not enumerated</small></div>
        <div><dt>Preliminary mapped</dt><dd>{claims.preliminaryMappedUnits}</dd><small>Historical mapping only</small></div>
        <div><dt>Reported unmapped</dt><dd>{claims.reportedUnmappedUnits}</dd><small>Reported inventory remainder</small></div>
        <div><dt>Mission blueprint slots</dt><dd>{claims.missionBlueprintSlots}</dd><small>Reported unique slots · not playable missions</small></div>
        <div><dt>Revision events</dt><dd>{claims.revisionEvents.count}</dd><small>Nonadditive · not extra slots</small></div>
      </dl>
      <div className="atlas-layout">
        <div>
          <fieldset className="atlas-book-filters">
            <legend>Filter reported books</legend>
            <label className="atlas-search">
              <Icon name="search" />
              <span className="sr-only">Find a reported book</span>
              <input type="search" value={query} maxLength={128}
                placeholder="Book title or identifier"
                onChange={(e) => setQuery(e.target.value)} />
            </label>
            <label className="atlas-status-filter">
              Author-draft status
              <select value={status} onChange={(e) => setStatus(e.target.value)}>
                <option value="all">All reported books</option>
                <option value="first-pass">Reported first-pass author notes</option>
                <option value="unknown-author">UNKNOWN author draft</option>
              </select>
            </label>
          </fieldset>
          <p className="atlas-ledger-note" role="status">{books.length} matching reported books</p>
          <div className="atlas-book-list" aria-label="Reported books">
            {books.map((b) => (
              <button className={`atlas-card atlas-book-card ${book?.id === b.id ? "selected" : ""}`}
                key={b.id} aria-pressed={book?.id === b.id} onClick={() => setSelected(b.id)}>
                <strong>{b.title}</strong>
                <code>{b.id}</code>
                <span className="atlas-book-inventory">{b.inventory.count} inventory units · {coverageStatusLabel(b.inventory.status)}</span>
                <span className="atlas-book-card-statuses">
                  <span>Preliminary mapping <strong>{coverageStatusLabel(b.preliminaryMapped.status)}</strong></span>
                  <span>Independent review <strong>{b.independentReview.count} · {coverageStatusLabel(b.independentReview.status)}</strong></span>
                  <span>Runtime imports <strong>{b.runtimeLearning.count} · {coverageStatusLabel(b.runtimeLearning.status)}</strong></span>
                  <span>Learner validation <strong>{coverageStatusLabel(coverage.learnerValidated.status)}</strong></span>
                </span>
                <span className="atlas-card-bottom">
                  <span>{b.authorDraft.status === "UNKNOWN" ? "Author draft unknown, not supplied" : `Author draft · ${b.authorDraft.count}/${b.authorDraft.denominator} first-pass author notes · not approvals`}</span>
                  <Icon name="arrow" size={18} />
                </span>
              </button>
            ))}
            {books.length === 0 && <p role="status">No matching reported books. <button onClick={() => { setQuery(""); setStatus("all"); }}>Clear book filters</button></p>}
          </div>
          <button className="atlas-inspect-link" disabled={!book} onClick={() => details.current?.focus()}>
            Go to selected book details <Icon name="arrow" size={14} />
          </button>
        </div>
        <section className="atlas-inspector atlas-book-inspector" aria-label="Selected book details">
          <p className="eyebrow">SOURCE-ATTRIBUTED / {coverage.provenance.sourceVersion}</p>
          {book ? (
          <>
          <h2 ref={details} tabIndex={-1}>{book.title}</h2>
          <code className="atlas-exact-id">{book.id}</code>
          <dl className="atlas-book-stages">
            <div><dt>Inventory</dt><dd>{book.inventory.count} · {coverageStatusLabel(book.inventory.status)}</dd></div>
            <div><dt>Preliminary mapping</dt><dd>{coverageStatusLabel(book.preliminaryMapped.status)} · per-book count not supplied</dd></div>
            <div><dt>Author draft</dt><dd>{book.authorDraft.count === null ? "Unknown, not supplied" : `${book.authorDraft.count}/${book.authorDraft.denominator} first-pass author notes · ${coverageStatusLabel(book.authorDraft.status)}`}</dd></div>
            <div><dt>Independent review</dt><dd>{book.independentReview.count} · {coverageStatusLabel(book.independentReview.status)} · no reported approvals</dd></div>
            <div><dt>Runtime imported</dt><dd>{book.runtimeLearning.count} · {coverageStatusLabel(book.runtimeLearning.status)} · no reported imports</dd></div>
            <div><dt>Learner validation</dt><dd>{coverageStatusLabel(coverage.learnerValidated.status)}</dd></div>
          </dl>
          <h3>Follow-up gaps</h3>
          <p className="atlas-unknown">Source-unit IDs and per-book mappings need enumeration. First-pass author notes are not independent review, runtime import or learner validation.</p>
          <ul className="atlas-followup">{coverage.followupGaps.map((gap) => <li key={gap}><code>{gap}</code></li>)}</ul>
          <details className="atlas-source">
            <summary>Book coverage provenance</summary>
            <p>{coverage.provenance.source}</p>
            <p>{coverage.provenance.verification}</p>
            <code>{coverage.sourcePath}</code>
            <code aria-label="Public coverage ledger checksum">{coverage.ledgerSha256}</code>
            <ul>{coverage.provenance.limitations.map((limitation) => <li key={limitation}>{limitation}</li>)}</ul>
            <p>Private source bodies: {coverage.provenance.privateSourceBodies}. Previous checkpoint: {coverage.provenance.previousCheckpoint}.</p>
          </details>
          </>
          ) : (
            <p className="atlas-unknown" role="status">
              No book matches the current filters. Clear the filters to inspect source-attributed details.
            </p>
          )}
        </section>
      </div>
    </section>
  );
}
export function CurriculumAtlas() {
  const [preview, setPreview] = useState<CurriculumPreview>();
  const [coverage, setCoverage] = useState<Coverage>();
  const [error, setError] = useState<CurriculumError>();
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState("");
  const [stageId, setStageId] = useState("");
  const [query, setQuery] = useState("");
  const generation = useRef(0);
  const inspector = useRef<HTMLHeadingElement>(null);
  async function read() {
    const current = ++generation.current;
    setBusy(true);
    setError(undefined);
    setPreview(undefined);
    setCoverage(undefined);
    setSelected("");
    setStageId("");
    try {
      const result = CurriculumResultSchema.parse(
        await window.codefit.readCurriculumPreview(),
      );
      if (generation.current !== current) return;
      if (!result.ok) setError(result.code);
      else {
        setPreview(result.result.preview);
        setCoverage(result.result.coverage);
        setSelected(result.result.preview.skills[0].id);
        setStageId(result.result.preview.skills[0].stages[0].id);
      }
    } catch {
      if (generation.current === current) setError("PROTOCOL_ERROR");
    } finally {
      if (generation.current === current) setBusy(false);
    }
  }
  useEffect(() => {
    void read();
    return () => {
      ++generation.current;
      void window.codefit.cancelCurriculumPreview();
    };
  }, []);
  async function cancel() {
    ++generation.current;
    setBusy(false);
    setPreview(undefined);
    setCoverage(undefined);
    setSelected("");
    setStageId("");
    setError("CANCELLED");
    await window.codefit.cancelCurriculumPreview();
  }
  function select(id: string) {
    const skill = preview?.skills.find((s) => s.id === id);
    if (!skill) return;
    setSelected(id);
    setStageId(skill.stages[0].id);
  }
  const skill = preview?.skills.find((s) => s.id === selected);
  const stage = skill?.stages.find((s) => s.id === stageId);
  const filtered = preview?.skills.filter((s) =>
    `${s.title} ${s.id} ${s.sourceItem} ${s.prerequisites.join(" ")} ${s.stages.map((t) => t.id).join(" ")}`
      .toLowerCase()
      .includes(query.toLowerCase()),
  );
  return (
    <section className="atlas" aria-label="Author-draft curriculum preview">
      <div className="atlas-hero">
        <div className="atlas-hero-copy">
          <p className="eyebrow">THE KNOWLEDGE ATLAS / AUTHOR DRAFTS</p>
          <h2>
            Explore the ideas.
            <br />
            <em>Before the journey.</em>
          </h2>
          <p>
            A window into the curriculum taking shape. Inspect its structure and
            open questions, separate from your learning record.
          </p>
          <div className="atlas-badges">
            <Badge tone="violet">
              {preview ? preview.reviewStatus : "PREVIEW ONLY"}
            </Badge>
            <span>Java-owned · Offline · Read-only</span>
          </div>
        </div>
        <svg
          className="atlas-illustration"
          viewBox="0 0 260 210"
          aria-hidden="true"
        >
          <defs>
            <linearGradient id="atlas-page" x2="1" y2="1">
              <stop stopColor="var(--cyan)" stopOpacity=".25" />
              <stop offset="1" stopColor="var(--violet)" stopOpacity=".04" />
            </linearGradient>
          </defs>
          <ellipse
            cx="130"
            cy="163"
            rx="110"
            ry="31"
            fill="none"
            stroke="var(--line)"
          />
          <path
            d="M26 90 130 136 235 90M26 108 130 154 235 108M26 126 130 172 235 126"
            fill="none"
            stroke="var(--violet)"
            opacity=".45"
          />
          <path
            d="M26 66 130 111 235 66 130 21Z"
            fill="url(#atlas-page)"
            stroke="var(--cyan)"
          />
          <path
            d="M130 111V172M130 21V111M26 66V126M235 66V126"
            fill="none"
            stroke="var(--cyan)"
            opacity=".4"
          />
          <path
            d="m63 66 45 20m-27-29 27 12m47 17 43-20m-43 3 25-12"
            stroke="var(--cyan)"
            strokeWidth="2"
          />
          <circle cx="130" cy="21" r="4" fill="var(--cyan)" />
          <circle cx="235" cy="66" r="3" fill="var(--violet)" />
        </svg>
      </div>
      {!preview ? (
        <div className="atlas-unavailable" role={error ? "alert" : "status"}>
          <Icon name={busy ? "clock" : "info"} size={28} />
          <h3>
            {busy
              ? "Opening the pinned Java catalog"
              : error === "RELEASE_DISABLED"
                ? "Local review access only"
                : "Preview unavailable"}
          </h3>
          <p>
            {busy
              ? "Reading bundled metadata. No learner profile or database is required."
              : error
                ? errors[error]
                : "The preview has not been loaded."}
          </p>
          {busy ? (
            <button className="secondary-button" onClick={() => void cancel()}>
              Cancel preview read
            </button>
          ) : (
            <button className="secondary-button" onClick={() => void read()}>
              Retry preview read
            </button>
          )}
        </div>
      ) : (
        <>
          <div className="atlas-summary" aria-label="Preview scope">
            <div>
              <strong>
                {preview.skills.length.toString().padStart(2, "0")}
              </strong>
              <span>Draft skill definitions</span>
            </div>
            <div>
              <strong>
                {preview.skills.reduce((n, s) => n + s.stages.length, 0)}
              </strong>
              <span>Stage identifiers · not missions</span>
            </div>
            <div>
              <strong>
                {preview.independentApprovals.toString().padStart(2, "0")}
              </strong>
              <span>Independent approvals</span>
            </div>
            <div className="atlas-revision">
              <Badge tone="violet">{preview.revision}</Badge>
              <span>Pinned author revision</span>
            </div>
          </div>
          <div className="atlas-disclaimer">
            <Icon name="info" />
            <p>
              <strong>Structure, not earned progression.</strong> These drafts
              are not part of your Ascension Tree. No mission execution,
              grading, XP, rewards, mastery or production import is available.
            </p>
          </div>
          {coverage && <CoverageBrowser coverage={coverage} />}
          <div className="atlas-layout">
            <div className="atlas-catalog">
              <div className="atlas-section-heading">
                <h2>Draft collection</h2>
                <span>{preview.skills.length} DEFINITIONS</span>
              </div>
              <label className="atlas-search">
                <Icon name="search" />
                <span className="sr-only">Find a draft skill</span>
                <input
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="Title, ID, source item, prerequisite or stage ID"
                  maxLength={128}
                />
              </label>
              <div className="atlas-cards" aria-label="Draft skill definitions">
                {filtered?.map((s) => (
                  <button
                    key={s.id}
                    className={`atlas-card ${selected === s.id ? "selected" : ""}`}
                    aria-pressed={selected === s.id}
                    onClick={() => select(s.id)}
                  >
                    <span className="atlas-card-top">
                      <span>{s.sourceItem}</span>
                      <span>AUTHOR DRAFT</span>
                    </span>
                    <strong>{s.title}</strong>
                    <code>{s.id}</code>
                    <span className="atlas-card-bottom">
                      <span>
                        {s.stages.map((t) => label(t.stage)).join(" · ")}
                      </span>
                      <Icon name="arrow" size={18} />
                    </span>
                  </button>
                ))}
                {filtered?.length === 0 && (
                  <p role="status">
                    No matching draft definitions.{" "}
                    <button onClick={() => setQuery("")}>Clear search</button>
                  </p>
                )}
              </div>
              <button
                className="atlas-inspect-link"
                onClick={() => inspector.current?.focus()}
              >
                Go to selected draft details <Icon name="arrow" size={14} />
              </button>
            </div>
            {skill && (
              <section
                className="atlas-inspector"
                aria-label="Selected draft details"
              >
                <div className="atlas-inspector-top">
                  <p className="eyebrow">DEFINITION / {skill.sourceItem}</p>
                  <Badge tone="violet">UNREVIEWED</Badge>
                </div>
                <h2 ref={inspector} tabIndex={-1}>
                  {skill.title}
                </h2>
                <code className="atlas-exact-id">{skill.id}</code>
                <h3>Skill objectives</h3>
                <p className="atlas-unknown">UNKNOWN · not supplied in author metadata.</p>
                <div className="atlas-mapping">
                  <Icon name="tree" />
                  <div>
                    <strong>Exact skill mapping unavailable</strong>
                    <p>
                      No approved CodeFit SkillRef or graph version is assigned
                      to this draft.
                    </p>
                  </div>
                </div>
                <h3>Declared prerequisites</h3>
                <ul className="atlas-prerequisites">
                  {skill.prerequisites.map((p) => {
                    const internal = preview.skills.some((s) => s.id === p);
                    return (
                      <li key={p}>
                        {internal ? (
                          <button onClick={() => select(p)}>
                            <code>{p}</code>
                            <Icon name="arrow" size={14} />
                          </button>
                        ) : (
                          <code>{p}</code>
                        )}
                        <small>
                          {internal
                            ? "Internal draft reference · also unmapped"
                            : "Unresolved external prerequisite"}
                        </small>
                      </li>
                    );
                  })}
                </ul>
                <div className="atlas-section-heading">
                  <h3>Stage structure</h3>
                  <span>METADATA ONLY</span>
                </div>
                <div
                  className="atlas-stages"
                  role="tablist"
                  aria-label="Draft stages"
                >
                  {skill.stages.map((s, i) => (
                    <button
                      role="tab"
                      aria-selected={stageId === s.id}
                      aria-controls="atlas-stage-panel"
                      id={`atlas-tab-${s.stage}`}
                      tabIndex={stageId === s.id ? 0 : -1}
                      key={s.id}
                      onClick={() => setStageId(s.id)}
                      onKeyDown={(e) => {
                        if (
                          !["ArrowLeft", "ArrowRight", "Home", "End"].includes(
                            e.key,
                          )
                        )
                          return;
                        e.preventDefault();
                        const next =
                          e.key === "Home"
                            ? 0
                            : e.key === "End"
                              ? skill.stages.length - 1
                              : (i +
                                  (e.key === "ArrowRight" ? 1 : -1) +
                                  skill.stages.length) %
                                skill.stages.length;
                        setStageId(skill.stages[next].id);
                        document
                          .getElementById(
                            `atlas-tab-${skill.stages[next].stage}`,
                          )
                          ?.focus();
                      }}
                    >
                      <small>{String(i + 1).padStart(2, "0")}</small>
                      {label(s.stage)}
                    </button>
                  ))}
                </div>
                {stage && (
                  <div
                    className="atlas-stage-panel"
                    role="tabpanel"
                    id="atlas-stage-panel"
                    aria-labelledby={`atlas-tab-${stage.stage}`}
                    tabIndex={0}
                  >
                    <code>{stage.id}</code>
                    <p>
                      This is a stage identifier, not a playable mission.
                      Instructions, objectives, difficulty, answer formats and
                      checks are not supplied.
                    </p>
                    <button disabled>
                      <Icon name="lock" size={14} />
                      Mission execution unavailable
                    </button>
                  </div>
                )}
                <details className="atlas-source">
                  <summary>Source & integrity</summary>
                  <p>Original-author metadata · {skill.sourceItem}</p>
                  <code>{skill.sourceSectionSha256}</code>
                  <p>
                    Source-section locator only. A hash is not technical or
                    pedagogical approval. Source bodies are not included.
                  </p>
                </details>
              </section>
            )}
          </div>
          <details className="atlas-open-questions">
            <summary>
              <span>Open questions before integration</span>
              <Badge tone="violet">
                {preview.blockers.length} MAPPING GAPS
              </Badge>
            </summary>
            <p>
              Four draft definitions lack exact mappings. Six external
              prerequisites remain unresolved. Similar names do not establish
              equivalence.
            </p>
            <ul>
              {preview.blockers.map((b) => (
                <li key={b.id}>
                  <code>{b.id}</code>
                  <span>
                    {b.code === "UNMAPPED_SKILL"
                      ? "Unmapped draft skill"
                      : "Unresolved external prerequisite"}
                  </span>
                </li>
              ))}
            </ul>
          </details>
          <div className="atlas-provenance">
            <div>
              <p className="eyebrow">
                BUNDLED JAVA METADATA / {preview.revision}
              </p>
              <p>{preview.caveat}</p>
              <p>
                Java 21 content verification pending. This is a pinned catalog,
                not a live learner snapshot.
              </p>
              <code aria-label="Public catalog checksum">
                {preview.revisionSha256}
              </code>
            </div>
            <button className="secondary-button" onClick={() => void read()}>
              Recheck catalog <Icon name="refresh" size={14} />
            </button>
          </div>
        </>
      )}
    </section>
  );
}
