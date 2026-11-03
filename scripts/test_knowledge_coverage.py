import copy
import contextlib
import io
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import knowledge_coverage


class CoverageLedgerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ledger = json.loads(knowledge_coverage.LEDGER_PATH.read_text(encoding="utf-8"))

    def test_report_preserves_unknowns_and_nonadditive_counts(self):
        report = knowledge_coverage.build_report(self.ledger)
        self.assertEqual(504, report["reportedTotals"]["structuralUnits"])
        self.assertEqual(157, report["reportedTotals"]["preliminaryMappedUnits"])
        self.assertEqual(347, report["reportedTotals"]["reportedUnmappedUnits"])
        self.assertEqual(419, report["reportedTotals"]["missionBlueprintSlots"])
        self.assertEqual(424, report["reportedTotals"]["revisionEvents"])
        self.assertFalse(report["reportedTotals"]["revisionEventsAdditiveToMissionBlueprintSlots"])
        self.assertNotEqual(843, report["reportedTotals"]["missionBlueprintSlots"])
        for book in report["books"]:
            self.assertEqual("UNKNOWN", book["stages"]["preliminaryMappedUnits"]["status"])
            self.assertEqual("UNKNOWN", book["stages"]["deepOverlaySlots"]["status"])
        self.assertEqual(90, report["books"][1]["stages"]["firstPassAuthoredUnits"]["count"])

    def test_invalid_totals_are_rejected(self):
        ledger = copy.deepcopy(self.ledger)
        ledger["reportedClaims"]["reportedUnmappedUnits"] = 346
        with self.assertRaisesRegex(knowledge_coverage.LedgerError, "do not reconcile"):
            knowledge_coverage.validate_ledger(ledger)

    def test_v041_mapping_counts_cannot_be_silently_reallocated(self):
        for mapped, unmapped in ((158, 346), (156, 348), (True, 503)):
            ledger = copy.deepcopy(self.ledger)
            ledger["reportedClaims"]["preliminaryMappedUnits"] = mapped
            ledger["reportedClaims"]["reportedUnmappedUnits"] = unmapped
            with self.subTest(mapped=mapped, unmapped=unmapped):
                with self.assertRaisesRegex(knowledge_coverage.LedgerError, "baseline counts do not reconcile"):
                    knowledge_coverage.validate_ledger(ledger)

    def test_duplicate_unit_ids_are_rejected(self):
        ledger = copy.deepcopy(self.ledger)
        ledger["books"][0]["unitIds"] = ["unit-1"] * ledger["books"][0]["structuralUnits"]
        with self.assertRaisesRegex(knowledge_coverage.LedgerError, "duplicate unit ID"):
            knowledge_coverage.validate_ledger(ledger)

    def test_conflicting_revisions_are_rejected(self):
        ledger = copy.deepcopy(self.ledger)
        ledger["reportedClaims"]["revisionEvents"]["records"] = [
            {"eventId": "event-1", "missionId": "mission-1", "revision": "r2", "replacesRevision": "r1"},
            {"eventId": "event-2", "missionId": "mission-1", "revision": "r2", "replacesRevision": "r0"},
        ]
        with self.assertRaisesRegex(knowledge_coverage.LedgerError, "conflicting revision lineage"):
            knowledge_coverage.validate_ledger(ledger)

    def test_missing_book_and_unknown_book_units_are_rejected(self):
        ledger = copy.deepcopy(self.ledger)
        ledger["books"].pop()
        with self.assertRaisesRegex(knowledge_coverage.LedgerError, "exactly five books"):
            knowledge_coverage.validate_ledger(ledger)
        ledger = copy.deepcopy(self.ledger)
        ledger["books"][0]["unitIds"] = ["unit-1"]
        with self.assertRaisesRegex(knowledge_coverage.LedgerError, "does not match reported inventory"):
            knowledge_coverage.validate_ledger(ledger)
        ledger = copy.deepcopy(self.ledger)
        ledger["books"][0]["id"] = "unknown-book"
        with self.assertRaisesRegex(knowledge_coverage.LedgerError, "unknown book"):
            knowledge_coverage.validate_ledger(ledger)

    def test_unallowlisted_content_fields_are_rejected(self):
        ledger = copy.deepcopy(self.ledger)
        ledger["sourceText"] = "not allowed"
        with self.assertRaisesRegex(knowledge_coverage.LedgerError, "unexpected ledger fields"):
            knowledge_coverage.validate_ledger(ledger)

    def test_prior_checkpoint_regression_is_rejected(self):
        previous = {"effective-java": {"structuralUnits": 90, "firstPassAuthoredUnits": 90}}
        current = {"effective-java": {"structuralUnits": 90, "firstPassAuthoredUnits": 89}}
        with self.assertRaisesRegex(knowledge_coverage.LedgerError, "prior-checkpoint regression"):
            knowledge_coverage.validate_no_regression(previous, current)

    def test_known_checkpoint_counts_cannot_disappear_or_become_unknown(self):
        previous = {"effective-java": {"structuralUnits": 90, "firstPassAuthoredUnits": 90}}
        for new_book in (
            {"structuralUnits": 90, "firstPassAuthoredUnits": None},
            {"structuralUnits": 90},
            {"structuralUnits": 90, "firstPassAuthoredUnits": "UNKNOWN"},
            {"structuralUnits": 90, "firstPassAuthoredUnits": True},
        ):
            with self.subTest(new_book=new_book):
                with self.assertRaisesRegex(knowledge_coverage.LedgerError, "prior-checkpoint regression"):
                    knowledge_coverage.validate_no_regression(previous, {"effective-java": new_book})

    def test_unknown_checkpoint_count_can_become_known_without_regression(self):
        previous = {"effective-java": {"structuralUnits": 90, "firstPassAuthoredUnits": None}}
        current = {"effective-java": {"structuralUnits": 90, "firstPassAuthoredUnits": 90}}
        knowledge_coverage.validate_no_regression(previous, current)
        knowledge_coverage.validate_no_regression(current, current)

    def test_generation_is_deterministic_read_only_and_private_body_free(self):
        original = copy.deepcopy(self.ledger)
        report = knowledge_coverage.build_report(self.ledger)
        self.assertEqual(original, self.ledger)
        self.assertEqual("NOT_INDEPENDENTLY_VERIFIED", report["provenance"]["privateSourceBodies"])
        self.assertNotIn("sourceBody", json.dumps(report))
        json_bytes = knowledge_coverage.render_json(report)
        markdown = knowledge_coverage.render_markdown(report)
        self.assertEqual(json_bytes, knowledge_coverage.render_json(knowledge_coverage.build_report(self.ledger)))
        self.assertIn("UNKNOWN", markdown)
        with tempfile.TemporaryDirectory() as output:
            learner_db = Path(output) / "learner.db"
            with sqlite3.connect(learner_db) as database:
                database.execute("CREATE TABLE learner_state (value TEXT)")
                database.execute("INSERT INTO learner_state VALUES ('unchanged')")
            learner_before = learner_db.read_bytes()
            fixture_before = knowledge_coverage.LEDGER_PATH.read_bytes()
            with patch("sys.argv", ["knowledge_coverage.py", "--output-dir", output]):
                with contextlib.redirect_stdout(io.StringIO()):
                    knowledge_coverage.main()
            self.assertEqual(fixture_before, knowledge_coverage.LEDGER_PATH.read_bytes())
            self.assertEqual(learner_before, learner_db.read_bytes())
            self.assertEqual(
                {"coverage-report.json", "coverage-report.md", "learner.db"},
                {path.name for path in Path(output).iterdir()},
            )
            self.assertEqual(json_bytes, (Path(output) / "coverage-report.json").read_text(encoding="utf-8"))
            self.assertEqual(markdown, (Path(output) / "coverage-report.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
