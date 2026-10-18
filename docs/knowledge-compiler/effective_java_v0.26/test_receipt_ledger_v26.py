"""Synthetic local SQLite intake tests. No Java or real learner code is run."""
import base64
import hashlib
import json
import multiprocessing
import os
import sqlite3
import stat
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
from grader_boundary_v24 import prepare_attempt
from worker_receipt_v25 import DOMAIN, POLICY_HASH, canonical
from receipt_ledger_v26 import ReceiptLedger, LedgerRejected, DuplicateReceipt

NOW = datetime(2026, 10, 18, 11, 0, tzinfo=timezone.utc)
EMPTY = hashlib.sha256(b'').hexdigest()
SOURCE = 'class Solution {}'


def process_accept(db, pub, pin, payload, work, gate, results):
    """Separate OS processes contend on the same SQLite UNIQUE constraints."""
    gate.wait()
    try:
        ledger = ReceiptLedger(db, worker_public_key=pub, pinned_worker_key_sha256=pin,
                               expected_worker_id='external-worker-example')
        p = prepare_attempt(work)
        ledger.accept(payload, p, received_utc=NOW)
        results.put('accepted')
    except DuplicateReceipt:
        results.put('duplicate')
    except Exception as exc:
        results.put('error:' + type(exc).__name__ + ':' + str(exc))


class ReceiptLedgerV26(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='codefit-receipts-')
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.db = self.dir/'ledger.sqlite3'
        self.sk = Ed25519PrivateKey.generate()  # Synthetic test key, never trusted externally
        self.pub = self.sk.public_key().public_bytes(serialization.Encoding.Raw,
                                                    serialization.PublicFormat.Raw)
        self.pin = hashlib.sha256(self.pub).hexdigest()
        self.ledger = self.open()
        self.work = json.dumps({'schema':'codefit-java-attempt/v0.24',
                                'attempt_id':'a'*32,
                                'mission_id':'EJ20-023-Q3',
                                'source_files':{'Solution.java':SOURCE}})
        self.prepared = prepare_attempt(self.work)

    def open(self):
        return ReceiptLedger(self.db, worker_public_key=self.pub, pinned_worker_key_sha256=self.pin,
                             expected_worker_id='external-worker-example')

    def signed(self, *, nonce='b'*32, work=None, status='TESTS_PASSED'):
        p = prepare_attempt(work or self.work)
        payload = {'schema':'codefit-worker-verdict/v0.25',
                   'worker_id':'external-worker-example',
                   'attempt_id':p.attempt_id, 'mission_id':p.mission_id,
                   'work_order_sha256':p.work_order_sha256, 'policy_sha256':POLICY_HASH,
                   'nonce':nonce, 'timestamp_utc':'2026-10-18T11:00:00Z',
                   'status':status, 'compile_exit_code':0,'tests_total':3,'tests_passed':3,
                   'wall_ms':100,'cpu_ms':90,'peak_memory_mib':12,
                   'stdout_bytes':0,'stderr_bytes':0,
                   'stdout_sha256':EMPTY,'stderr_sha256':EMPTY}
        sig = self.sk.sign(DOMAIN + canonical(payload))
        return json.dumps({'schema':'codefit-signed-worker-receipt/v0.25','payload':payload,
                           'signature_ed25519_base64url':base64.urlsafe_b64encode(sig).decode().rstrip('=')})

    def accepted(self, receipt=None, prepared=None, received_utc=NOW):
        return self.ledger.accept(receipt if receipt is not None else self.signed(), prepared if prepared is not None else self.prepared, received_utc=received_utc)

    def another(self, aid='c'*32):
        return json.dumps({'schema':'codefit-java-attempt/v0.24','attempt_id':aid,
                           'mission_id':'EJ20-023-Q3','source_files':{'Solution.java':SOURCE}})

    def test_initial_empty_snapshot(self):
        r=self.ledger.audit_snapshot()
        self.assertEqual(r['quarantined_receipt_count'],0)
        self.assertFalse(r['mastery_eligible'])

    def test_accept_quarantined_only(self):
        r=self.accepted()
        self.assertEqual(r.status,'CRYPTOGRAPHICALLY_VERIFIED_QUARANTINED')
        self.assertFalse(r.production_grader)
        self.assertFalse(r.learner_release)
        self.assertFalse(r.mastery_eligible)
        self.assertEqual(self.ledger.audit_snapshot()['quarantined_receipt_count'],1)

    def test_db_private_mode(self):
        self.assertEqual(stat.S_IMODE(self.db.stat().st_mode),0o600)

    def test_retry_rejected_same_instance(self):
        self.accepted()
        with self.assertRaises(DuplicateReceipt):self.accepted()

    def test_retry_rejected_after_reopen(self):
        self.accepted()
        self.ledger=self.open()
        with self.assertRaises(DuplicateReceipt):self.accepted()

    def test_same_attempt_different_nonce_rejected(self):
        self.accepted()
        with self.assertRaises(DuplicateReceipt):self.accepted(self.signed(nonce='c'*32))

    def test_same_nonce_different_attempt_rejected(self):
        self.accepted()
        other=self.another()
        with self.assertRaises(DuplicateReceipt):self.accepted(self.signed(work=other),prepare_attempt(other))

    def test_different_attempt_and_nonce_accepted(self):
        self.accepted()
        other=self.another()
        self.accepted(self.signed(work=other,nonce='c'*32),prepare_attempt(other))
        self.assertEqual(self.ledger.audit_snapshot()['quarantined_receipt_count'],2)

    def test_forged_signature_not_persisted(self):
        raw=json.loads(self.signed());raw['signature_ed25519_base64url']='A'*86
        with self.assertRaises(LedgerRejected): self.accepted(json.dumps(raw))
        self.assertEqual(self.ledger.audit_snapshot()['quarantined_receipt_count'],0)

    def test_mismatched_workorder_not_persisted(self):
        other=prepare_attempt(self.another())
        with self.assertRaises(LedgerRejected):self.accepted(self.signed(),other)
        self.assertEqual(self.ledger.audit_snapshot()['quarantined_receipt_count'],0)

    def test_rejected_replay_does_not_increase_count(self):
        self.accepted()
        for _ in range(3):
            with self.assertRaises(DuplicateReceipt):self.accepted()
        self.assertEqual(self.ledger.audit_snapshot()['quarantined_receipt_count'],1)

    def test_no_code_signing_key_or_receipt_in_db(self):
        self.accepted()
        raw=self.db.read_bytes()
        self.assertNotIn(SOURCE.encode(),raw)
        self.assertNotIn(self.pub,raw)
        self.assertNotIn(self.signed().encode(),raw)
        self.assertNotIn(b'signature_ed25519_base64url',raw)

    def test_key_rotation_requires_migration(self):
        other=Ed25519PrivateKey.generate().public_key().public_bytes(
            serialization.Encoding.Raw,serialization.PublicFormat.Raw)
        with self.assertRaises(LedgerRejected): ReceiptLedger(self.db,worker_public_key=other,
            pinned_worker_key_sha256=hashlib.sha256(other).hexdigest(),
            expected_worker_id='external-worker-example')

    def test_worker_id_switch_rejected(self):
        with self.assertRaises(LedgerRejected): ReceiptLedger(self.db,worker_public_key=self.pub,
            pinned_worker_key_sha256=self.pin,expected_worker_id='different-worker')

    def test_invalid_pin_rejected(self):
        with self.assertRaises(LedgerRejected):ReceiptLedger(self.db,worker_public_key=self.pub,
            pinned_worker_key_sha256='0'*64,expected_worker_id='external-worker-example')

    def test_relative_db_path_rejected(self):
        with self.assertRaises(LedgerRejected): ReceiptLedger('relative.sqlite3',worker_public_key=self.pub,
            pinned_worker_key_sha256=self.pin,expected_worker_id='external-worker-example')

    def test_nonprivate_directory_rejected(self):
        os.chmod(self.dir,0o755)
        with self.assertRaises(LedgerRejected):self.open()
        os.chmod(self.dir,0o700)

    def test_permissive_existing_db_rejected(self):
        os.chmod(self.db,0o644)
        with self.assertRaises(LedgerRejected):self.open()
        os.chmod(self.db,0o600)

    def test_symlink_db_rejected(self):
        link=self.dir/'link.sqlite3'
        link.symlink_to(self.db)
        with self.assertRaises(LedgerRejected):ReceiptLedger(link,worker_public_key=self.pub,
             pinned_worker_key_sha256=self.pin,expected_worker_id='external-worker-example')

    def test_file_disappearance_fails_closed(self):
        self.db.unlink()
        with self.assertRaises(LedgerRejected):self.accepted()

    def test_file_disappearance_snapshot_fails_closed(self):
        self.db.unlink()
        with self.assertRaises(LedgerRejected):self.ledger.audit_snapshot()

    def test_existing_empty_db_not_auto_enrolled(self):
        self.db.unlink();self.db.touch(mode=0o600)
        with self.assertRaises(LedgerRejected):self.open()

    def test_missing_trust_metadata_not_reenrolled(self):
        with sqlite3.connect(self.db) as c:c.execute('DELETE FROM settings')
        with self.assertRaises(LedgerRejected):self.open()

    def test_metadata_tamper_rejected(self):
        with sqlite3.connect(self.db) as c:c.execute("UPDATE settings SET value='fake' WHERE name='worker_pin_sha256'")
        with self.assertRaises(LedgerRejected):self.ledger.audit_snapshot()
        with self.assertRaises(LedgerRejected):self.open()

    def test_no_unexpected_ledger_schema(self):
        with sqlite3.connect(self.db) as c:
            c.execute("UPDATE settings SET value='v999' WHERE name='schema'")
        with self.assertRaises(LedgerRejected):self.open()

    def test_truncated_sqlite_fails_closed(self):
        self.db.write_bytes(b'not a sqlite database')
        with self.assertRaises(LedgerRejected):self.open()

    def test_concurrent_threads_one_wins(self):
        gate=threading.Barrier(6)
        def submit(_):
            gate.wait()
            try:
                self.accepted();return 'accepted'
            except DuplicateReceipt:return 'duplicate'
        with ThreadPoolExecutor(max_workers=6) as pool:
            result=list(pool.map(submit,range(6)))
        self.assertEqual(result.count('accepted'),1)
        self.assertEqual(result.count('duplicate'),5)
        self.assertEqual(self.ledger.audit_snapshot()['quarantined_receipt_count'],1)

    def test_concurrent_processes_one_wins(self):
        if 'spawn' not in multiprocessing.get_all_start_methods():
            self.skipTest('spawn process IPC test unavailable')
        ctx=multiprocessing.get_context('spawn')
        gate=ctx.Event();results=ctx.Queue()
        jobs=[ctx.Process(target=process_accept,args=(str(self.db),self.pub,self.pin,self.signed(),self.work,gate,results)) for _ in range(3)]
        for job in jobs:job.start()
        gate.set()
        for job in jobs:
            job.join(timeout=8)
            if job.is_alive():job.terminate();job.join(3)
            self.assertEqual(job.exitcode,0)
        observed=[results.get(timeout=2) for _ in jobs]
        self.assertEqual(observed.count('accepted'),1,observed)
        self.assertEqual(observed.count('duplicate'),2,observed)
        self.assertEqual(self.ledger.audit_snapshot()['quarantined_receipt_count'],1)

    def test_no_raw_receipt_or_source_tables(self):
        with sqlite3.connect(self.db) as c:
            fields={row[1] for row in c.execute('PRAGMA table_info(receipts)')}
        self.assertNotIn('source_files',fields)
        self.assertNotIn('raw_receipt',fields)
        self.assertNotIn('signature',fields)

    def test_verified_claim_never_becomes_grade(self):
        self.accepted()
        self.assertEqual(self.ledger.audit_snapshot()['quarantined_receipt_count'],1)
        self.assertFalse(self.ledger.audit_snapshot()['production_grader'])

    def test_invalid_datetime_rejected_atomically(self):
        with self.assertRaises(LedgerRejected):self.accepted(received_utc=datetime(2026,10,18,11))
        self.assertEqual(self.ledger.audit_snapshot()['quarantined_receipt_count'],0)

    def test_live_metadata_change_blocks_intake(self):
        with sqlite3.connect(self.db) as c:
            c.execute("UPDATE settings SET value='bogus' WHERE name='policy_sha256'")
        with self.assertRaises(LedgerRejected):self.accepted()
        with sqlite3.connect(self.db) as c:
            count=c.execute('SELECT COUNT(*) FROM receipts').fetchone()[0]
        self.assertEqual(count,0)

    def test_untrusted_prepared_type_rejected(self):
        with self.assertRaises(LedgerRejected):self.accepted(prepared={'attempt_id':'a'*32})

    def test_return_snapshot_excludes_any_digest_or_pin(self):
        r=self.ledger.audit_snapshot()
        self.assertNotIn(self.pin,str(r))
        self.assertNotIn(SOURCE,str(r))

if __name__ == '__main__':unittest.main()
