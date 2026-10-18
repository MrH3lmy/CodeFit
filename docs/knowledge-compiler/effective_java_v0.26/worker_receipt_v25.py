"""Non-production authenticated worker receipt *intake*, not code execution or grading.

An Ed25519 signature authenticates a receipt relative to a *separately pinned*
external key. It does not prove sandbox isolation, security review, pedagogical
accuracy or learner mastery. No trusted key, signed receipt, or learner source is
shipped here. The only possible successful state is QUARANTINED_NOT_APPROVED.
"""
from __future__ import annotations
import base64
import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone, timedelta
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from grader_boundary_v24 import POLICY, PreparedAttempt, canonical

SHA = re.compile(r'^[0-9a-f]{64}$')
HEXID = re.compile(r'^[0-9a-f]{32}$')
WORKER = re.compile(r'^[a-z][a-z0-9_-]{2,39}$')
TS = re.compile(r'^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$')
STATUS = frozenset({'COMPILE_ERROR', 'TESTS_FAILED', 'TESTS_PASSED', 'WALL_TIMEOUT', 'CPU_LIMIT', 'MEMORY_LIMIT', 'INFRA_FAILURE'})
MAX_RECEIPT = 8192
POLICY_HASH = hashlib.sha256(canonical(POLICY)).hexdigest()
DOMAIN = b'CODEFIT_WORKER_RECEIPT_V25\x00'

class ReceiptRejected(ValueError): pass

def _pairs(pairs):
    d = {}
    for k,v in pairs:
        if k in d: raise ValueError('duplicate key')
        d[k] = v
    return d

def parse(raw):
    try:
        if type(raw) not in (str,bytes): raise ValueError('serialized JSON only')
        if len(raw if type(raw) is bytes else raw.encode('utf-8')) > MAX_RECEIPT: raise ValueError('receipt too large')
        return json.loads(raw,object_pairs_hook=_pairs,parse_constant=lambda x: (_ for _ in ()).throw(ValueError('nonfinite')))
    except (ValueError,UnicodeError,TypeError) as exc: raise ReceiptRejected('invalid receipt JSON') from exc

def exactly(v, names): return type(v) is dict and set(v) == set(names)

def b64(data, size):
    if type(data) is not str or not re.fullmatch(r'[A-Za-z0-9_-]+',data): raise ReceiptRejected('invalid signature encoding')
    try: out=base64.urlsafe_b64decode(data + '='*((-len(data))%4))
    except Exception as exc: raise ReceiptRejected('invalid signature encoding') from exc
    if len(out)!=size or base64.urlsafe_b64encode(out).decode().rstrip('=')!=data: raise ReceiptRejected('noncanonical signature')
    return out

def valid_timestamp(value,now):
    if type(value) is not str or not TS.fullmatch(value):raise ReceiptRejected('invalid time')
    try: instant=datetime.fromisoformat(value.replace('Z','+00:00'))
    except ValueError as exc: raise ReceiptRejected('invalid time') from exc
    if now.tzinfo is None: raise ReceiptRejected('timezone-aware clock required')
    delta=now.astimezone(timezone.utc)-instant
    if delta < timedelta(seconds=-30) or delta>timedelta(minutes=5): raise ReceiptRejected('receipt expired or future-dated')

@dataclass(frozen=True)
class QuarantinedReceipt:
    receipt_hash: str
    worker_id: str
    attempt_id: str
    work_order_sha256: str
    worker_claim: str
    status: str = 'CRYPTOGRAPHICALLY_VERIFIED_QUARANTINED'
    isolation_approved: bool = False
    production_grader: bool = False
    mastery_eligible: bool = False
    rewards_eligible: bool = False
    learner_release: bool = False

def verify_receipt(raw, prepared:PreparedAttempt, *, worker_public_key:bytes,
                   pinned_worker_key_sha256:str, expected_worker_id:str,
                   received_utc=None, previously_seen_nonces=frozenset()):
    """Caller MUST supply key/pin from an owner-controlled external channel.

    `previously_seen_nonces` is caller-managed persisted replay state; this function
    never claims replay protection between runs without that durable state.
    """
    if type(prepared) is not PreparedAttempt or prepared.status!='VALIDATED_NOT_EXECUTED': raise ReceiptRejected('invalid prepared attempt')
    if type(worker_public_key) is not bytes or len(worker_public_key)!=32: raise ReceiptRejected('invalid external worker key')
    if type(pinned_worker_key_sha256) is not str or not SHA.fullmatch(pinned_worker_key_sha256) or hashlib.sha256(worker_public_key).hexdigest()!=pinned_worker_key_sha256:
        raise ReceiptRejected('worker trust anchor not independently pinned')
    if type(expected_worker_id) is not str or not WORKER.fullmatch(expected_worker_id): raise ReceiptRejected('invalid worker identifier')
    if not isinstance(previously_seen_nonces,(set,frozenset)) or not all(type(n) is str and HEXID.fullmatch(n) for n in previously_seen_nonces):
        raise ReceiptRejected('invalid replay state')
    doc=parse(raw)
    if not exactly(doc,('schema','payload','signature_ed25519_base64url')) or doc['schema']!='codefit-signed-worker-receipt/v0.25':
        raise ReceiptRejected('invalid receipt envelope')
    p=doc['payload']
    keys=('schema','worker_id','attempt_id','mission_id','work_order_sha256','policy_sha256',
          'nonce','timestamp_utc','status','compile_exit_code','tests_total','tests_passed',
          'wall_ms','cpu_ms','peak_memory_mib','stdout_bytes','stderr_bytes','stdout_sha256','stderr_sha256')
    if not exactly(p,keys) or p['schema']!='codefit-worker-verdict/v0.25': raise ReceiptRejected('unknown receipt payload schema')
    if p['worker_id']!=expected_worker_id or p['attempt_id']!=prepared.attempt_id or p['mission_id']!=prepared.mission_id or p['work_order_sha256']!=prepared.work_order_sha256 or p['policy_sha256']!=POLICY_HASH:
        raise ReceiptRejected('receipt not bound to exact work order and policy')
    if type(p['nonce']) is not str or not HEXID.fullmatch(p['nonce']) or p['nonce'] in previously_seen_nonces:
        raise ReceiptRejected('invalid or replayed nonce')
    valid_timestamp(p['timestamp_utc'],received_utc or datetime.now(timezone.utc))
    status=p['status']
    if type(status) is not str or status not in STATUS:raise ReceiptRejected('invalid worker verdict')
    for field,limit in [('wall_ms',POLICY['wall_ms']),('cpu_ms',POLICY['cpu_ms']),('peak_memory_mib',POLICY['memory_mib']),('stdout_bytes',POLICY['max_stdout_bytes']),('stderr_bytes',POLICY['max_stderr_bytes'])]:
        if type(p[field]) is not int or not 0 <= p[field] <= limit: raise ReceiptRejected('unbounded execution measurement')
    for field in ('tests_total','tests_passed'):
        if type(p[field]) is not int or not 0 <= p[field] <= 1000: raise ReceiptRejected('invalid test counts')
    if p['tests_passed']>p['tests_total']: raise ReceiptRejected('passing tests exceed total')
    if type(p['compile_exit_code']) is not int or not -1 <= p['compile_exit_code'] <= 255:raise ReceiptRejected('invalid compiler exit')
    for field in ('stdout_sha256','stderr_sha256'):
        if type(p[field]) is not str or not SHA.fullmatch(p[field]):raise ReceiptRejected('invalid output digest')
    if status=='TESTS_PASSED' and (p['compile_exit_code']!=0 or p['tests_total']==0 or p['tests_total']!=p['tests_passed']):
        raise ReceiptRejected('inconsistent claimed success')
    if status=='TESTS_FAILED' and (p['compile_exit_code']!=0 or p['tests_total']==0 or p['tests_passed']>=p['tests_total']):
        raise ReceiptRejected('inconsistent claimed test failure')
    if status=='COMPILE_ERROR' and (p['compile_exit_code']<=0 or p['tests_total']!=0 or p['tests_passed']!=0):
        raise ReceiptRejected('inconsistent claimed compile failure')
    if status in ('WALL_TIMEOUT','CPU_LIMIT','MEMORY_LIMIT','INFRA_FAILURE') and (p['tests_total']!=0 or p['tests_passed']!=0):
        raise ReceiptRejected('indeterminate work cannot report tests')
    sig=b64(doc['signature_ed25519_base64url'],64)
    try: Ed25519PublicKey.from_public_bytes(worker_public_key).verify(sig,DOMAIN+canonical(p))
    except (ValueError,InvalidSignature) as exc: raise ReceiptRejected('signature invalid') from exc
    # Digest has no learner sources, output body, hidden tests or private key.
    return QuarantinedReceipt(hashlib.sha256(canonical(doc)).hexdigest(),p['worker_id'],p['attempt_id'],p['work_order_sha256'],status)

def verify_batch(receipts, prepared, *,worker_public_key,pinned_worker_key_sha256,expected_worker_id,received_utc=None,previously_seen_nonces=frozenset()):
    if type(receipts) is not list or len(receipts)>32:raise ReceiptRejected('invalid batch')
    seen=set(previously_seen_nonces)
    out=[]
    for raw in receipts:
        doc=parse(raw)
        p=doc.get('payload') if type(doc) is dict else None
        nonce=p.get('nonce') if type(p) is dict else None
        record=verify_receipt(raw,prepared,worker_public_key=worker_public_key,
          pinned_worker_key_sha256=pinned_worker_key_sha256,expected_worker_id=expected_worker_id,
          received_utc=received_utc,previously_seen_nonces=seen)
        seen.add(nonce)
        out.append(record)
    # This is NOT a production persistent replay database.
    return {'verified_quarantined':tuple(out),'nonces_to_persist':frozenset(seen),'approvals':0,'mastery_eligible':False,'learner_release':False}
