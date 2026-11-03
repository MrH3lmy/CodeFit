#!/usr/bin/env python3
"""Generate deterministic reports from the checked-in original-only coverage ledger."""

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LEDGER_PATH = ROOT / "docs/knowledge-compiler/hunter-pilot-v0.41/coverage-ledger-input.json"
DEFAULT_OUTPUT_DIR = ROOT / "docs/knowledge-compiler/hunter-pilot-v0.41"
EXPECTED_BOOKS = {
    "ocp-java-se-17-guide": ("OCP Java SE 17 Guide", 26),
    "effective-java": ("Effective Java", 90),
    "100-java-mistakes": ("100 Java Mistakes", 100),
    "java-coding-problems": ("Java Coding Problems", 269),
    "complete-coding-interview-guide-java": ("Complete Coding Interview Guide in Java", 19),
}
STAGES = (
    "preliminaryMappedUnits",
    "firstPassAuthoredUnits",
    "deepOverlaySlots",
    "missionBlueprintSlots",
    "reviewApprovedUnits",
    "locallyPreviewableUnits",
    "runtimeImportedUnits",
    "gradedUnits",
    "learnerValidatedUnits",
)
class LedgerError(ValueError):
    """The allowlisted ledger is malformed or internally inconsistent."""


def _require(condition, message):
    if not condition:
        raise LedgerError(message)


def validate_ledger(ledger):
    _require(
        set(ledger) == {"schema", "ledgerVersion", "provenance", "books", "reportedClaims"},
        "unexpected ledger fields",
    )
    _require(ledger.get("schema") == "codefit-knowledge-coverage-ledger/1", "unsupported ledger schema")
    _require(ledger.get("ledgerVersion") == "1.0.0", "unsupported ledger version")
    provenance = ledger.get("provenance", {})
    _require(
        set(provenance) == {
            "observedAt", "sourceVersion", "source", "verification",
            "privateSourceBodies", "previousCheckpoint", "limitations",
        },
        "unexpected provenance fields",
    )
    _require(provenance.get("observedAt") == "2026-10-23", "unexpected observation date")
    _require(provenance.get("sourceVersion") == "v0.41", "unexpected source version")
    _require(
        provenance.get("source") == "v0.41 delivery report, as attributed in CodeFit issue #298",
        "unexpected source attribution",
    )
    _require(
        provenance.get("verification") == "SOURCE_ATTRIBUTED_NOT_INDEPENDENTLY_VERIFIED",
        "reported claims must remain unverified",
    )
    _require(
        provenance.get("privateSourceBodies") == "NOT_INDEPENDENTLY_VERIFIED",
        "private source bodies must remain explicitly unverified",
    )
    _require(provenance.get("previousCheckpoint") == "NOT_AVAILABLE", "unexpected prior checkpoint")
    _require(
        isinstance(provenance.get("limitations"), list)
        and all(isinstance(item, str) for item in provenance["limitations"]),
        "invalid provenance limitations",
    )

    books = ledger.get("books")
    _require(isinstance(books, list) and len(books) == len(EXPECTED_BOOKS), "expected exactly five books")
    by_id = {}
    unit_owners = {}
    for book in books:
        _require(
            set(book) == {"id", "title", "structuralUnits", "unitIds", "firstPassAuthoredUnits"},
            "unexpected book fields",
        )
        book_id = book.get("id")
        _require(book_id in EXPECTED_BOOKS, "unknown book")
        _require(book_id not in by_id, "duplicate book")
        by_id[book_id] = book
        title, expected_units = EXPECTED_BOOKS[book_id]
        _require(book.get("title") == title, "unexpected book title")
        units = book.get("structuralUnits")
        _require(isinstance(units, int) and not isinstance(units, bool) and units >= 0, "invalid structural unit total")
        _require(units == expected_units, "book structural unit count does not match reported baseline")
        unit_ids = book.get("unitIds")
        _require(unit_ids is None or isinstance(unit_ids, list), "invalid unit IDs")
        if unit_ids is not None:
            _require(len(unit_ids) == units, "enumerated unit count does not match reported inventory")
            for unit_id in unit_ids:
                _require(isinstance(unit_id, str) and unit_id, "invalid unit ID")
                _require(unit_id not in unit_owners, "duplicate unit ID")
                unit_owners[unit_id] = book_id
        first_pass = book.get("firstPassAuthoredUnits")
        _require(first_pass is None or (
            isinstance(first_pass, int) and not isinstance(first_pass, bool)
            and 0 <= first_pass <= units
        ), "invalid first-pass authored count")
        _require(first_pass == (90 if book_id == "effective-java" else None),
                 "unexpected book first-pass count")
    _require(set(by_id) == set(EXPECTED_BOOKS), "missing book")

    claims = ledger.get("reportedClaims", {})
    _require(
        set(claims) == {
            "structuralUnits", "preliminaryMappedUnits", "reportedUnmappedUnits",
            "effectiveJavaFirstPassAuthorNotes", "missionBlueprintSlots",
            "deepOverlaySlots", "revisionEvents", "independentApprovals",
            "productionGraders", "hunterProductionImports",
        },
        "unexpected reported claim fields",
    )
    _require(sum(book["structuralUnits"] for book in books) == claims.get("structuralUnits"),
             "structural unit total does not reconcile")
    mapped = claims.get("preliminaryMappedUnits")
    unmapped = claims.get("reportedUnmappedUnits")
    _require(type(mapped) is int and type(unmapped) is int
             and mapped == 157 and unmapped == 347
             and mapped + unmapped == claims["structuralUnits"],
             "historical reported preliminary mapping baseline counts do not reconcile")
    effective_java = by_id["effective-java"]
    first_pass_claim = claims.get("effectiveJavaFirstPassAuthorNotes", {})
    _require(set(first_pass_claim) == {"count", "denominator"}, "invalid first-pass claim fields")
    _require(first_pass_claim == {"count": effective_java["firstPassAuthoredUnits"], "denominator": 90},
             "Effective Java first-pass claim does not reconcile")
    _require(claims.get("missionBlueprintSlots") == 419, "unexpected unique mission blueprint count")
    _require(claims.get("deepOverlaySlots") == 360, "unexpected deep overlay count")
    revision_events = claims.get("revisionEvents", {})
    _require(
        set(revision_events) == {"count", "additiveToMissionBlueprintSlots", "records"},
        "invalid revision event fields",
    )
    _require(revision_events.get("count") == 424, "unexpected revision event count")
    _require(revision_events.get("additiveToMissionBlueprintSlots") is False,
             "revision events must be explicitly nonadditive")
    records = revision_events.get("records")
    _require(records is None or isinstance(records, list), "invalid revision event records")
    if records is not None:
        event_ids = {}
        slot_revisions = {}
        for event in records:
            _require(set(event) == {"eventId", "missionId", "revision", "replacesRevision"},
                     "invalid revision event fields")
            event_id = event["eventId"]
            _require(isinstance(event_id, str) and event_id, "invalid revision event ID")
            _require(
                all(isinstance(event[name], str) and event[name]
                    for name in ("missionId", "revision", "replacesRevision")),
                "invalid revision lineage",
            )
            _require(event_id not in event_ids, "conflicting or duplicate revision event")
            event_ids[event_id] = event
            revision_key = (event["missionId"], event["revision"])
            previous_revision = slot_revisions.get(revision_key)
            _require(previous_revision is None or previous_revision == event["replacesRevision"],
                     "conflicting revision lineage")
            slot_revisions[revision_key] = event["replacesRevision"]
        _require(len(records) == revision_events["count"], "revision record count does not reconcile")
    _require(claims.get("independentApprovals") == 0, "approval count must remain zero")
    _require(claims.get("productionGraders") == 0, "production grader count must remain zero")
    _require(claims.get("hunterProductionImports") == 0, "Hunter production import count must remain zero")
    return ledger


def validate_no_regression(previous, current):
    """Reject missing, invalid, or decreased previously known checkpoint counts."""
    for book_id, old_book in previous.items():
        _require(book_id in current, "prior checkpoint book missing")
        new_book = current[book_id]
        for field in ("structuralUnits", *STAGES):
            old_value = old_book.get(field)
            new_value = new_book.get(field)
            if old_value is not None:
                _require(
                    type(old_value) is int and type(new_value) is int and new_value >= old_value,
                    f"prior-checkpoint regression: {book_id}.{field}",
                )


def _book_stages(book, claims):
    first_pass = book["firstPassAuthoredUnits"]
    return {
        "preliminaryMappedUnits": None,
        "firstPassAuthoredUnits": first_pass,
        "deepOverlaySlots": None,
        "missionBlueprintSlots": None,
        "reviewApprovedUnits": 0 if claims["independentApprovals"] == 0 else None,
        "locallyPreviewableUnits": None,
        "runtimeImportedUnits": 0 if claims["hunterProductionImports"] == 0 else None,
        "gradedUnits": None,
        "learnerValidatedUnits": None,
    }


def build_report(ledger):
    validate_ledger(ledger)
    claims = ledger["reportedClaims"]
    books = []
    for source_book in ledger["books"]:
        book = {
            "id": source_book["id"],
            "title": source_book["title"],
            "inventory": {
                "count": source_book["structuralUnits"],
                "status": "REPORTED_NOT_ENUMERATED",
            },
            "stages": {},
        }
        for stage in STAGES:
            count = _book_stages(source_book, claims)[stage]
            status = "REPORTED_FIRST_PASS_NOT_REVIEWED" if stage == "firstPassAuthoredUnits" and count is not None else (
                "ZERO_REPORTED" if count == 0 else "UNKNOWN"
            )
            book["stages"][stage] = {"count": count, "status": status}
        book["followUp"] = (
            "Enumerate source-unit IDs and independently reconcile the 90 first-pass notes; "
            "then map, review, and assess each unit without treating author notes as approval."
            if source_book["id"] == "effective-java"
            else "Enumerate source-unit IDs, reconcile book-specific mapping and authoring counts, "
                 "then obtain independent review before any runtime or learner claims."
        )
        books.append(book)
    return {
        "schema": "codefit-knowledge-coverage-report/1",
        "ledgerVersion": ledger["ledgerVersion"],
        "provenance": ledger["provenance"],
        "reportedTotals": {
            "structuralUnits": claims["structuralUnits"],
            "preliminaryMappedUnits": claims["preliminaryMappedUnits"],
            "reportedUnmappedUnits": claims["reportedUnmappedUnits"],
            "effectiveJavaFirstPassAuthorNotes": claims["effectiveJavaFirstPassAuthorNotes"],
            "missionBlueprintSlots": claims["missionBlueprintSlots"],
            "deepOverlaySlots": claims["deepOverlaySlots"],
            "revisionEvents": claims["revisionEvents"]["count"],
            "revisionEventsAdditiveToMissionBlueprintSlots": False,
            "independentApprovals": claims["independentApprovals"],
            "productionGraders": claims["productionGraders"],
            "hunterProductionImports": claims["hunterProductionImports"],
        },
        "books": books,
        "stageDefinitions": {
            "preliminaryMappedUnits": "Reported preliminary mappings; per-book distribution is UNKNOWN.",
            "firstPassAuthoredUnits": "First-pass author notes only; not review approval or learning completion.",
            "deepOverlaySlots": "Reported overlay slots; per-book distribution is UNKNOWN.",
            "missionBlueprintSlots": "Unique reported blueprint slots; revision events are not additive.",
            "reviewApprovedUnits": "Independent review-approved units; the report records zero approvals.",
            "locallyPreviewableUnits": "Book-unit local preview coverage; UNKNOWN and distinct from metadata preview records.",
            "runtimeImportedUnits": "Hunter production imports; report records zero.",
            "gradedUnits": "Units with production grading evidence; UNKNOWN (zero production graders reported).",
            "learnerValidatedUnits": "Units validated by learners; UNKNOWN.",
        },
        "previewBoundary": {
            "source": "Checked-in Java KnowledgePreview v0.41",
            "authorMetadataSkillRecords": 4,
            "metadataStageIds": 16,
            "interpretation": "These author-preview records are not enumerated book units or additional blueprint slots.",
            "productionImportSupported": False,
            "graderAvailable": False,
            "independentApprovals": 0,
        },
    }


def render_json(report):
    return json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def render_markdown(report):
    totals = report["reportedTotals"]
    lines = [
        "# Five-book Knowledge Coverage Ledger",
        "",
        f"**Source version:** {report['provenance']['sourceVersion']}  ",
        f"**Observed at:** {report['provenance']['observedAt']}  ",
        f"**Verification:** `{report['provenance']['verification']}`  ",
        f"**Private source bodies:** `{report['provenance']['privateSourceBodies']}`",
        "",
        "> Historical source-attributed claims only. The delivery report is not checked in,",
        "> no source-unit inventory was independently enumerated, and unknown distributions",
        "> remain `UNKNOWN`. No stage total is a proxy for another stage.",
        "",
        "## Reported baseline claims",
        "",
        "| Measure | Reported value | Interpretation |",
        "| --- | ---: | --- |",
        f"| Structural units | {totals['structuralUnits']} | Reported inventory count; not independently enumerated |",
        f"| Preliminary mappings | {totals['preliminaryMappedUnits']} | Reported preliminary mapping, not learning completion |",
        f"| Unmapped units | {totals['reportedUnmappedUnits']} | Reported remainder; reconciles to 504 − 157 |",
        f"| Effective Java first-pass author notes | {totals['effectiveJavaFirstPassAuthorNotes']['count']}/{totals['effectiveJavaFirstPassAuthorNotes']['denominator']} | Author notes, not review-approved |",
        f"| Unique mission blueprint slots | {totals['missionBlueprintSlots']} | Unique slots; do not add revision events |",
        f"| Deep overlay slots | {totals['deepOverlaySlots']} | Separate stage; per-book distribution unknown |",
        f"| Revision events | {totals['revisionEvents']} | **Nonadditive**; replacements, not additional unique slots |",
        f"| Independent approvals | {totals['independentApprovals']} | Reported zero |",
        f"| Production graders | {totals['productionGraders']} | Reported zero; grading outcomes remain unknown |",
        f"| Hunter production imports | {totals['hunterProductionImports']} | Reported zero |",
        "",
        "## Per-book gap matrix",
        "",
        "| Book | Units¹ | Preliminary mapped | First-pass authored | Deep overlays | Mission slots | Review-approved | Locally previewable | Runtime imported | Graded | Learner-validated | Next action |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for book in report["books"]:
        stages = book["stages"]
        cells = [
            str(book["inventory"]["count"]),
            _format_count(stages["preliminaryMappedUnits"]["count"]),
            _format_count(stages["firstPassAuthoredUnits"]["count"], denominator=book["inventory"]["count"]),
            _format_count(stages["deepOverlaySlots"]["count"]),
            _format_count(stages["missionBlueprintSlots"]["count"]),
            _format_count(stages["reviewApprovedUnits"]["count"]),
            _format_count(stages["locallyPreviewableUnits"]["count"]),
            _format_count(stages["runtimeImportedUnits"]["count"]),
            _format_count(stages["gradedUnits"]["count"]),
            _format_count(stages["learnerValidatedUnits"]["count"]),
        ]
        lines.append("| " + " | ".join([book["title"], *cells, book["followUp"]]) + " |")
    lines.extend([
        "",
        "¹ Structural counts are historical reported claims, not enumerated unit IDs.",
        "",
        "## Stage and preview boundaries",
        "",
    ])
    lines.extend(f"- **{stage}:** {definition}" for stage, definition in report["stageDefinitions"].items())
    preview = report["previewBoundary"]
    lines.extend([
        "",
        f"The checked-in Java preview contains {preview['authorMetadataSkillRecords']} author-metadata skill records "
        f"and {preview['metadataStageIds']} metadata stage IDs. {preview['interpretation']} "
        "Preview loading is not runtime import, grading, content approval, or learner validation.",
        "",
        "## Provenance and unreconciled claims",
        "",
        f"- Source attribution: {report['provenance']['source']}.",
        f"- Prior checkpoint: `{report['provenance']['previousCheckpoint']}`; no regression comparison is claimed.",
        *[f"- {limitation}" for limitation in report["provenance"]["limitations"]],
        "- Per-book preliminary mapping, deep-overlay, mission, local preview, grading, and learner-validation distributions are unknown.",
        "",
        "## Reproducibility",
        "",
        "From the repository root, run `python3 scripts/knowledge_coverage.py`. "
        "It reads only the checked-in original-only ledger fixture and writes deterministic "
        "JSON and Markdown reports alongside that fixture.",
        "",
    ])
    return "\n".join(lines)


def _format_count(count, denominator=None):
    if count is None:
        return "UNKNOWN"
    if denominator is not None:
        return f"{count}/{denominator} (reported)"
    return str(count)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help="directory for generated reports (default: the v0.41 documentation directory)",
    )
    args = parser.parse_args()
    try:
        ledger = json.loads(LEDGER_PATH.read_text(encoding="utf-8"))
        report = build_report(ledger)
        json_output = render_json(report)
        markdown_output = render_markdown(report)
        args.output_dir.mkdir(parents=True, exist_ok=True)
        (args.output_dir / "coverage-report.json").write_text(json_output, encoding="utf-8")
        (args.output_dir / "coverage-report.md").write_text(markdown_output, encoding="utf-8")
    except (OSError, json.JSONDecodeError, LedgerError) as error:
        parser.error(str(error))
    print(f"Wrote {args.output_dir / 'coverage-report.json'}")
    print(f"Wrote {args.output_dir / 'coverage-report.md'}")


if __name__ == "__main__":
    main()
