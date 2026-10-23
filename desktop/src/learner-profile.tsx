import { useEffect, useRef, useState } from "react";
import { Badge, Button } from "./components";
import { Icon } from "./icons";
import {
  LearnerResultSchema,
  type LearnerProjection,
} from "./learner-contract";

const errorMessages: Record<string, string> = {
  PROFILE_BUSY:
    "This profile is in use. Close CodeFit JavaFX before connecting a snapshot.",
  PROFILE_INVALID:
    "This file could not be validated as a supported CodeFit profile.",
  PROFILE_MISMATCH:
    "The connected profile identity changed. Select the profile again.",
  INCOMPATIBLE_SCHEMA:
    "This profile uses an unsupported database version. No migration was performed.",
  SOURCE_CHANGED:
    "The selected file changed during capture. Close other applications and select it again.",
  SNAPSHOT_LIMIT:
    "The private snapshot storage limit has been reached. See the profile backup guide before retrying.",
  SNAPSHOT_FAILED:
    "A verified snapshot could not be completed. The learner database was not replaced.",
  TIMEOUT:
    "Snapshot access timed out. Select the profile again when it is available.",
  CANCELLED: "Profile connection cancelled.",
  BUSY: "A profile operation is already in progress.",
  UNSUPPORTED_PLATFORM:
    "Learner snapshots currently support validated local Linux filesystems. This platform or filesystem requires native validation first.",
};

export function profileName(profile: LearnerProjection | undefined): string {
  return (
    profile?.identity.displayName || (profile ? "Learner profile" : "Explorer")
  );
}
export function snapshotTime(value: string): string {
  return new Date(value).toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZoneName: "short",
  });
}

/** A replaced, cancelled or unmounted selection can never publish its delayed reply. */
export function useLearnerProfile() {
  const [profile, setProfile] = useState<LearnerProjection>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const generation = useRef(0);
  useEffect(
    () => () => {
      generation.current++;
    },
    [],
  );
  async function choose() {
    const current = ++generation.current;
    setProfile(undefined);
    setError(undefined);
    setBusy(true);
    try {
      const reply = LearnerResultSchema.parse(
        await window.codefit.chooseLearnerProfile(),
      );
      if (generation.current !== current) return;
      if (reply.ok) setProfile(reply.result);
      else
        setError(
          errorMessages[reply.code] ||
            "The learner snapshot is unavailable. Select the profile again.",
        );
    } catch {
      if (generation.current === current)
        setError(
          "The learner snapshot is unavailable. Select the profile again.",
        );
    } finally {
      if (generation.current === current) setBusy(false);
    }
  }
  async function disconnect() {
    const current = ++generation.current;
    setProfile(undefined);
    setBusy(false);
    setError(undefined);
    try {
      await window.codefit.disconnectLearnerProfile();
    } catch {
      if (generation.current === current)
        setError(
          "The profile view is disconnected. Close CodeFit before reconnecting if the engine remains unavailable.",
        );
    }
  }
  return { profile, busy, error, choose, disconnect };
}

export function LearnerProfile({
  profile,
  busy,
  error,
  onChoose,
  onDisconnect,
}: {
  profile?: LearnerProjection;
  busy: boolean;
  error?: string;
  onChoose: () => void;
  onDisconnect: () => void;
}) {
  const available = profile?.progression.status === "available";
  return (
    <div className="learner-profile">
      <div className="profile-summary">
        <span className="avatar large">
          {Array.from(profileName(profile))[0]}
        </span>
        <div>
          <h3>{profileName(profile)}</h3>
          <p>
            {profile
              ? profile.identity.status === "available"
                ? "Recorded local display name"
                : "Learner name unavailable"
              : "Not connected"}
          </p>
        </div>
        <Badge tone={profile ? "cyan" : "violet"}>
          {profile ? "READ-ONLY SNAPSHOT" : "LOCAL PROFILE"}
        </Badge>
      </div>
      <p className="dialog-intro">
        {profile
          ? "This view reads a verified copy of your selected profile. Changes in JavaFX appear after you select and capture the profile again."
          : "Choose your existing codefit.db to view recorded progress. JavaFX normally stores it in the folder from which it was launched."}
      </p>
      <div className="profile-metrics">
        <div>
          <span>Recorded level</span>
          <strong>{available ? profile.progression.level : "—"}</strong>
          <small>{available ? "Engagement" : "Unavailable"}</small>
        </div>
        <div>
          <span>Recorded XP</span>
          <strong>
            {available ? profile.progression.xp?.toLocaleString() : "—"}
          </strong>
          <small>
            {available ? "Participation, not mastery" : "Unavailable"}
          </small>
        </div>
        <div>
          <span>Skill mastery</span>
          <strong>—</strong>
          <small>Unavailable</small>
        </div>
        <div>
          <span>Active missions</span>
          <strong>—</strong>
          <small>Unavailable</small>
        </div>
      </div>
      {profile && (
        <p className="snapshot-caption">
          Snapshot captured{" "}
          <time dateTime={profile.capturedAt}>
            {snapshotTime(profile.capturedAt)}
          </time>
          . These values are recorded history, not a live connection.
        </p>
      )}
      <p className="callout">
        <Icon name="shield" />
        {profile
          ? "A private full database backup is stored on this device."
          : "Connecting creates a private full database backup on this device."}{" "}
        Connecting does not migrate your profile or award XP. XP and skill
        mastery remain independent.
      </p>
      {error && (
        <p className="learner-error" role="alert">
          {error}
        </p>
      )}
      {busy && (
        <p className="snapshot-caption" role="status">
          Waiting for selection or a verified snapshot…
        </p>
      )}
      <div className="learner-actions">
        <Button onClick={onChoose} disabled={busy} icon="arrow">
          {profile ? "Capture again" : "Choose learner profile"}
        </Button>
        {(profile || busy) && (
          <Button secondary onClick={onDisconnect}>
            {busy ? "Cancel connection" : "Disconnect profile"}
          </Button>
        )}
      </div>
      <p className="fine-print">
        A confirmation appears before CodeFit accesses the database. Close
        JavaFX first. Older applications may ignore ownership locks; this
        integration opens the source read-only. Native SQLite may use lock or
        shared-memory sidecars.
      </p>
      <details className="snapshot-privacy">
        <summary>Private backup storage and cleanup</summary>
        <p>
          Full snapshots are unencrypted and may contain answers, drafts and
          peer keys. File permissions restrict access, but do not protect
          against someone using your account or reading an unencrypted disk.
        </p>
        <p>
          Storage stops after eight completed or interrupted captures.
          Disconnect and close all CodeFit instances before managing files.
          Archive completed snapshots to private, encrypted storage and verify
          the copy before manually removing a chosen backup. Interrupted
          .partial files are not verified backups.
        </p>
        <p>
          Disconnecting does not delete backups. Automatic deletion and source
          replacement are disabled. Deletion cannot guarantee secure erasure on
          SSDs, synchronized folders or backups; use operating-system disk
          encryption and avoid shared storage.
        </p>
      </details>
    </div>
  );
}
