import { useEffect, useState } from "react";
import { Icon } from "./icons";
import { CoreArt, MissionArt } from "./art";
import { Badge, Button, SectionTitle } from "./components";
import { missions, type Mission } from "./data";
import type { LearnerProjection } from "./learner-contract";
import { profileName, snapshotTime } from "./learner-profile";
export function CommandCenter({
  onMission,
  onTree,
  onMissions,
  onActivity,
  onBoss,
  unknown,
  learner,
  onProfile,
}: {
  onMission: (m: Mission) => void;
  onTree: () => void;
  onMissions: () => void;
  onActivity: () => void;
  onBoss: () => void;
  unknown: boolean;
  learner?: LearnerProjection;
  onProfile: () => void;
}) {
  const [objectives, setObjectives] = useState([true, false, false]);
  useEffect(() => {
    setObjectives(unknown ? [false, false, false] : [true, false, false]);
  }, [unknown]);
  return (
    <>
      <section className="command-hero">
        <div className="hero-copy">
          <span className="overline">
            <i /> YOUR NEXT CHAPTER
          </span>
          <h2>
            Build beyond
            <br />
            <em>what you know.</em>
          </h2>
          <p>
            Every problem is a doorway.
            <br />
            Turn today’s practice into tomorrow’s instinct.
          </p>
          <Button
            onClick={learner ? onProfile : () => onMission(missions[0])}
            icon="arrow"
          >
            {learner ? "View connected profile" : "Enter your next mission"}
          </Button>
          <span className="hero-footnote">
            {learner ? (
              "RECORDED PROGRESS · READ-ONLY SNAPSHOT"
            ) : (
              <>
                JAVA FOUNDATIONS <span>→</span> DEBUGGING
              </>
            )}
          </span>
        </div>
        <CoreArt />
        <div className="hero-coordinate">
          CF / ASCENT PROTOCOL{" "}
          <span>{learner ? "CONNECTED PROFILE" : "EXAMPLE PATH"}</span>
        </div>
      </section>
      {learner ? (
        <LearnerJourney learner={learner} onProfile={onProfile} />
      ) : (
        <div className="journey-strip">
          <div className="journey-title">
            <Icon name="tree" />
            <div>
              <span>YOUR ASCENT</span>
              <strong>
                {unknown ? "A path ready to explore" : "From syntax to systems"}
              </strong>
            </div>
          </div>
          <div className="path-steps">
            <span className={!unknown ? "done" : ""}>
              <i>{!unknown ? <Icon name="check" size={10} /> : "1"}</i>Discover
            </span>
            <b />
            <span className={!unknown ? "current" : ""}>
              <i>2</i>Practice
            </span>
            <b />
            <span>
              <i>3</i>Transfer
            </span>
          </div>
          <button className="text-button" onClick={onTree}>
            Explore tree <Icon name="arrow" size={15} />
          </button>
          <span className="strip-note">ILLUSTRATIVE PATH</span>
        </div>
      )}
      {learner ? (
        <LearnerOverview
          learner={learner}
          onProfile={onProfile}
          onMissions={onMissions}
          onBoss={onBoss}
        />
      ) : unknown ? (
        <section className="unknown-welcome">
          <p className="eyebrow">UNOBSERVED / NEW LEARNER EXAMPLE</p>
          <h2>Start with a question, not a score.</h2>
          <p>
            No active mission, completion history or earned achievement is
            connected. Your level, XP and mastery remain unknown.
          </p>
          <Button onClick={onMissions} icon="arrow">
            Explore demonstration missions
          </Button>
        </section>
      ) : (
        <>
          <div className="command-grid">
            <section className="active-mission">
              <SectionTitle
                label="CONTINUE WITH INTENTION"
                title="Your active mission"
                action={<Badge tone="cyan">EXAMPLE</Badge>}
              />
              <div className="active-mission-body">
                <div>
                  <div className="mission-meta">
                    <span>CF-024</span>
                    <span>JAVA / DEBUGGING</span>
                  </div>
                  <h3>
                    Trace the invisible<span>↗</span>
                  </h3>
                  <p>
                    Find the boundary. Explain the failure.
                    <br />
                    Make it hold.
                  </p>
                  <div className="mission-progress">
                    <span>Sample session</span>
                    <strong>Step 2 of 3</strong>
                  </div>
                  <div
                    className="step-track"
                    aria-label="Example session step 2 of 3"
                  >
                    <i />
                    <i />
                    <i />
                  </div>
                  <button
                    className="text-button"
                    onClick={() => onMission(missions[0])}
                  >
                    Resume preview <Icon name="arrow" size={17} />
                  </button>
                </div>
                <MissionArt />
              </div>
            </section>
            <section className="daily-objectives">
              <SectionTitle
                label="MAKE ROOM FOR PROGRESS"
                title="Today’s intentions"
                action={
                  <span className="count">
                    {objectives.filter(Boolean).length}
                    <small> / 3</small>
                  </span>
                }
              />
              <p className="small-muted">
                A sample checklist. Make it yours for this session.
              </p>
              <div className="objective-list">
                {[
                  "Revisit one important idea",
                  "Work through a focused mission",
                  "Reflect on what changed",
                ].map((o, i) => (
                  <label key={o} className={objectives[i] ? "checked" : ""}>
                    <input
                      type="checkbox"
                      checked={objectives[i]}
                      onChange={() =>
                        setObjectives(
                          objectives.map((v, j) => (j === i ? !v : v)),
                        )
                      }
                    />
                    <span className="checkbox-visual">
                      <Icon name="check" size={12} />
                    </span>
                    <span>{o}</span>
                    <small>0{i + 1}</small>
                  </label>
                ))}
              </div>
            </section>
          </div>
          <div className="command-lower">
            <section>
              <SectionTitle
                label="FOLLOW THE THREAD"
                title="Recent signals"
                action={
                  <button className="text-button" onClick={onActivity}>
                    View journal <Icon name="arrow" size={15} />
                  </button>
                }
              />
              <button className="activity-row" onClick={onActivity}>
                <span className="signal-icon">
                  <Icon name="code" />
                </span>
                <span>
                  <strong>A boundary, understood.</strong>
                  <small>Example reflection · The edge of a loop</small>
                </span>
                <Badge>EXAMPLE HISTORY</Badge>
                <Icon name="chevron" size={15} />
              </button>
              <button className="activity-row" onClick={onMissions}>
                <span className="signal-icon violet">
                  <Icon name="target" />
                </span>
                <span>
                  <strong>Another way forward.</strong>
                  <small>Explore a software design mission</small>
                </span>
                <Icon name="chevron" size={15} />
              </button>
            </section>
            <button className="horizon" onClick={onBoss}>
              <div className="horizon-symbol">
                <Icon name="shield" size={29} />
              </div>
              <p className="eyebrow">BEYOND THE HORIZON</p>
              <h3>The integration trial</h3>
              <p>
                A future encounter concept.
                <br />
                Connect everything you’ve learned.
              </p>
              <span>
                Inspect concept <Icon name="arrow" size={15} />
              </span>
              <small>PROTOTYPE ONLY</small>
            </button>
          </div>
        </>
      )}
    </>
  );
}

function LearnerJourney({
  learner,
  onProfile,
}: {
  learner: LearnerProjection;
  onProfile: () => void;
}) {
  const available = learner.progression.status === "available";
  return (
    <div className="journey-strip learner-journey">
      <div className="journey-title">
        <Icon name="tree" />
        <div>
          <span>YOUR RECORDED JOURNEY</span>
          <strong>{profileName(learner)}</strong>
        </div>
      </div>
      <div className="learner-progression" aria-label="Recorded engagement">
        <span>
          <small>LEVEL</small>
          <strong>
            {available ? learner.progression.level : "Unavailable"}
          </strong>
        </span>
        <span>
          <small>XP</small>
          <strong>
            {available
              ? learner.progression.xp?.toLocaleString()
              : "Unavailable"}
          </strong>
        </span>
      </div>
      <button className="text-button" onClick={onProfile}>
        Profile details <Icon name="arrow" size={15} />
      </button>
      <span className="strip-note">XP IS INDEPENDENT OF MASTERY</span>
    </div>
  );
}
function LearnerOverview({
  learner,
  onProfile,
  onMissions,
  onBoss,
}: {
  learner: LearnerProjection;
  onProfile: () => void;
  onMissions: () => void;
  onBoss: () => void;
}) {
  return (
    <>
      <div className="command-grid learner-overview">
        <section className="active-mission">
          <SectionTitle
            label="PRACTICE LEAVES A RECORD"
            title="Learning activity"
            action={<Badge tone="cyan">RECORDED</Badge>}
          />
          <div className="active-mission-body">
            <div>
              <div className="mission-meta">
                <span>LOCAL PROFILE</span>
                <span>READ-ONLY</span>
              </div>
              <h3>
                Your next horizon<span>↗</span>
              </h3>
              <p>
                Return to the ideas that matter.
                <br />
                Every observation adds context.
              </p>
              <dl className="learner-activity-metrics">
                <div>
                  <dt>Recorded reviews</dt>
                  <dd>{learner.activity.reviewCount.toLocaleString()}</dd>
                </div>
                <div>
                  <dt>Due scheduled cards</dt>
                  <dd>{learner.activity.dueScheduledCards.toLocaleString()}</dd>
                </div>
              </dl>
              <p className="small-muted">
                Due on {learner.activity.reviewDay} (UTC). New-card budgets and
                session limits are not included.
              </p>
            </div>
            <MissionArt />
          </div>
        </section>
        <section className="daily-objectives">
          <SectionTitle
            label="EVIDENCE BEFORE CERTAINTY"
            title="Understanding takes evidence"
          />
          <p className="small-muted">
            Participation alone does not establish skill mastery.
          </p>
          <dl className="learner-availability">
            <div>
              <dt>Skill mastery</dt>
              <dd>Unavailable</dd>
            </div>
            <div>
              <dt>Adaptive recommendations</dt>
              <dd>Unavailable</dd>
            </div>
            <div>
              <dt>Active mission status</dt>
              <dd>Unavailable</dd>
            </div>
          </dl>
          <p className="small-muted">
            These learning workflows remain in JavaFX. No new evaluation or
            reward is triggered by this view.
          </p>
        </section>
      </div>
      <div className="command-lower">
        <section>
          <SectionTitle
            label="KNOW WHERE YOU STAND"
            title="A moment in your journey"
            action={
              <button className="text-button" onClick={onProfile}>
                Profile details <Icon name="arrow" size={15} />
              </button>
            }
          />
          <div className="activity-row learner-snapshot-row">
            <span className="signal-icon">
              <Icon name="clock" />
            </span>
            <span>
              <strong>Verified learner snapshot</strong>
              <small>
                <time dateTime={learner.capturedAt}>
                  {snapshotTime(learner.capturedAt)}
                </time>
              </small>
            </span>
            <Badge>READ-ONLY</Badge>
          </div>
          <p className="snapshot-caption">
            Java reads a consistent copy of your selected database. Select the
            profile again to capture newer activity.
          </p>
          <button className="activity-row" onClick={onMissions}>
            <span className="signal-icon violet">
              <Icon name="target" />
            </span>
            <span>
              <strong>Explore another way forward.</strong>
              <small>
                Browse demonstration missions · not learner assignments
              </small>
            </span>
            <Badge>EXAMPLE</Badge>
            <Icon name="chevron" size={15} />
          </button>
        </section>
        <button className="horizon" onClick={onBoss}>
          <div className="horizon-symbol">
            <Icon name="shield" size={29} />
          </div>
          <p className="eyebrow">BEYOND THE HORIZON</p>
          <h3>The integration trial</h3>
          <p>
            A future encounter concept.
            <br />
            Connect everything you’ve learned.
          </p>
          <span>
            Inspect concept <Icon name="arrow" size={15} />
          </span>
          <small>SAMPLE CONCEPT · NOT AN ACHIEVEMENT</small>
        </button>
      </div>
    </>
  );
}
