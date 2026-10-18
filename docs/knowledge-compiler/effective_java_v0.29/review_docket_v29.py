"""Read-only, OWNER-PRIVATE reconciliation of quarantined external review decisions.

Never treats signature verification, status ACCEPT, or an owner worksheet as approval.
It creates an auditable review docket, not a learner release or an adjudicator.
The ledger is owner-local and must be verified against its separately pinned roster.
"""
from __future__ import annotations
import hashlib
import json
import re
from contextlib import closing
from datetime import datetime

from review_attestation_v23 import canonical, source_snapshot, validate_trust_registry
from review_intake_ledger_v28 import ReviewIntakeLedger, ReviewLedgerRejected, _connect, _private_path

HEX64 = re.compile(r'^[0-9a-f]{64}$')
HEX32 = re.compile(r'^[0-9a-f]{32}$')
DECISIONS = frozenset(('ACCEPT', 'REJECT', 'NEEDS_REVISION'))
ROLE_NAMES = {'technical': 'claims', 'pedagogy': 'missions'}

class DocketRejected(ReviewLedgerRejected):
    pass


def _digest(x):
    return hashlib.sha256(canonical(x)).hexdigest()


def _source_registry():
    claims, missions = source_snapshot()
    if len(claims) != 28 or len(missions) != 6:
        raise DocketRejected('review registry counts changed unexpectedly')
    return {'technical': claims, 'pedagogy': missions}


def build_owner_docket(ledger: ReviewIntakeLedger):
    """Build one bounded private manual-adjudication worksheet from verified local ledger.

    This is deliberately read-only; it cannot grant approval or modify evidence.
    Owner must use a separate authenticated workflow to adjudicate externally.
    """
    if type(ledger) is not ReviewIntakeLedger:
        raise DocketRejected('authenticated private ledger instance required')
    try:
        path = _private_path(ledger.path)
    except ReviewLedgerRejected as exc:
        raise DocketRejected("review database path no longer private") from exc
    if not path.exists():
        raise DocketRejected('review database was removed')
    roster = validate_trust_registry(ledger._roster)
    registry = _source_registry()
    try:
        with closing(_connect(path)) as conn:
            conn.execute('BEGIN')
            if dict(conn.execute('SELECT name,value FROM settings')) != ledger._settings():
                raise DocketRejected('review trust settings changed')
            rows = conn.execute('''SELECT reviewer_id,role,subject_id,subject_sha256,
                decision,evidence_sha256,signed_envelope_sha256,nonce,inserted_utc
                FROM reviews ORDER BY role,subject_id,reviewer_id''').fetchall()
            conn.commit()
    except (OSError, Exception) as exc:
        if isinstance(exc, DocketRejected):
            raise
        raise DocketRejected('untrusted, unavailable or invalid stored reviewer evidence') from exc
    if len(rows) > 500:
        raise DocketRejected('unexpected number of review records')
    by_subject = {}
    nonces=set()
    receipts=set()
    identities=set()
    for rid, role, subject_id, sh, decision, eh, receipt, nonce, timestamp in rows:
        if type(rid) is not str or rid not in roster or roster[rid][0] != role:
            raise DocketRejected('unknown reviewer or unauthorized review role')
        if role not in registry or type(subject_id) is not str or subject_id not in registry[role]:
            raise DocketRejected('subject outside registered review scope')
        if sh != _digest(registry[role][subject_id]):
            raise DocketRejected('stored subject was changed')
        if decision not in DECISIONS:
            raise DocketRejected('unsupported stored decision')
        if any(type(h) is not str or not HEX64.fullmatch(h) for h in (sh, eh, receipt)):
            raise DocketRejected('malformed persisted subject/evidence hash')
        if type(nonce) is not str or not HEX32.fullmatch(nonce):
            raise DocketRejected('malformed persisted replay nonce')
        try:
            if type(timestamp) is not str or datetime.fromisoformat(timestamp).utcoffset() is None:
                raise ValueError('naive timestamp')
        except (ValueError,TypeError) as exc:
            raise DocketRejected('invalid persisted timestamp') from exc
        identity=(rid,role,subject_id)
        if nonce in nonces or receipt in receipts or identity in identities:
            raise DocketRejected('persisted duplicate/replay')
        nonces.add(nonce);receipts.add(receipt);identities.add(identity)
        by_subject.setdefault((role,subject_id),[]).append({
            'reviewer_id':rid,'decision':decision,'evidence_sha256':eh,
            'signed_envelope_sha256':receipt,'recorded_utc':timestamp})
    subjects=[]
    for role, scope in registry.items():
        for subject_id, subject in sorted(scope.items()):
            reviews=by_subject.get((role,subject_id),[])
            decisions={x['decision'] for x in reviews}
            if not reviews:
                state='AWAITING_EXTERNAL_SIGNED_REVIEW'
            elif len(decisions)>1:
                state='CONFLICT_REQUIRES_OWNER_ARBITRATION'
            elif decisions == {'ACCEPT'}:
                state='AWAITING_OWNER_EVIDENCE_AND_INDEPENDENCE_ADJUDICATION'
            else:
                state='REVISION_OR_REJECTION_REQUIRES_AUTHOR_RESPONSE'
            subjects.append({
                'subject_id':subject_id,'role':role,'subject_sha256':_digest(subject),
                'state':state,'reviewer_decisions':reviews,
                'owner_checks_required':['independent_identity_and_conflicts',
                    'authorized_complete_source_review', 'external_evidence_digest_reproduced',
                    'claim_or_mission_correctness', 'written_owner_decision'],
                'owner_adjudicated':False,'independently_approved':False,
                'learner_release':False})
    states={state:sum(s['state']==state for s in subjects) for state in sorted({s['state'] for s in subjects})}
    private={'schema':'codefit-private-review-docket/v0.29',
      'roster_pin_sha256':ledger._pin,'source_scope_sha256':ledger._scope,
      'review_subjects':len(subjects),'signed_review_records':len(rows),
      'states':states,'subjects':subjects,
      'limitation':'The ledger stores signature/evidence digests, not signed-envelope bodies; audit does not reverify signature after intake. Owner must retain original signed envelopes securely outside this package.',
      'independent_approvals':0,'owner_adjudications':0,
      'production_grader':False,'mastery_eligible':False,'learner_release':False}
    private['docket_sha256']=_digest(private)
    return private


def public_progress(private):
    """Return strictly redacted counts, never private reviewer identities or answers."""
    if type(private) is not dict or private.get('schema')!='codefit-private-review-docket/v0.29':
        raise DocketRejected('owner docket required')
    if not isinstance(private.get('subjects'),list) or len(private['subjects']) != 34:
        raise DocketRejected('invalid docket length')
    if _digest({k:v for k,v in private.items() if k!='docket_sha256'}) != private.get('docket_sha256'):
        raise DocketRejected('docket edited after creation')
    counts=private['states']
    return {'schema':'codefit-redacted-review-progress/v0.29',
            'review_subjects':34,'technical_claims_total':28,'pedagogy_missions_total':6,
            'subjects_with_signed_decisions':sum(bool(s['reviewer_decisions']) for s in private['subjects']),
            'quarantined_signed_review_records':private['signed_review_records'],
            'awaiting_review':counts.get('AWAITING_EXTERNAL_SIGNED_REVIEW',0),
            'awaiting_owner_adjudication':counts.get('AWAITING_OWNER_EVIDENCE_AND_INDEPENDENCE_ADJUDICATION',0),
            'needing_revision_or_arbitration':counts.get('REVISION_OR_REJECTION_REQUIRES_AUTHOR_RESPONSE',0)+counts.get('CONFLICT_REQUIRES_OWNER_ARBITRATION',0),
            'independent_approvals':0,'owner_adjudications':0,
            'mastery_eligible':False,'learner_release':False,
            'privacy':'AGGREGATES_ONLY_NO_IDENTITIES_OR_EVIDENCE'}


def empty_project_baseline():
    """Readiness baseline; no synthetic reviewer identities are ever enrolled here."""
    return {'schema':'codefit-redacted-review-progress/v0.29','review_subjects':34,
            'technical_claims_total':28,'pedagogy_missions_total':6,
            'subjects_with_signed_decisions':0,'quarantined_signed_review_records':0,
            'awaiting_review':34,'awaiting_owner_adjudication':0,
            'needing_revision_or_arbitration':0,'independent_approvals':0,
            'owner_adjudications':0,'mastery_eligible':False,'learner_release':False,
            'privacy':'AGGREGATES_ONLY_NO_IDENTITIES_OR_EVIDENCE'}

if __name__=='__main__':
    print(json.dumps(empty_project_baseline(),indent=2))
