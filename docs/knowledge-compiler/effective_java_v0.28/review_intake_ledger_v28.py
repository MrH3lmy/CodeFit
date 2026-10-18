"""Owner-local, non-production quarantine ledger for externally signed review decisions.

The reviewer roster and SHA-256 pin MUST be supplied by an independent operator
through a trusted out-of-band channel. No trusted identities or secret keys ship
with this package. A valid signature is NOT independent review approval.

Atomic SQLite intake on one trusted local filesystem; no remote/distributed replay
claims, no automated content approvals, no mastery/rewards and no learner data.
"""
from __future__ import annotations
import hashlib
import os
import re
import sqlite3
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

from review_attestation_v23 import (
    ReviewIntakeError, canonical, sha256, source_snapshot, strict_json,
    validate_trust_registry, validate_signed_review,
)

SCHEMA = 'codefit-private-review-intake-ledger/v0.28'
SHA = re.compile(r'^[a-f0-9]{64}$')
HEX32 = re.compile(r'^[a-f0-9]{32}$')

class ReviewLedgerRejected(ReviewIntakeError):
    """Never count a failed/ambiguous review intake as approved."""
class DuplicateReview(ReviewLedgerRejected):
    """A nonce or reviewer/role/subject decision was already recorded."""


def _private_path(raw):
    if not isinstance(raw,(str,os.PathLike)):
        raise ReviewLedgerRejected('absolute owner-owned .sqlite3 path required')
    path=Path(raw)
    if not path.is_absolute() or path.suffix!='.sqlite3' or path.name in ('.','..'):
        raise ReviewLedgerRejected('absolute .sqlite3 file path required')
    if path.is_symlink() or (path.exists() and not path.is_file()):
        raise ReviewLedgerRejected('database cannot be symlink or non-file')
    parent=path.parent
    if parent.is_symlink() or not parent.is_dir():
        raise ReviewLedgerRejected('private existing parent directory required')
    meta=parent.stat()
    if meta.st_uid!=os.geteuid() or (meta.st_mode & 0o077):
        raise ReviewLedgerRejected('parent directory must be process-owned, mode 0700')
    if path.exists():
        meta=path.stat()
        if meta.st_uid!=os.geteuid() or (meta.st_mode & 0o077):
            raise ReviewLedgerRejected('database file must be private and owner-controlled')
    return path


def _connect(path):
    con=sqlite3.connect(str(path),isolation_level=None,timeout=5.0)
    con.execute('PRAGMA busy_timeout=5000')
    con.execute('PRAGMA trusted_schema=OFF')
    con.execute('PRAGMA synchronous=FULL')
    con.execute('PRAGMA journal_mode=DELETE')
    return con


def _scope_digest():
    claims,missions=source_snapshot()
    return sha256(canonical({
        'claims':{k:sha256(canonical(v)) for k,v in sorted(claims.items())},
        'missions':{k:sha256(canonical(v)) for k,v in sorted(missions.items())},
    }))


class ReviewIntakeLedger:
    def __init__(self, db_path, *, trusted_roster_bytes, pinned_roster_sha256):
        if type(trusted_roster_bytes) not in (str,bytes) or type(pinned_roster_sha256) is not str or not SHA.fullmatch(pinned_roster_sha256):
            raise ReviewLedgerRejected('trusted roster bytes and independent digest pin required')
        self._roster=trusted_roster_bytes.encode('utf-8') if type(trusted_roster_bytes) is str else trusted_roster_bytes
        if sha256(self._roster)!=pinned_roster_sha256:
            raise ReviewLedgerRejected('owner roster pin mismatch')
        try:
            validate_trust_registry(self._roster)
        except ReviewIntakeError as exc:
            raise ReviewLedgerRejected("owner roster not valid") from exc
        self._pin=pinned_roster_sha256
        self._scope=_scope_digest()
        self.path=_private_path(db_path)
        new=not self.path.exists()
        try:
            with closing(_connect(self.path)) as conn:
                if new:
                    os.chmod(self.path,0o600)
                else:
                    existing={x[0] for x in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
                    if not {'settings','reviews'}.issubset(existing):
                        raise ReviewLedgerRejected('existing schema missing; no destructive recovery')
                conn.execute('''CREATE TABLE IF NOT EXISTS settings(
                    name TEXT PRIMARY KEY, value TEXT NOT NULL)''')
                conn.execute('''CREATE TABLE IF NOT EXISTS reviews(
                    row_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    reviewer_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    subject_id TEXT NOT NULL,
                    subject_sha256 TEXT NOT NULL,
                    decision TEXT NOT NULL,
                    evidence_sha256 TEXT NOT NULL,
                    signed_envelope_sha256 TEXT NOT NULL UNIQUE,
                    nonce TEXT NOT NULL UNIQUE,
                    inserted_utc TEXT NOT NULL,
                    UNIQUE(reviewer_id,role,subject_id))''')
                conn.execute('BEGIN IMMEDIATE')
                actual=dict(conn.execute('SELECT name,value FROM settings'))
                if new and not actual:
                    conn.executemany('INSERT INTO settings(name,value) VALUES(?,?)',sorted(self._settings().items()))
                elif actual!=self._settings():
                    raise ReviewLedgerRejected('roster or source changed; separate audited migration required')
                conn.commit()
        except (sqlite3.Error,OSError) as exc:
            raise ReviewLedgerRejected('secure review ledger initialization failed') from exc
        _private_path(self.path)

    def _settings(self):
        return {'schema':SCHEMA,'roster_pin_sha256':self._pin,'source_scope_sha256':self._scope}

    def accept_batch(self, serialized_envelopes, *, received_utc=None):
        """Atomic intake; any invalid/replayed item rolls back the whole batch."""
        if type(serialized_envelopes) is not list or not 1<=len(serialized_envelopes)<=100:
            raise ReviewLedgerRejected('bounded nonempty list required')
        now=received_utc or datetime.now(timezone.utc)
        if not isinstance(now,datetime) or now.tzinfo is None or now.utcoffset() is None:
            raise ReviewLedgerRejected('timezone-aware current time required')
        _private_path(self.path)
        if not self.path.exists():
            raise ReviewLedgerRejected('ledger disappeared; refusing recreation')
        try:
            with closing(_connect(self.path)) as conn:
                conn.execute('BEGIN IMMEDIATE')
                if dict(conn.execute('SELECT name,value FROM settings'))!=self._settings():
                    raise ReviewLedgerRejected('trusted settings modified')
                result=[]
                for data in serialized_envelopes:
                    if type(data) not in (bytes,str):
                        raise ReviewLedgerRejected('serialized signed decision required')
                    verified=validate_signed_review(data,self._roster,pinned_roster_sha256=self._pin,received_utc=now)
                    raw=strict_json(data)
                    payload=raw['payload']
                    nonce=payload['nonce']
                    if type(nonce) is not str or not HEX32.fullmatch(nonce):
                        raise ReviewLedgerRejected('invalid nonce')
                    receipt_hash=sha256(canonical(raw))
                    if conn.execute('''SELECT 1 FROM reviews WHERE nonce=? OR signed_envelope_sha256=?
                        OR (reviewer_id=? AND role=? AND subject_id=?) LIMIT 1''',
                        (nonce,receipt_hash,verified['reviewer_id'],verified['reviewer_role'],verified['subject_id'])).fetchone():
                        raise DuplicateReview('review replay or conflicting decision from same reviewer')
                    conn.execute('''INSERT INTO reviews(reviewer_id,role,subject_id,subject_sha256,decision,
                        evidence_sha256,signed_envelope_sha256,nonce,inserted_utc) VALUES(?,?,?,?,?,?,?,?,?)''',
                        (verified['reviewer_id'],verified['reviewer_role'],verified['subject_id'],payload['subject_sha256'],
                         verified['decision'],verified['signed_evidence_sha256'],receipt_hash,nonce,
                         now.astimezone(timezone.utc).isoformat()))
                    result.append({'subject_id':verified['subject_id'], 'role':verified['reviewer_role'],
                                   'decision':verified['decision'], 'verification':'QUARANTINED_PENDING_OWNER_ADJUDICATION',
                                   'approved':False, 'mastery_eligible':False})
                conn.commit()
                return {'accepted_for_adjudication':tuple(result),'independent_approvals':0,
                        'learner_release':False,'mastery_eligible':False}
        except (DuplicateReview,ReviewLedgerRejected):
            raise
        except ReviewIntakeError as exc:
            raise ReviewLedgerRejected('signed review failed independent trust checks') from exc
        except sqlite3.IntegrityError as exc:
            raise DuplicateReview('transactional review uniqueness violation') from exc
        except (OSError,sqlite3.Error) as exc:
            raise ReviewLedgerRejected('review ledger unavailable; no reviews accepted') from exc

    def audit_snapshot(self):
        """No evidence text, identities, keys or submitted solutions are exported."""
        _private_path(self.path)
        if not self.path.exists(): raise ReviewLedgerRejected('ledger disappeared')
        try:
            with closing(_connect(self.path)) as conn:
                conn.execute('BEGIN')
                if dict(conn.execute('SELECT name,value FROM settings'))!=self._settings():
                    raise ReviewLedgerRejected('ledger trust metadata changed')
                rows=conn.execute('SELECT role,subject_id,decision FROM reviews ORDER BY role,subject_id').fetchall()
                conn.commit()
        except (sqlite3.Error,OSError) as exc:
            raise ReviewLedgerRejected('ledger audit unavailable') from exc
        unique_technical={r[1] for r in rows if r[0]=='technical'}
        unique_pedagogy={r[1] for r in rows if r[0]=='pedagogy'}
        groups={}
        for role,subject,decision in rows:
            groups.setdefault((role,subject),set()).add(decision)
        conflicting=sum(len(decisions)>1 for decisions in groups.values())
        return {'schema':'codefit-private-review-intake-audit/v0.28',
                'signed_review_records_received':len(rows),
                'technical_subjects_with_signed_decisions':len(unique_technical),
                'pedagogy_subjects_with_signed_decisions':len(unique_pedagogy),
                'conflicting_subjects_needing_adjudication':conflicting,
                'technical_subjects_total':28,'pedagogy_subjects_total':6,
                'owner_adjudications_completed':0,'independent_approvals':0,
                'independence_confirmed':False,'mastery_eligible':False,
                'rewards_eligible':False,'learner_release':False}
