# Snapshot comparison core

Issue: #191
Integration follow-up: #185, after #183 supplies real snapshots.

This slice is the pure calculation layer for already decoded, verified, currently permitted peer
snapshots. It performs no networking, database/file I/O, JavaFX work, identity generation, or
permission enforcement.

## Inputs and freshness

SnapshotComparisonEngine accepts explicit SnapshotObservation values.

An available observation contains:
- the supplied snapshot;
- capturedAt from the accepted envelope or equivalent local capture context.

Capture metadata may not predate the snapshot's own cutoff/capturedAt. Freshness is computed from the
snapshot data time itself (ProgressSummary.cutoff or PreparationSnapshot.capturedAt), not from when a
receiver happened to receive or re-publish it. A freshly received old snapshot therefore stays old.

Unavailable inputs are explicit:
- MISSING means no snapshot/evidence was supplied;
- NOT_SHARED may be used only when the caller has explicit sharing/permission context;
- UNAVAILABLE means the producer/caller knows the value cannot be supplied.

Plain omission is never interpreted as a privacy decision.

EvaluationContext contains the evaluation instant, caller-selected maximum snapshot age, explicit
per-side metric omission context, and optional task/difficulty cohort evidence. The result reports
snapshot ages and distinguishes stale and future snapshots/capture metadata. Historical screens can
deliberately use a longer freshness horizon than live peer screens.

The application boundary must verify/decode envelopes and enforce current permissions before calling
this engine.

## Progress comparisons

For intended same-period peer comparisons:
- DAY and WEEK kinds must match.
- ComparisonWindow.sameLocalPeriodAs identifies matching local labels.
- Matching labels are not enough: if signed UTC [start,end) bounds differ, the result is
  UTC_INTERVAL_MISMATCH.
- Different zones/local periods are explicit DESCRIPTIVE_ONLY results and are never silently shown as
  the same period.

For historical-self comparisons:
- window kinds must match;
- different zones are explicit descriptive-only comparisons;
- different week-start policies are explicit descriptive-only comparisons;
- #185 must select an actual stored previous-period snapshot. This core never reconstructs history.

### Partial periods and DST

Complete calendar periods can be compared to complete calendar periods even when DST makes their
durations differ, for example 23 or 25 hours.

If both summaries are partial, elapsed duration from each signed window start to its cutoff must be
equal. Different elapsed cutoffs return CUTOFF_MISMATCH.

A complete period and a partial period return COMPLETE_PARTIAL_MISMATCH.

A later aggregate is never shortened or prorated to an earlier cutoff. If only a 12-hour aggregate
exists and a 10-hour comparison is required, #183/#185 must obtain a genuinely aligned 10-hour
snapshot. The core returns unavailable rather than fabricating a value.

## Metric compatibility and deltas

Each MetricComparison retains the original MetricValue on both sides, preserving:
- metric id and version;
- unit;
- availability;
- value;
- sample size;
- provenance;
- timestamp basis.

Rules:
- unknown metric id/version -> UNKNOWN_METRIC;
- same id with incompatible versions -> VERSION_MISMATCH;
- omitted on one side -> LEFT_METRIC_MISSING or RIGHT_METRIC_MISSING unless the caller supplies an
  explicit NOT_SHARED/UNAVAILABLE MetricOmission for that side;
- explicit per-metric NOT_SHARED -> LEFT_METRIC_NOT_SHARED or RIGHT_METRIC_NOT_SHARED;
- UNAVAILABLE stays unavailable and is not zero;
- INSUFFICIENT_DATA stays low-sample and is not a measured zero;
- different provenance is DESCRIPTIVE_ONLY with PROVENANCE_MISMATCH;
- progress metrics default to DESCRIPTIVE_ONLY with COHORT_EVIDENCE_MISSING unless the caller
  explicitly supplies MATCHED task/difficulty-cohort evidence for that metric;
- explicitly unmatched cohorts remain DESCRIPTIVE_ONLY with COHORT_MISMATCH;
- timestamp-basis uncertainty is preserved, including legacy and mixed timestamp bases.

Deltas use BigDecimal so supported long count/time values cannot overflow.

COUNT produces a count delta.
SECONDS produces a seconds delta.
BASIS_POINTS and PERCENT produce percentage-point deltas.

Relative percentage change is also returned when the right/baseline value is non-zero. A zero
baseline returns ZERO_BASELINE with no relative percentage, never NaN or infinity.

The engine produces no universal winner, XP score, normalization across unmatched practice sets, or
inference that more practice time means greater skill. Missing cohort evidence never silently becomes
"matched"; numeric deltas may still be shown descriptively.

## Preparation comparisons

PreparationSnapshot.scoresComparableWith and readinessComparableWith remain authoritative.

When profile definition and scoring version match:
- raw overall score, coverage, and compatible domain scores may be compared;
- domain status, critical gates, thresholds, coverage, and measured/total requirements remain visible;
- blocking critical-domain ids are copied into the result.

If only the overall readiness threshold differs:
- raw scores remain comparable;
- readinessComparable is false;
- the result carries READINESS_THRESHOLD_MISMATCH;
- READY/NOT_READY is not treated as an equivalent grade.

If profile id/fingerprint or scoring version differs:
- no overall numeric rank/delta is produced;
- common domain ids are DESCRIPTIVE_ONLY;
- no domain score delta is claimed across incompatible definitions.

Even with matching fingerprints, visible per-domain definition fields are checked before producing a
domain delta. Conflicting weight, critical-gate flag, threshold, or total-requirement count yields
DOMAIN_DEFINITION_MISMATCH.

Stage or elapsed-preparation comparisons are intentionally unavailable in this slice because v1
PreparationSnapshot does not carry preparation-start/checkpoint evidence.
unavailablePreparationStage returns HISTORICAL_EVIDENCE_MISSING instead of inventing a baseline.

A current whole-history readiness snapshot must never be relabelled as activity inside a selected
DAY/WEEK.

## What #183 must supply

The snapshot-production layer must supply:
1. ProgressSummary values for the exact signed DAY/WEEK window and requested cutoff.
2. For partial comparisons, an aggregate produced at the exact required equal-elapsed cutoff.
3. Explicit MetricAvailability and sample sizes under MetricRegistry minima.
4. Correct metric id/version/unit, provenance, and timestamp basis.
5. Dated PreparationSnapshot values captured from the real readiness engine.
6. Historical snapshots/checkpoints only when they truly exist.
7. Envelope/local capture metadata that #185 can pass as capturedAt.
8. Explicit whole-snapshot and per-metric not-shared/producer-unavailable context only when the
   application really knows it.
9. Matched/unmatched task or difficulty cohort evidence when available; absence stays UNKNOWN.

If an aligned earlier cutoff is missing, #183 must report missing evidence; it must not prorate a
later total.

## What #185 must supply and enforce

The integration layer owns:
1. Envelope verification/decoding and current sharing authorization.
2. Selecting the intended peer/current/previous snapshot from real stored data.
3. Choosing the evaluation instant and snapshot-freshness horizon.
4. Using NOT_SHARED only from explicit permission state; omission alone is MISSING, including at the
   individual metric level.
5. Supplying matched-cohort evidence only when #183/#185 can actually establish it; otherwise keeping
   the metric descriptive.
6. Requesting a genuinely aligned partial snapshot when a cached cutoff is too late.
7. Surfacing cross-zone, week-policy, cutoff, stale, low-sample, provenance, cohort, version,
   timestamp-basis, profile-definition, scoring-version, threshold, coverage, and critical-gate warnings.
8. Keeping preparation/readiness comparisons separate from DAY/WEEK activity.
9. Never turning DESCRIPTIVE_ONLY results into a leaderboard rank or winner.
10. Adding separately reviewed start/checkpoint evidence before stage/elapsed-preparation UX is enabled.

## Determinism and side effects

For identical snapshots, observation metadata, and EvaluationContext, the engine returns the same
ordered result. Metric and domain outputs use stable identifier/version ordering.

The package performs no networking, filesystem/database access, JavaFX calls, clock reads,
identity/permission decisions, or mutation of supplied protocol records.
