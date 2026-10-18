#!/usr/bin/env python3
"""OFFLINE evidence verifier for externally signed reviews; not a reviewer or approval authority.

Ed25519 proves possession of an externally enrolled key, not a reviewer's
independence, expertise, provenance or honesty. Enrollment is performed by
an independent owner outside this compiler; there are NO trusted keys shipped.
"""
import base64
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

ROOT = Path(__file__).resolve().parent
CLAIMS = ROOT/'output/deep_v22/claim_register_v0.22.json'
PEDAGOGY = ROOT/'output/deep_v22/blind_pedagogy_packet_v0.22.json'
MAX_JSON_BYTES = 65536
SHA256 = re.compile(r'^[0-9a-f]{64}$')
IDENT = re.compile(r'^[A-Za-z0-9][A-Za-z0-9_.-]{2,100}$')
ALLOWED_DECISIONS = {'ACCEPT', 'REJECT', 'NEEDS_REVISION'}
REVIEW_ROLES = {'technical', 'pedagogy'}

class ReviewIntakeError(ValueError):
    pass


def _no_duplicate_keys(pairs):
    out = {}
    for k, v in pairs:
        if k in out:
            raise ValueError('duplicate JSON key')
        out[k] = v
    return out


def strict_json(data):
    try:
        if type(data) not in (str, bytes):
            raise ValueError('JSON bytes/string required')
        if len(data.encode('utf-8') if type(data) is str else data) > MAX_JSON_BYTES:
            raise ValueError('oversized JSON')
        return json.loads(data, object_pairs_hook=_no_duplicate_keys,
                          parse_constant=lambda x: (_ for _ in ()).throw(ValueError('nonfinite number')))
    except (ValueError, UnicodeError, TypeError) as exc:
        raise ReviewIntakeError('invalid JSON input') from exc


def canonical(data):
    return json.dumps(data, sort_keys=True, ensure_ascii=False,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')


def b64decode(value, n):
    if type(value) is not str or len(value) > 200 or not re.fullmatch(r'[A-Za-z0-9_-]+={0,2}', value):
        raise ReviewIntakeError('invalid base64url')
    try:
        raw = base64.b64decode((value.replace('-', '+').replace('_', '/') + '=' * (-len(value) % 4)), validate=True)
    except Exception as exc:
        raise ReviewIntakeError('invalid base64url') from exc
    if len(raw) != n or base64.urlsafe_b64encode(raw).decode('ascii').rstrip('=') != value.rstrip('='):
        raise ReviewIntakeError('invalid decoded length')
    return raw


def sha256(s):
    return hashlib.sha256(s).hexdigest()


def _exact(obj, keys):
    return type(obj) is dict and set(obj) == set(keys)


def source_snapshot():
    registry = strict_json(CLAIMS.read_bytes())
    pedagogy = strict_json(PEDAGOGY.read_bytes())
    if registry.get('claim_count') != 28 or len(registry.get('claims', [])) != 28:
        raise ReviewIntakeError('unexpected claim registry')
    if len(pedagogy.get('blind_missions', [])) != 6:
        raise ReviewIntakeError('unexpected blind mission packet')
    claims = {c['claim_id']: c for c in registry['claims']}
    missions = {m['mission_id']: m for m in pedagogy['blind_missions']}
    if len(claims) != 28 or len(missions) != 6:
        raise ReviewIntakeError('duplicate source identifiers')
    return claims, missions


def validate_trust_registry(raw):
    """Registry must be acquired from the owner-controlled trusted channel, never submission."""
    reg = strict_json(raw)
    if not _exact(reg, {'schema', 'owner_verified_out_of_band', 'reviewers'}):
        raise ReviewIntakeError('invalid trusted reviewer registry')
    if reg['schema'] != 'codefit-external-reviewer-roster/v0.23' or reg['owner_verified_out_of_band'] is not True:
        raise ReviewIntakeError('unapproved trust registry')
    reviewers = reg['reviewers']
    if type(reviewers) is not list or not 1 <= len(reviewers) <= 50:
        raise ReviewIntakeError('no approved external reviewers')
    found = {}
    for r in reviewers:
        if not _exact(r, {'id', 'role', 'public_key_ed25519_base64url', 'external_identity_checked'}):
            raise ReviewIntakeError('invalid reviewer entry')
        rid, role = r['id'], r['role']
        if not isinstance(rid, str) or not IDENT.fullmatch(rid) or rid in found or role not in REVIEW_ROLES:
            raise ReviewIntakeError('invalid reviewer identity or role')
        if r['external_identity_checked'] is not True:
            raise ReviewIntakeError('reviewer lacks out-of-band confirmation')
        found[rid] = (role, b64decode(r['public_key_ed25519_base64url'], 32))
    return found


def validate_signed_review(raw, trusted_roster_bytes, *, pinned_roster_sha256, received_utc=None):
    """Check signature, scope, immutably bound source, and permissions.

    No production approval is returned. Real approvals require a separate human
    adjudicator, independent provenance and explicit owner release decision.
    """
    doc = strict_json(raw)
    if not _exact(doc, {'schema', 'payload', 'signature_ed25519_base64url'}):
        raise ReviewIntakeError('invalid signed envelope')
    if doc['schema'] != 'codefit-signed-review-envelope/v0.23':
        raise ReviewIntakeError('unsupported signed-review schema')
    payload = doc['payload']
    expected = {'schema','reviewer_id','role','subject_id','subject_sha256',
                'decision','evidence_sha256','evidence_summary','timestamp_utc','nonce'}
    if not _exact(payload, expected) or payload['schema'] != 'codefit-review-decision/v0.23':
        raise ReviewIntakeError('invalid decision payload')
    if type(pinned_roster_sha256) is not str or not SHA256.fullmatch(pinned_roster_sha256) or sha256(trusted_roster_bytes.encode("utf-8") if type(trusted_roster_bytes) is str else trusted_roster_bytes) != pinned_roster_sha256:
        raise ReviewIntakeError("trusted roster digest must match separate owner-controlled pin")
    roster = validate_trust_registry(trusted_roster_bytes)
    reviewer_id = payload['reviewer_id']
    if type(reviewer_id) is not str or reviewer_id not in roster:
        raise ReviewIntakeError('reviewer not independently enrolled')
    role, public_key = roster[reviewer_id]
    if payload['role'] != role:
        raise ReviewIntakeError('role mismatch')
    if payload['decision'] not in ALLOWED_DECISIONS:
        raise ReviewIntakeError('unknown decision')
    if any(type(payload[k]) is not str or not SHA256.fullmatch(payload[k])
           for k in ('subject_sha256','evidence_sha256')):
        raise ReviewIntakeError('invalid evidence digest')
    if type(payload['evidence_summary']) is not str or not 80 <= len(payload['evidence_summary']) <= 4000:
        raise ReviewIntakeError('insufficient reviewer evidence description')
    if type(payload['nonce']) is not str or not re.fullmatch(r'[0-9a-f]{32}', payload['nonce']):
        raise ReviewIntakeError('invalid replay nonce')
    if type(payload['timestamp_utc']) is not str or not re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ', payload['timestamp_utc']):
        raise ReviewIntakeError('invalid UTC timestamp')
    try:
        instant = datetime.fromisoformat(payload['timestamp_utc'].replace('Z','+00:00'))
    except ValueError as exc:
        raise ReviewIntakeError('invalid UTC timestamp') from exc
    now = received_utc or datetime.now(timezone.utc)
    if instant > now or (now - instant).days > 90:
        raise ReviewIntakeError('future or stale review timestamp')
    claims, missions = source_snapshot()
    subject_id = payload['subject_id']
    if role == 'technical':
        if type(subject_id) is not str or subject_id not in claims:
            raise ReviewIntakeError('technical claim outside registered scope')
        subject = claims[subject_id]
    else:
        if type(subject_id) is not str or subject_id not in missions:
            raise ReviewIntakeError('pedagogy review outside blind mission scope')
        subject = missions[subject_id]
    # Bind signed decision to the exact published original claim/prompt, not
    # an unsigned reviewer text field or the entire changeable registry.
    if sha256(canonical(subject)) != payload['subject_sha256']:
        raise ReviewIntakeError('subject content changed since review')
    try:
        Ed25519PublicKey.from_public_bytes(public_key).verify(
            b64decode(doc['signature_ed25519_base64url'],64),
            b'CODEFIT_REVIEW_DECISION_V23\x00' + canonical(payload))
    except (InvalidSignature, ValueError) as exc:
        raise ReviewIntakeError('signature mismatch') from exc
    return {'schema':'codefit-verified-review-intake/v0.23',
            'subject_id':subject_id,'reviewer_id':reviewer_id,'reviewer_role':role,
            'decision':payload['decision'],'signed_evidence_sha256':payload['evidence_sha256'],
            'verification_status':'CRYPTOGRAPHICALLY_VALID__REQUIRES_HUMAN_ADJUDICATION',
            'approved':False,'independent_review_confirmed':False,
            'mastery_eligible':False,'learner_release':False}


def verify_batch(envelopes, trusted_roster_bytes, *, pinned_roster_sha256, received_utc=None):
    if type(envelopes) is not list or len(envelopes) > 100:
        raise ReviewIntakeError('invalid review batch')
    seen_subject_role = set()
    seen_nonce = set()
    verified=[]
    for entry in envelopes:
        if type(entry) not in (str, bytes):
            raise ReviewIntakeError('must pass serialized envelopes')
        decoded = strict_json(entry)
        record = validate_signed_review(entry,trusted_roster_bytes,pinned_roster_sha256=pinned_roster_sha256,received_utc=received_utc)
        nonce=decoded['payload']['nonce']
        key=(record['subject_id'],record['reviewer_role'],record['reviewer_id'])
        if key in seen_subject_role or nonce in seen_nonce:
            raise ReviewIntakeError('duplicate subject/reviewer or replay nonce')
        seen_subject_role.add(key);seen_nonce.add(nonce)
        verified.append(record)
    return {'verified_intakes':verified, 'independent_approvals':0,
            'learner_release':False, 'needs_manual_provenance_and_adjudication':True}


def empty_baseline():
    # Current project does not have an externally enrolled trusted reviewer.
    return {'schema':'codefit-review-intake-baseline/v0.23',
            'independently_enrolled_external_reviewers':0,
            'externally_signed_decisions_received':0,
            'independently_approved_claims':0,
            'independently_approved_missions':0,
            'production_graders':0, 'mastery_eligible':False,
            'status':'AWAIT_EXTERNAL_REVIEWER_ENROLLMENT'}

if __name__=='__main__':
    print(json.dumps(empty_baseline(),indent=2))
