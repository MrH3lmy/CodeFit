"""Synthetic-only receipt contract tests. Never runs learner code or approves a worker."""
import base64
import copy
import hashlib
import json
import unittest
from dataclasses import FrozenInstanceError
from datetime import datetime, timezone, timedelta
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
from grader_boundary_v24 import prepare_attempt
from worker_receipt_v25 import (ReceiptRejected, POLICY_HASH,DOMAIN, canonical,
    verify_receipt,verify_batch,parse,QuarantinedReceipt)

NOW=datetime(2026,10,18,10,0,0,tzinfo=timezone.utc)
EMPTY=hashlib.sha256(b'').hexdigest()
BASE_ATTEMPT={'schema':'codefit-java-attempt/v0.24','attempt_id':'a'*32,'mission_id':'EJ20-023-Q3',
              'source_files':{'Solution.java':'class Solution {}'}}

def raw(d):return json.dumps(d,separators=(',',':'),ensure_ascii=False)

class WorkerReceiptV25(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Ephemeral synthetic signer, never enrolled or committed as a trusted worker.
        cls.sk=Ed25519PrivateKey.generate()
        cls.key=cls.sk.public_key().public_bytes(serialization.Encoding.Raw,serialization.PublicFormat.Raw)
        cls.pin=hashlib.sha256(cls.key).hexdigest()
        cls.prepared=prepare_attempt(raw(BASE_ATTEMPT))
    def payload(self):
        return {'schema':'codefit-worker-verdict/v0.25','worker_id':'synthetic-worker',
            'attempt_id':self.prepared.attempt_id,'mission_id':self.prepared.mission_id,
            'work_order_sha256':self.prepared.work_order_sha256,'policy_sha256':POLICY_HASH,
            'nonce':'b'*32,'timestamp_utc':'2026-10-18T10:00:00Z',
            'status':'TESTS_PASSED','compile_exit_code':0,'tests_total':3,'tests_passed':3,
            'wall_ms':100,'cpu_ms':90,'peak_memory_mib':12,'stdout_bytes':0,
            'stderr_bytes':0,'stdout_sha256':EMPTY,'stderr_sha256':EMPTY}
    def sign(self,payload=None):
        p=self.payload() if payload is None else payload
        signature=self.sk.sign(DOMAIN+canonical(p))
        return raw({'schema':'codefit-signed-worker-receipt/v0.25','payload':p,
                    'signature_ed25519_base64url':base64.urlsafe_b64encode(signature).decode().rstrip('=')})
    def check(self,serialized,**kwargs):
        return verify_receipt(serialized,self.prepared,worker_public_key=self.key,pinned_worker_key_sha256=self.pin,
               expected_worker_id='synthetic-worker',received_utc=NOW,**kwargs)
    def rejected(self,payload,**kwargs):
        with self.assertRaises(ReceiptRejected):self.check(self.sign(payload),**kwargs)

    def test_valid_is_quarantined_not_grading(self):
        r=self.check(self.sign())
        self.assertEqual(r.status,'CRYPTOGRAPHICALLY_VERIFIED_QUARANTINED')
        self.assertEqual(r.worker_claim,'TESTS_PASSED')
        self.assertFalse(r.isolation_approved)
        self.assertFalse(r.production_grader)
        self.assertFalse(r.mastery_eligible)
        self.assertFalse(r.rewards_eligible)
        self.assertFalse(r.learner_release)
    def test_no_source_secret_or_outputs_returned(self):
        r=self.check(self.sign());text=repr(r)
        self.assertNotIn('class Solution',text)
        self.assertNotIn('stdout_sha256',text)
        self.assertNotIn(self.pin,text)
    def test_immutable_result(self):
        with self.assertRaises(FrozenInstanceError):self.check(self.sign()).mastery_eligible=True
    def test_works_with_reordered_json_outer_keys(self):
        d=json.loads(self.sign());self.assertEqual(self.check(json.dumps(dict(reversed(list(d.items()))))).worker_claim,'TESTS_PASSED')
    def test_success_requires_all_tests(self):
        p=self.payload();p['tests_passed']=2;self.rejected(p)
    def test_success_requires_tests(self):
        p=self.payload();p['tests_total']=p['tests_passed']=0;self.rejected(p)
    def test_success_requires_compile_zero(self):
        p=self.payload();p['compile_exit_code']=1;self.rejected(p)
    def test_compile_error_status_valid_quarantined(self):
        p=self.payload();p.update(status='COMPILE_ERROR',compile_exit_code=1,tests_total=0,tests_passed=0)
        self.assertEqual(self.check(self.sign(p)).worker_claim,'COMPILE_ERROR')
    def test_tests_failed_valid_quarantined(self):
        p=self.payload();p.update(status='TESTS_FAILED',tests_passed=2)
        self.assertEqual(self.check(self.sign(p)).worker_claim,'TESTS_FAILED')
    def test_timeout_valid_quarantined(self):
        p=self.payload();p.update(status='WALL_TIMEOUT',tests_total=0,tests_passed=0,compile_exit_code=-1)
        self.assertEqual(self.check(self.sign(p)).worker_claim,'WALL_TIMEOUT')
    def test_failure_requires_unpassed_tests(self):
        p=self.payload();p['status']='TESTS_FAILED';self.rejected(p)
    def test_failure_requires_compile_zero(self):
        p=self.payload();p.update(status='TESTS_FAILED',compile_exit_code=1,tests_passed=0);self.rejected(p)
    def test_compile_error_requires_failed_compile(self):
        p=self.payload();p.update(status='COMPILE_ERROR',tests_total=0,tests_passed=0);self.rejected(p)
    def test_compile_error_cannot_report_tests(self):
        p=self.payload();p.update(status='COMPILE_ERROR',compile_exit_code=1);self.rejected(p)
    def test_infrastructure_error_cannot_report_tests(self):
        p=self.payload();p.update(status='INFRA_FAILURE');self.rejected(p)
    def test_binding_detects_different_prepared_attempt(self):
        another=prepare_attempt(raw({**BASE_ATTEMPT,'source_files':{'Solution.java':'class Other {}'}}))
        with self.assertRaises(ReceiptRejected):verify_receipt(self.sign(),another,worker_public_key=self.key,pinned_worker_key_sha256=self.pin,expected_worker_id='synthetic-worker',received_utc=NOW)
    def test_binding_does_not_accept_different_worker(self):
        with self.assertRaises(ReceiptRejected):verify_receipt(self.sign(),self.prepared,worker_public_key=self.key,pinned_worker_key_sha256=self.pin,expected_worker_id='fake',received_utc=NOW)
    def test_pin_is_required(self):
        with self.assertRaises(ReceiptRejected):verify_receipt(self.sign(),self.prepared,worker_public_key=self.key,pinned_worker_key_sha256=None,expected_worker_id='synthetic-worker',received_utc=NOW)
    def test_pin_mismatch_rejected(self):
        with self.assertRaises(ReceiptRejected):verify_receipt(self.sign(),self.prepared,worker_public_key=self.key,pinned_worker_key_sha256='0'*64,expected_worker_id='synthetic-worker',received_utc=NOW)
    def test_public_key_must_be_bytes(self):
        with self.assertRaises(ReceiptRejected):verify_receipt(self.sign(),self.prepared,worker_public_key=self.key.hex(),pinned_worker_key_sha256=self.pin,expected_worker_id='synthetic-worker',received_utc=NOW)
    def test_cross_signed_wrong_key(self):
        other=Ed25519PrivateKey.generate().public_key().public_bytes(serialization.Encoding.Raw,serialization.PublicFormat.Raw)
        with self.assertRaises(ReceiptRejected):verify_receipt(self.sign(),self.prepared,worker_public_key=other,pinned_worker_key_sha256=hashlib.sha256(other).hexdigest(),expected_worker_id='synthetic-worker',received_utc=NOW)
    def test_tamper_unsigned_payload_after_signature(self):
        d=json.loads(self.sign());d['payload']['status']='TESTS_FAILED';d['payload']['tests_passed']=2
        with self.assertRaises(ReceiptRejected):self.check(raw(d))
    def test_duplicate_envelope_fields(self):
        with self.assertRaises(ReceiptRejected):self.check('{"schema":"x","schema":"y"}')
    def test_oversize(self):
        with self.assertRaises(ReceiptRejected):self.check(' '*8193)
    def test_bad_utf8(self):
        with self.assertRaises(ReceiptRejected):self.check(b'\xff')
    def test_reject_nan(self):
        with self.assertRaises(ReceiptRejected):self.check('{"x":NaN}')
    def test_payload_must_be_dict(self):
        d=json.loads(self.sign());d['payload']=[]
        with self.assertRaises(ReceiptRejected):self.check(raw(d))
    def test_reject_wrong_signature(self):
        d=json.loads(self.sign());d['signature_ed25519_base64url']='A'*86
        with self.assertRaises(ReceiptRejected):self.check(raw(d))
    def test_reject_replay_with_prior_nonce(self):
        with self.assertRaises(ReceiptRejected):self.check(self.sign(),previously_seen_nonces={'b'*32})
    def test_batch_deduplicates_nonces(self):
        with self.assertRaises(ReceiptRejected):verify_batch([self.sign(),self.sign()],self.prepared,worker_public_key=self.key,pinned_worker_key_sha256=self.pin,expected_worker_id='synthetic-worker',received_utc=NOW)
    def test_batch_replay_state_returned_for_owner_persistence(self):
        r=verify_batch([self.sign()],self.prepared,worker_public_key=self.key,pinned_worker_key_sha256=self.pin,expected_worker_id='synthetic-worker',received_utc=NOW)
        self.assertIn('b'*32,r['nonces_to_persist']);self.assertEqual(r['approvals'],0);self.assertFalse(r['learner_release'])
    def test_batch_limit(self):
        with self.assertRaises(ReceiptRejected):verify_batch([self.sign()]*33,self.prepared,worker_public_key=self.key,pinned_worker_key_sha256=self.pin,expected_worker_id='synthetic-worker',received_utc=NOW)
    def test_stale_timestamp(self):
        p=self.payload();p['timestamp_utc']='2026-10-18T09:54:00Z';self.rejected(p)
    def test_future_timestamp(self):
        p=self.payload();p['timestamp_utc']='2026-10-18T10:01:00Z';self.rejected(p)
    def test_invalid_timestamp_day(self):
        p=self.payload();p['timestamp_utc']='2026-02-30T10:00:00Z';self.rejected(p)
    def test_clock_must_be_timezone_aware(self):
        with self.assertRaises(ReceiptRejected):verify_receipt(self.sign(),self.prepared,worker_public_key=self.key,pinned_worker_key_sha256=self.pin,expected_worker_id='synthetic-worker',received_utc=datetime(2026,10,18,10))
    def test_signature_base64_padding_canonical(self):
        d=json.loads(self.sign());d['signature_ed25519_base64url']+='=='
        with self.assertRaises(ReceiptRejected):self.check(raw(d))
    def test_policy_digest_not_user_editable(self):
        p=self.payload();p['policy_sha256']='0'*64;self.rejected(p)
    def test_mission_digest_not_user_editable(self):
        p=self.payload();p['mission_id']='EJ20-024-Q3';self.rejected(p)

# Table-driven adversarial cases: each named case is an independent unittest.
MUTATIONS={
 'worker_spoof':('worker_id','evil-worker'),
 'attempt_spoof':('attempt_id','c'*32),
 'attempt_type':('attempt_id',False),
 'schema_spoof':('schema','codefit-worker-verdict/v0.99'),
 'work_order_bad_hash':('work_order_sha256','1'*64),
 'status_unknown':('status','ALL_OK'),
 'status_null':('status',None),
 'nonce_type':('nonce',234),
 'nonce_short':('nonce','f'*31),
 'timestamp_text':('timestamp_utc','yesterday'),
 'timestamp_null':('timestamp_utc',None),
 'compile_exit_negative':('compile_exit_code',-2),
 'compile_exit_huge':('compile_exit_code',256),
 'compile_exit_boolean':('compile_exit_code',True),
 'tests_total_negative':('tests_total',-1),
 'tests_passed_negative':('tests_passed',-1),
 'tests_passed_excess':('tests_passed',4),
 'tests_total_boolean':('tests_total',True),
 'tests_total_over_limit':('tests_total',1001),
 'wall_negative':('wall_ms',-1),
 'wall_over_limit':('wall_ms',5001),
 'wall_boolean':('wall_ms',True),
 'cpu_over_limit':('cpu_ms',3001),
 'memory_over_limit':('peak_memory_mib',129),
 'stdout_over_limit':('stdout_bytes',4097),
 'stderr_over_limit':('stderr_bytes',4097),
 'stdout_bad_digest':('stdout_sha256','z'*64),
 'stderr_bad_digest':('stderr_sha256','a'*63),
 'memory_float':('peak_memory_mib',2.2),
}

def add_case(name,key,value):
    def exercise(self):
        p=self.payload();p[key]=value;self.rejected(p)
    exercise.__name__='test_reject_'+name
    setattr(WorkerReceiptV25,exercise.__name__,exercise)
for n,(k,v) in MUTATIONS.items():add_case(n,k,v)

if __name__=='__main__':unittest.main()