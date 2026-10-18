"""Prototype, owner-local, durable QUARANTINE ledger for signed worker receipts.

This component NEVER runs Java, grades learners, or authorizes release. It only
atomically prevents duplicate receipt/nonce/attempt intake across concurrent
processes and restarts on one trusted local filesystem. An external operator
must supply an independently enrolled worker identity/key and control the
private ledger directory. No publisher files or keys are stored here.

NOT a production service: SQLite filesystem security, backups, HA, rate limits,
trust enrollment, auditing, retention, recovery and operator review are out of
scope. No distributed replay guarantees across separate ledger databases.
"""
from __future__ import annotations

import hashlib
import os
import re
import sqlite3
from datetime import datetime, timezone
from contextlib import closing
from pathlib import Path

from grader_boundary_v24 import PreparedAttempt
from worker_receipt_v25 import (
    POLICY_HASH, ReceiptRejected, QuarantinedReceipt, SHA, WORKER,
    parse, verify_receipt,
)

SCHEMA = 'codefit-private-quarantined-receipt-ledger/v0.26'
HEX32 = re.compile(r'^[0-9a-f]{32}$')

class LedgerRejected(ReceiptRejected):
    """The durable local intake was refused; caller MUST NOT treat as success."""

class DuplicateReceipt(LedgerRejected):
    """Replay nonce or reused attempt/work-order was seen previously."""


def _trusted_path(db_path):
    if not isinstance(db_path, (str, os.PathLike)):
        raise LedgerRejected('operator-owned absolute ledger path required')
    path = Path(db_path)
    if not path.is_absolute() or path.name in ('', '.', '..') or path.suffix != '.sqlite3':
        raise LedgerRejected('operator-owned absolute .sqlite3 file required')
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise LedgerRejected('ledger file must not be a link or non-file')
    parent = path.parent
    if parent.is_symlink() or not parent.is_dir():
        raise LedgerRejected('private directory must exist without symlink')
    st = parent.stat()
    if st.st_uid != os.geteuid() or st.st_mode & 0o077:
        raise LedgerRejected('ledger directory must be owned by process and mode 0700')
    if path.exists():
        stat = path.stat()
        if stat.st_uid != os.geteuid() or stat.st_mode & 0o077:
            raise LedgerRejected('ledger file must be private and operator-owned')
    return path


def _connect(path):
    # Separate per-operation connections; SQLite serializes BEGIN IMMEDIATE
    # writers across local processes. No untrusted extension loading.
    conn = sqlite3.connect(str(path), timeout=5.0, isolation_level=None)
    conn.execute('PRAGMA busy_timeout=5000')
    conn.execute('PRAGMA trusted_schema=OFF')
    conn.execute('PRAGMA synchronous=FULL')
    conn.execute('PRAGMA journal_mode=DELETE')
    return conn


class ReceiptLedger:
    def __init__(self, db_path, *, worker_public_key: bytes,
                 pinned_worker_key_sha256: str, expected_worker_id: str):
        if (type(worker_public_key) is not bytes or len(worker_public_key) != 32 or
                type(pinned_worker_key_sha256) is not str or
                not SHA.fullmatch(pinned_worker_key_sha256) or
                hashlib.sha256(worker_public_key).hexdigest() != pinned_worker_key_sha256 or
                type(expected_worker_id) is not str or not WORKER.fullmatch(expected_worker_id)):
            raise LedgerRejected('invalid externally controlled worker trust configuration')
        self.path = _trusted_path(db_path)
        self._key = worker_public_key
        self._pin = pinned_worker_key_sha256
        self._worker = expected_worker_id
        initially_absent = not self.path.exists()
        try:
            with closing(_connect(self.path)) as conn:
                if not initially_absent:
                    present = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                    if not {'settings','receipts'}.issubset(present):
                        raise LedgerRejected('existing ledger missing required tables; never reset')
                if initially_absent:
                    os.chmod(self.path, 0o600)
                conn.execute('''CREATE TABLE IF NOT EXISTS settings (
                    name TEXT PRIMARY KEY, value TEXT NOT NULL)''')
                conn.execute('''CREATE TABLE IF NOT EXISTS receipts (
                    row_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    worker_id TEXT NOT NULL,
                    nonce TEXT NOT NULL UNIQUE,
                    attempt_id TEXT NOT NULL UNIQUE,
                    work_order_sha256 TEXT NOT NULL UNIQUE,
                    receipt_sha256 TEXT NOT NULL UNIQUE,
                    worker_claim TEXT NOT NULL,
                    inserted_utc TEXT NOT NULL)''')
                conn.execute('BEGIN IMMEDIATE')
                settings = dict(conn.execute('SELECT name,value FROM settings'))
                expected = {
                    'schema': SCHEMA,
                    'worker_id': self._worker,
                    'worker_pin_sha256': self._pin,
                    'policy_sha256': POLICY_HASH,
                }
                if not settings and initially_absent:
                    conn.executemany('INSERT INTO settings(name,value) VALUES (?,?)', sorted(expected.items()))
                elif settings != expected:
                    raise LedgerRejected('ledger schema/trust/policy mismatch; rotation requires separate audited migration')
                conn.commit()
        except (sqlite3.Error, OSError) as exc:
            raise LedgerRejected('durable ledger initialization failed') from exc
        # Reject insecure existing file or an unexpected path replacement.
        _trusted_path(self.path)

    def _expected_settings(self):
        return {'schema':SCHEMA,'worker_id':self._worker,
                'worker_pin_sha256':self._pin,'policy_sha256':POLICY_HASH}

    def accept(self, signed_receipt: str | bytes, prepared: PreparedAttempt, *, received_utc=None):
        """Atomically insert an authenticated receipt as QUARANTINED only.

        Duplicates (even otherwise valid signatures) are refused; no idempotent
        success is returned, protecting callers from replay-induced advancement.
        """
        try:
            if type(prepared) is not PreparedAttempt:
                raise LedgerRejected('prepared attempt must come from validated boundary')
            payload = parse(signed_receipt)
            inner = payload.get('payload') if type(payload) is dict else None
            nonce = inner.get('nonce') if type(inner) is dict else None
            if type(nonce) is not str or not HEX32.fullmatch(nonce):
                raise LedgerRejected('invalid replay nonce')
            _trusted_path(self.path)
            if not self.path.exists():
                raise LedgerRejected("ledger file disappeared; refuse to recreate")
            with closing(_connect(self.path)) as conn:
                conn.execute('BEGIN IMMEDIATE')
                if dict(conn.execute('SELECT name,value FROM settings')) != self._expected_settings():
                    raise LedgerRejected('trusted ledger settings changed; refuse intake')
                if conn.execute('SELECT 1 FROM receipts WHERE nonce=? OR attempt_id=? OR work_order_sha256=? LIMIT 1',
                                (nonce, prepared.attempt_id, prepared.work_order_sha256)).fetchone():
                    raise DuplicateReceipt('duplicate receipt nonce, attempt, or work order')
                verified = verify_receipt(
                    signed_receipt, prepared,
                    worker_public_key=self._key,
                    pinned_worker_key_sha256=self._pin,
                    expected_worker_id=self._worker,
                    received_utc=received_utc,
                )
                now = received_utc or datetime.now(timezone.utc)
                if now.tzinfo is None:
                    raise LedgerRejected('timezone-aware intake time required')
                conn.execute('''INSERT INTO receipts
                    (worker_id,nonce,attempt_id,work_order_sha256,receipt_sha256,worker_claim,inserted_utc)
                    VALUES (?,?,?,?,?,?,?)''',
                    (verified.worker_id, nonce, verified.attempt_id, verified.work_order_sha256,
                     verified.receipt_hash, verified.worker_claim, now.astimezone(timezone.utc).isoformat()))
                conn.commit()
                return verified
        except (DuplicateReceipt, LedgerRejected):
            raise
        except ReceiptRejected as exc:
            raise LedgerRejected('worker receipt cryptographic or protocol verification failed') from exc
        except sqlite3.IntegrityError as exc:
            raise DuplicateReceipt('atomic uniqueness conflict') from exc
        except (sqlite3.Error, OSError) as exc:
            raise LedgerRejected('durable receipt store unavailable; no result accepted') from exc

    def audit_snapshot(self):
        """Returns counts and release gates only; never stored learner code."""
        try:
            _trusted_path(self.path)
            if not self.path.exists():
                raise LedgerRejected("ledger file disappeared; refuse to recreate")
            with closing(_connect(self.path)) as conn:
                conn.execute('BEGIN')
                settings = dict(conn.execute('SELECT name,value FROM settings'))
                if settings != self._expected_settings():
                    raise LedgerRejected('ledger trust metadata changed')
                count = conn.execute('SELECT COUNT(*) FROM receipts').fetchone()[0]
                conn.commit()
            return {'schema':'codefit-receipt-ledger-snapshot/v0.26',
                    'quarantined_receipt_count':count, 'worker_claims_trusted':False,
                    'production_grader':False, 'independent_security_review':False,
                    'learner_release':False, 'mastery_eligible':False,
                    'rewards_eligible':False}
        except LedgerRejected:
            raise
        except (sqlite3.Error, OSError) as exc:
            raise LedgerRejected('ledger inspection failed') from exc
