"""Author-only local review docket tests: synthetic reviewers are NEVER approvals."""
import copy
import json
import os
import sqlite3
import unittest
from pathlib import Path
from datetime import datetime, timezone

from tests import test_review_intake_ledger_v28 as prior
from review_docket_v29 import (DocketRejected,build_owner_docket,public_progress,
                                  empty_project_baseline,_digest)

class ReviewDocketV29(unittest.TestCase):
    def setUp(self):
        self.old=prior.V28ReviewLedger('test_empty_baseline_zero_approvals')
        self.old.setUp()
        self.addCleanup(self.old.doCleanups)
        self.ledger=self.old.ledger
        self.now=prior.NOW
    def docket(self):return build_owner_docket(self.ledger)
    def take(self,decision='ACCEPT',role='technical',subject=None,nonce='a'*32):
        data=self.old.signed(decision=decision,role=role,subject=subject,nonce=nonce)
        self.ledger.accept_batch([data],received_utc=self.now)
    def mutate(self,column,value):
        db=self.old.db
        with sqlite3.connect(db) as conn:
            conn.execute(f'UPDATE reviews SET {column}=?',(value,))

    def test_empty_34_subjects(self):
        d=self.docket();self.assertEqual(d['review_subjects'],34)
        self.assertEqual(d['states']['AWAITING_EXTERNAL_SIGNED_REVIEW'],34)
        self.assertEqual(len(d['subjects']),34)
    def test_technical_and_pedagogy_counts(self):
        d=self.docket()
        self.assertEqual(sum(x['role']=='technical' for x in d['subjects']),28)
        self.assertEqual(sum(x['role']=='pedagogy' for x in d['subjects']),6)
    def test_unreviewed_all_blocked(self):
        d=self.docket();self.assertTrue(all(not x['owner_adjudicated'] and not x['independently_approved'] for x in d['subjects']))
        self.assertFalse(d['mastery_eligible'])
    def test_signed_accept_pending_owner_not_approved(self):
        self.take();d=self.docket();p=public_progress(d)
        self.assertEqual(p['awaiting_owner_adjudication'],1)
        self.assertEqual(p['independent_approvals'],0)
        self.assertEqual(d['owner_adjudications'],0)
        self.assertFalse(d['learner_release'])
    def test_rejection_requires_revision(self):
        self.take('REJECT');self.assertEqual(public_progress(self.docket())['needing_revision_or_arbitration'],1)
    def test_needs_revision_requires_revision(self):
        self.take('NEEDS_REVISION');self.assertEqual(public_progress(self.docket())['needing_revision_or_arbitration'],1)
    def test_pedagogy_subject_separate(self):
        self.take(role='pedagogy');d=self.docket();p=public_progress(d)
        self.assertEqual(p['subjects_with_signed_decisions'],1)
        self.assertEqual(sum(x['role']=='technical' and x['reviewer_decisions']!=[] for x in d['subjects']),0)
    def test_multi_role_two_subjects(self):
        self.take();self.take(role='pedagogy',nonce='b'*32)
        p=public_progress(self.docket());self.assertEqual(p['subjects_with_signed_decisions'],2)
        self.assertEqual(p['awaiting_owner_adjudication'],2)
    def test_docket_reproducible(self):
        self.take();self.assertEqual(self.docket(),self.docket())
    def test_digest_changes_with_new_review(self):
        digest=self.docket()['docket_sha256'];self.take()
        self.assertNotEqual(self.docket()['docket_sha256'],digest)
    def test_no_private_identity_in_public_summary(self):
        self.take();d=self.docket();pub=json.dumps(public_progress(d))
        self.assertNotIn('synthetic-technical',pub)
        self.assertNotIn('evidence_sha256',pub)
        self.assertNotIn('nonce',pub)
        self.assertNotIn('source_body',pub)
        self.assertNotIn('ACCEPT',pub)
    def test_public_summary_cannot_be_rewritten(self):
        d=self.docket();d['subjects'][0]['state']='AWAITING_OWNER_EVIDENCE_AND_INDEPENDENCE_ADJUDICATION'
        with self.assertRaises(DocketRejected):public_progress(d)
    def test_requires_authenticated_ledger(self):
        with self.assertRaises(DocketRejected):build_owner_docket(object())
    def test_deleted_db_fails_closed(self):
        self.old.db.unlink()
        with self.assertRaises(DocketRejected):self.docket()
    def test_tampered_subject_sha_rejected(self):
        self.take();self.mutate('subject_sha256','a'*64)
        with self.assertRaises(DocketRejected):self.docket()
    def test_tampered_role_rejected(self):
        self.take();self.mutate('role','pedagogy')
        with self.assertRaises(DocketRejected):self.docket()
    def test_tampered_decision_rejected(self):
        self.take();self.mutate('decision','APPROVED')
        with self.assertRaises(DocketRejected):self.docket()
    def test_tampered_evidence_digest_rejected(self):
        self.take();self.mutate('evidence_sha256','xyz')
        with self.assertRaises(DocketRejected):self.docket()
    def test_tampered_signature_digest_rejected(self):
        self.take();self.mutate('signed_envelope_sha256','xyz')
        with self.assertRaises(DocketRejected):self.docket()
    def test_tampered_nonce_rejected(self):
        self.take();self.mutate('nonce','xyz')
        with self.assertRaises(DocketRejected):self.docket()
    def test_tampered_timestamp_rejected(self):
        self.take();self.mutate('inserted_utc','not-a-date')
        with self.assertRaises(DocketRejected):self.docket()
    def test_tampered_identity_rejected(self):
        self.take();self.mutate('reviewer_id','fake-identity')
        with self.assertRaises(DocketRejected):self.docket()
    def test_tampered_scope_settings_rejected(self):
        with sqlite3.connect(self.old.db) as conn:
            conn.execute("UPDATE settings SET value='fake' WHERE name='source_scope_sha256'")
        with self.assertRaises(DocketRejected):self.docket()
    def test_private_file_rejected_if_mode_public(self):
        os.chmod(self.old.db,0o644)
        with self.assertRaises(DocketRejected):self.docket()
    def test_empty_baseline_zero_reviews(self):
        p=empty_project_baseline()
        self.assertEqual(p['awaiting_review'],34)
        self.assertEqual(p['independent_approvals'],0)
        self.assertFalse(p['learner_release'])
    def test_public_progress_rejects_bare_json(self):
        with self.assertRaises(DocketRejected):public_progress({'schema':'codefit-private-review-docket/v0.29'})
    def test_malformed_stored_subject_rejected(self):
        self.take();self.mutate('subject_id','FAKE-ITEM')
        with self.assertRaises(DocketRejected):self.docket()
    def test_no_external_signatures_exist_in_project(self):
        p=public_progress(self.docket());self.assertEqual(p['quarantined_signed_review_records'],0)
    def test_owner_checks_present_not_done(self):
        self.take();d=self.docket();s=[x for x in d['subjects'] if x['reviewer_decisions']][0]
        self.assertIn('independent_identity_and_conflicts',s['owner_checks_required'])
        self.assertIn('written_owner_decision',s['owner_checks_required'])
        self.assertFalse(s['owner_adjudicated'])

if __name__=='__main__':unittest.main()
