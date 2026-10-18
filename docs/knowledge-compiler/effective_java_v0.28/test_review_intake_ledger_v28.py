"""Synthetic reviewer signatures exercise private quarantine only; zero independent approvals."""
import base64, hashlib, json, os, sqlite3, tempfile, unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
from pathlib import Path
from unittest.mock import patch
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from review_attestation_v23 import canonical, sha256, source_snapshot
from review_intake_ledger_v28 import ReviewIntakeLedger, ReviewLedgerRejected, DuplicateReview

NOW=datetime(2026,10,18,14,0,0,tzinfo=timezone.utc)
def b64(v): return base64.urlsafe_b64encode(v).decode('ascii').rstrip('=')

class V28ReviewLedger(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='codefit-review-v28-')
        self.addCleanup(self.temp.cleanup)
        self.db=Path(self.temp.name)/'reviews.sqlite3'
        self.tech=Ed25519PrivateKey.generate()
        self.ped=Ed25519PrivateKey.generate()
        self.roster={'schema':'codefit-external-reviewer-roster/v0.23','owner_verified_out_of_band':True,
           'reviewers':[{'id':rid,'role':role,'external_identity_checked':True,'public_key_ed25519_base64url':b64(key.public_key().public_bytes(serialization.Encoding.Raw,serialization.PublicFormat.Raw))}
                        for rid,role,key in [('synthetic-technical','technical',self.tech),('synthetic-pedagogy','pedagogy',self.ped)]]}
        self.roster_bytes=canonical(self.roster)
        self.pin=sha256(self.roster_bytes)
        self.claims,self.missions=source_snapshot()
        self.ledger=self.open()

    def open(self, db=None,roster=None,pin=None):
        return ReviewIntakeLedger(db or self.db,trusted_roster_bytes=self.roster_bytes if roster is None else roster,
                                 pinned_roster_sha256=self.pin if pin is None else pin)
    def signed(self, role='technical',subject=None,nonce='a'*32,decision='ACCEPT',key=None):
        scope=self.claims if role=='technical' else self.missions
        subject=subject or sorted(scope)[0]
        private=key or (self.tech if role=='technical' else self.ped)
        p={'schema':'codefit-review-decision/v0.23',
           'reviewer_id':'synthetic-technical' if role=='technical' else 'synthetic-pedagogy',
           'role':role,'subject_id':subject,'subject_sha256':sha256(canonical(scope[subject])),
           'decision':decision,'evidence_sha256':'5'*64,
           'evidence_summary':'Synthetic only: a reviewer would have to inspect independent evidence and reproduce tests before any human adjudicator considers this decision.',
           'timestamp_utc':'2026-10-18T13:59:00Z','nonce':nonce}
        return canonical({'schema':'codefit-signed-review-envelope/v0.23','payload':p,
                          'signature_ed25519_base64url':b64(private.sign(b'CODEFIT_REVIEW_DECISION_V23\x00'+canonical(p)))})

    def test_empty_baseline_zero_approvals(self):
        result=self.ledger.audit_snapshot()
        self.assertEqual(result['signed_review_records_received'],0)
        self.assertEqual(result['independent_approvals'],0)
        self.assertFalse(result['learner_release'])
    def test_verified_synthetic_decision_remains_quarantined(self):
        out=self.ledger.accept_batch([self.signed()],received_utc=NOW)
        self.assertEqual(out['accepted_for_adjudication'][0]['decision'],'ACCEPT')
        self.assertFalse(out['accepted_for_adjudication'][0]['approved'])
        self.assertEqual(self.ledger.audit_snapshot()['technical_subjects_with_signed_decisions'],1)
    def test_signed_rejection_not_mastery(self):
        out=self.ledger.accept_batch([self.signed(decision='REJECT')],received_utc=NOW)
        self.assertFalse(out['mastery_eligible'])
    def test_pedagogy_role_separate_scope(self):
        self.ledger.accept_batch([self.signed(role='pedagogy')],received_utc=NOW)
        self.assertEqual(self.ledger.audit_snapshot()['pedagogy_subjects_with_signed_decisions'],1)
    def test_valid_two_role_batch(self):
        self.ledger.accept_batch([self.signed(),self.signed(role='pedagogy',nonce='b'*32)],received_utc=NOW)
        self.assertEqual(self.ledger.audit_snapshot()['signed_review_records_received'],2)
    def test_restart_persistence(self):
        self.ledger.accept_batch([self.signed()],received_utc=NOW)
        reopened=self.open()
        self.assertEqual(reopened.audit_snapshot()['signed_review_records_received'],1)
        with self.assertRaises(DuplicateReview):reopened.accept_batch([self.signed()],received_utc=NOW)
    def test_nonce_replay_different_subject_blocked(self):
        self.ledger.accept_batch([self.signed()],received_utc=NOW)
        with self.assertRaises(DuplicateReview):
            self.ledger.accept_batch([self.signed(subject=sorted(self.claims)[1])],received_utc=NOW)
    def test_same_subject_redecide_blocked(self):
        self.ledger.accept_batch([self.signed()],received_utc=NOW)
        with self.assertRaises(DuplicateReview):
            self.ledger.accept_batch([self.signed(nonce='b'*32,decision='REJECT')],received_utc=NOW)
    def test_invalid_second_item_rolls_back_first(self):
        wrong=self.signed(role='pedagogy',nonce='b'*32,key=self.tech)
        with self.assertRaises(ReviewLedgerRejected):
            self.ledger.accept_batch([self.signed(),wrong],received_utc=NOW)
        self.assertEqual(self.ledger.audit_snapshot()['signed_review_records_received'],0)
    def test_valid_one_plus_invalid_duplicate_atomic(self):
        doc=self.signed()
        with self.assertRaises(DuplicateReview):self.ledger.accept_batch([doc,doc],received_utc=NOW)
        self.assertEqual(self.ledger.audit_snapshot()['signed_review_records_received'],0)
    def test_tampered_signature_refused(self):
        d=json.loads(self.signed());d['payload']['decision']='REJECT'
        with self.assertRaises(ReviewLedgerRejected):self.ledger.accept_batch([canonical(d)],received_utc=NOW)
    def test_role_scope_violation_rejected(self):
        x=self.signed(role='technical')
        d=json.loads(x);d['payload']['role']='pedagogy'
        with self.assertRaises(ReviewLedgerRejected):self.ledger.accept_batch([canonical(d)],received_utc=NOW)
    def test_unsupported_roster_pin_rejected(self):
        with self.assertRaises(ReviewLedgerRejected):self.open(pin='0'*64)
    def test_roster_changed_on_restart_refused(self):
        new=canonical({'schema':'codefit-external-reviewer-roster/v0.23','owner_verified_out_of_band':True,
                       'reviewers':[self.roster['reviewers'][0]]})
        with self.assertRaises(ReviewLedgerRejected):self.open(roster=new,pin=sha256(new))
    def test_unverified_roster_refused(self):
        bad=canonical({**self.roster,'owner_verified_out_of_band':False})
        with self.assertRaises(ReviewLedgerRejected):self.open(roster=bad,pin=sha256(bad))
    def test_nonabsolute_path_refused(self):
        with self.assertRaises(ReviewLedgerRejected):self.open(db='relative.sqlite3')
    def test_directory_not_private_refused(self):
        p=Path(self.temp.name)/'world';p.mkdir(mode=0o755)
        with self.assertRaises(ReviewLedgerRejected):self.open(db=p/'reviews.sqlite3')
    def test_symlink_refused(self):
        link=Path(self.temp.name)/'alias.sqlite3';link.symlink_to(self.db)
        with self.assertRaises(ReviewLedgerRejected):self.open(db=link)
    def test_database_mode_private(self):
        self.assertEqual(os.stat(self.db).st_mode & 0o777,0o600)
    def test_deleted_database_cannot_be_recreated_during_accept(self):
        self.db.unlink()
        with self.assertRaises(ReviewLedgerRejected):self.ledger.accept_batch([self.signed()],received_utc=NOW)
    def test_tampered_settings_refused(self):
        con=sqlite3.connect(self.db);con.execute("UPDATE settings SET value=? WHERE name='schema'",('fake',));con.commit();con.close()
        with self.assertRaises(ReviewLedgerRejected):self.ledger.accept_batch([self.signed()],received_utc=NOW)
    def test_invalid_existing_schema_not_reinitialized(self):
        p=Path(self.temp.name)/'old.sqlite3';con=sqlite3.connect(p);con.execute('CREATE TABLE unexpected(x INT)');con.commit();con.close();os.chmod(p,0o600)
        with self.assertRaises(ReviewLedgerRejected):self.open(db=p)
        self.assertTrue(p.exists())
    def test_empty_and_oversize_batches_rejected(self):
        with self.assertRaises(ReviewLedgerRejected):self.ledger.accept_batch([],received_utc=NOW)
        with self.assertRaises(ReviewLedgerRejected):self.ledger.accept_batch([self.signed()]*101,received_utc=NOW)
    def test_naive_clock_rejected(self):
        with self.assertRaises(ReviewLedgerRejected):self.ledger.accept_batch([self.signed()],received_utc=datetime(2026,10,18,14))
    def test_wrong_type_submission_rejected(self):
        with self.assertRaises(ReviewLedgerRejected):self.ledger.accept_batch([{'payload':{}}],received_utc=NOW)
    def test_unique_subject_counts_not_approval_counts(self):
        first,second=sorted(self.claims)[:2]
        self.ledger.accept_batch([self.signed(subject=first),self.signed(subject=second,nonce='b'*32)],received_utc=NOW)
        snap=self.ledger.audit_snapshot()
        self.assertEqual(snap['technical_subjects_with_signed_decisions'],2)
        self.assertEqual(snap['independent_approvals'],0)
    def test_no_sensitive_text_exported(self):
        self.ledger.accept_batch([self.signed()],received_utc=NOW)
        s=json.dumps(self.ledger.audit_snapshot())
        self.assertNotIn('synthetic-technical',s)
        self.assertNotIn('Synthetic only',s)
        self.assertNotIn('ACCEPT',s)
    def test_two_threads_same_review_one_accepted(self):
        review=self.signed()
        def save(_):
            try:return self.open().accept_batch([review],received_utc=NOW) and True
            except DuplicateReview:return False
        with ThreadPoolExecutor(max_workers=2) as pool:
            out=list(pool.map(save,range(2)))
        self.assertEqual(sorted(out),[False,True])
        self.assertEqual(self.ledger.audit_snapshot()['signed_review_records_received'],1)
    def test_no_key_file_written(self):
        self.ledger.accept_batch([self.signed()],received_utc=NOW)
        self.assertEqual(sorted(p.name for p in Path(self.temp.name).iterdir()),['reviews.sqlite3'])

if __name__=='__main__':unittest.main()
