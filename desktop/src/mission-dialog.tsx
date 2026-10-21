import { useEffect, useRef, useState } from "react";
import { Icon } from "./icons";
import { Badge, Button, Dialog, tabKeys } from "./components";
import { type Mission } from "./data";
export function MissionDialog({
  mission,
  onClose,
  onTree,
}: {
  mission: Mission;
  onClose: () => void;
  onTree: () => void;
}) {
  const [tab, setTab] = useState("Briefing");
  const [source, setSource] = useState(
    "// Original demonstration only. No Java execution.\n// Describe the first assumption you would test.\n\n",
  );
  const [saved, setSaved] = useState(source);
  const [discard, setDiscard] = useState(false);
  const [evaluation, setEvaluation] = useState("Not submitted");
  const [loading, setLoading] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  const dirty = source !== saved;
  const pending = useRef(onClose);
  const leave = (action: () => void) => {
    pending.current = action;
    if (dirty) setDiscard(true);
    else action();
  };
  const close = () => leave(onClose);
  return (
    <Dialog
      wide
      title={mission.title}
      eyebrow={`${mission.id} / DEMONSTRATION MISSION`}
      onClose={close}
    >
      <div className="mission-dialog-meta">
        <Badge tone="violet">PROTOTYPE CONTENT</Badge>
        <span>
          {mission.category} <i /> {mission.duration}
        </span>
      </div>
      {discard ? (
        <div className="unsaved-prompt" role="alert">
          <Icon name="alert" size={30} />
          <h3>Keep your thought in view.</h3>
          <p>
            This example has unsaved edits. Closing discards the in-memory text;
            no learner draft has been saved.
          </p>
          <div className="button-row">
            <Button secondary onClick={() => setDiscard(false)}>
              Keep editing
            </Button>
            <Button onClick={() => pending.current()}>Discard changes</Button>
          </div>
        </div>
      ) : (
        <>
          <div
            className="tabs"
            role="tablist"
            onKeyDown={tabKeys}
            aria-label="Mission workspace"
          >
            {["Briefing", "Workspace", "Evaluation"].map((t) => (
              <button
                role="tab"
                id={`mission-tab-${t}`}
                aria-controls="mission-panel"
                key={t}
                aria-selected={tab === t}
                tabIndex={tab === t ? 0 : -1}
                disabled={mission.status === "Blocked" && t !== "Briefing"}
                onClick={() => setTab(t)}
              >
                {t}
              </button>
            ))}
          </div>
          <div
            role="tabpanel"
            id="mission-panel"
            aria-labelledby={`mission-tab-${tab}`}
            className="mission-tab-content"
          >
            {tab === "Briefing" ? (
              <>
                <p className="dialog-lead">{mission.subtitle}</p>
                <ol className="briefing-objectives">
                  {mission.objectives.map((o, i) => (
                    <li key={o}>
                      <span>0{i + 1}</span>
                      {o}
                    </li>
                  ))}
                </ol>
                <div className="callout">
                  <Icon name="tree" />
                  <div>
                    <strong>{mission.prerequisite}</strong>
                    <p>
                      This sample does not check eligibility or expose reserved
                      Java challenge content.
                    </p>
                  </div>
                </div>
                <div className="progression-implication">
                  <h3>What could this practice develop?</h3>
                  <p>
                    {mission.skill}: a clearer explanation, a better question, a
                    testable next step. Actual progression would be determined
                    by Java evidence and reward policies.
                  </p>
                </div>
                <div className="button-row">
                  <Button secondary onClick={() => leave(onTree)} icon="tree">
                    View skill path
                  </Button>
                  {mission.status !== "Blocked" ? (
                    <Button onClick={() => setTab("Workspace")} icon="arrow">
                      Enter sample workspace
                    </Button>
                  ) : (
                    <Badge>BLOCKED IN THIS EXAMPLE</Badge>
                  )}
                </div>
              </>
            ) : tab === "Workspace" ? (
              <>
                <div className="editor-top">
                  <span>
                    <Icon name="code" size={15} /> Reflection.java{" "}
                    <Badge>EXAMPLE</Badge>
                  </span>
                  <span className={dirty ? "unsaved" : ""}>
                    {dirty ? "Unsaved edits" : "In memory only"}
                  </span>
                </div>
                <textarea
                  className="code-editor"
                  aria-label="Example mission editor"
                  value={source}
                  onChange={(e) => setSource(e.target.value)}
                  spellCheck={false}
                />
                <p className="fine-print">
                  Editable interaction sample. No compiler, durable autosave or
                  submission. Text is discarded when this dialog closes.
                </p>
                <div className="button-row">
                  <Button
                    secondary
                    onClick={() => setSaved(source)}
                    icon="check"
                  >
                    Keep in this preview
                  </Button>
                  <Button
                    onClick={() => {
                      setTab("Evaluation");
                      setEvaluation("Simulating");
                      setLoading(true);
                      timer.current = setTimeout(() => {
                        setEvaluation("Example feedback");
                        setLoading(false);
                      }, 900);
                    }}
                    icon="play"
                    disabled={mission.status === "Blocked"}
                  >
                    Simulate evaluation
                  </Button>
                </div>
              </>
            ) : (
              <>
                <label className="field-label" htmlFor="evaluation-state">
                  Evaluation scenario
                </label>
                <select
                  id="evaluation-state"
                  value={evaluation}
                  onChange={(e) => {
                    clearTimeout(timer.current);
                    setLoading(false);
                    setEvaluation(e.target.value);
                  }}
                >
                  {[
                    "Not submitted",
                    "Example feedback",
                    "Interrupted",
                    "Evaluator unavailable",
                    ...(loading ? ["Simulating"] : []),
                  ].map((s) => (
                    <option key={s}>{s}</option>
                  ))}
                </select>
                <div
                  className="evaluation-result"
                  aria-busy={loading}
                  role="status"
                >
                  <Icon
                    name={
                      evaluation === "Interrupted"
                        ? "alert"
                        : evaluation === "Evaluator unavailable"
                          ? "lock"
                          : evaluation === "Example feedback"
                            ? "target"
                            : "clock"
                    }
                    size={32}
                  />
                  <h3>
                    {evaluation === "Example feedback"
                      ? "An explanation is a useful first step."
                      : evaluation === "Interrupted"
                        ? "The outcome is not known yet."
                        : evaluation === "Evaluator unavailable"
                          ? "This environment cannot evaluate."
                          : evaluation === "Simulating"
                            ? "Preparing example feedback…"
                            : "A submission starts with intent."}
                  </h3>
                  <p>
                    {evaluation === "Example feedback"
                      ? "Sample feedback: name the assumption, trace the boundary, and suggest one focused check. This is not a pass, mastery estimate or earned reward."
                      : evaluation === "Interrupted"
                        ? "Example frozen submission DEMO-001 · stage: interrupted. Inspect durable status before any retry. No automatic resubmission is offered."
                        : evaluation === "Evaluator unavailable"
                          ? "Example runner status: unavailable. This is an environment limitation, not a learner failure. Your text remains in this dialog."
                          : evaluation === "Simulating"
                            ? "A short visual transition; no Java command is sent."
                            : "This prototype has not created a submission. Use the sample workspace to explore the interaction."}
                  </p>
                </div>
                <div className="button-row">
                  <Button
                    secondary
                    onClick={() => setTab("Workspace")}
                    icon="arrow"
                  >
                    Return to workspace
                  </Button>
                  {evaluation === "Interrupted" && (
                    <Button
                      onClick={() => setEvaluation("Example feedback")}
                      icon="refresh"
                    >
                      Inspect example status
                    </Button>
                  )}
                </div>
              </>
            )}
          </div>
        </>
      )}
    </Dialog>
  );
}
