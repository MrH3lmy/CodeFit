"""Security boundary protocol tests; never launch submitted Java."""
import copy
import json
import unittest
from unittest.mock import patch
from dataclasses import FrozenInstanceError
from grader_boundary_v24 import (InvalidAttempt, IsolationUnavailable, POLICY,
    canonical, prepare_attempt, execute_attempt, inspect_runtime, MAX_FILE_BYTES)

BASE={'schema':'codefit-java-attempt/v0.24','attempt_id':'a'*32,
      'mission_id':'EJ20-023-Q3','source_files':{'Solution.java':'class Solution {}'}}
def encoded(o):return json.dumps(o,ensure_ascii=False)

class GraderBoundaryV24(unittest.TestCase):
    def attempt(self,**changes):return encoded({**copy.deepcopy(BASE),**changes})
    def bad(self,p):
        with self.assertRaises(InvalidAttempt):prepare_attempt(p)
    def test_valid_prepared_without_execution(self):
        p=prepare_attempt(self.attempt())
        self.assertEqual(p.status,'VALIDATED_NOT_EXECUTED')
        self.assertEqual(p.source_bytes,len('class Solution {}'))
        self.assertEqual(p.source_file_count,1)
        self.assertFalse(p.mastery_eligible);self.assertFalse(p.rewards_eligible);self.assertFalse(p.production_grader)
    def test_work_order_hash_is_stable_across_json_key_order(self):
        obj=copy.deepcopy(BASE)
        self.assertEqual(prepare_attempt(encoded(obj)).work_order_sha256,
                         prepare_attempt(encoded(dict(reversed(list(obj.items()))))).work_order_sha256)
    def test_source_changes_change_hash(self):
        self.assertNotEqual(prepare_attempt(self.attempt()).work_order_sha256,
                            prepare_attempt(self.attempt(source_files={'Solution.java':'class Solution { int i; }'})).work_order_sha256)
    def test_attempt_id_changes_hash(self):
        self.assertNotEqual(prepare_attempt(self.attempt()).work_order_sha256,
                            prepare_attempt(self.attempt(attempt_id='b'*32)).work_order_sha256)
    def test_hash_is_valid_sha256(self):self.assertRegex(prepare_attempt(self.attempt()).work_order_sha256,r'^[0-9a-f]{64}$')
    def test_read_only_prepared_result(self):
        with self.assertRaises(FrozenInstanceError):prepare_attempt(self.attempt()).mastery_eligible=True
    def test_missing_field(self):
        o=copy.deepcopy(BASE);o.pop('source_files');self.bad(encoded(o))
    def test_extra_field(self):self.bad(self.attempt(mastered=True))
    def test_client_sandbox_attestation_is_disallowed(self):self.bad(self.attempt(isolated=True))
    def test_wrong_schema(self):self.bad(self.attempt(schema='codefit-java-attempt/v0.99'))
    def test_non_hex_identifier(self):self.bad(self.attempt(attempt_id='z'*32))
    def test_short_identifier(self):self.bad(self.attempt(attempt_id='a'*31))
    def test_uppercase_identifier(self):self.bad(self.attempt(attempt_id='A'*32))
    def test_numeric_identifier(self):self.bad(self.attempt(attempt_id=1))
    def test_unknown_mission(self):self.bad(self.attempt(mission_id='EJ20-999-Q3'))
    def test_predict_mission_is_not_java_build(self):self.bad(self.attempt(mission_id='EJ20-023-Q1'))
    def test_null_mission(self):self.bad(self.attempt(mission_id=None))
    def test_empty_source_map(self):self.bad(self.attempt(source_files={}))
    def test_source_map_list(self):self.bad(self.attempt(source_files=['x']))
    def test_too_many_files(self):self.bad(self.attempt(source_files={f'A{i}.java':'class X {}' for i in range(5)}))
    def test_parent_traversal(self):self.bad(self.attempt(source_files={'../X.java':'class X {}'}))
    def test_absolute_path(self):self.bad(self.attempt(source_files={'/tmp/X.java':'class X {}'}))
    def test_windows_path(self):self.bad(self.attempt(source_files={'C:\\X.java':'class X {}'}))
    def test_symlink_path(self):self.bad(self.attempt(source_files={'foo/X.java':'class X {}'}))
    def test_dotfile(self):self.bad(self.attempt(source_files={'.hidden.java':'class X {}'}))
    def test_non_java_extension(self):self.bad(self.attempt(source_files={'X.class':'not java'}))
    def test_binary_contents(self):self.bad(self.attempt(source_files={'X.java':10}))
    def test_empty_source(self):self.bad(self.attempt(source_files={'X.java':''}))
    def test_nul(self):self.bad(self.attempt(source_files={'X.java':'class X {}\x00'}))
    def test_escape_controls(self):self.bad(self.attempt(source_files={'X.java':'class X {}\x1b[31m'}))
    def test_bidi_override(self):self.bad(self.attempt(source_files={'X.java':'//\u202ereversal'}))
    def test_bom(self):self.bad(self.attempt(source_files={'X.java':'\ufeffclass X{}'}))
    def test_surrogate(self):self.bad(self.attempt(source_files={'X.java':'\ud800'}))
    def test_max_file_bytes(self):self.bad(self.attempt(source_files={'X.java':'x'*(MAX_FILE_BYTES+1)}))
    def test_total_size_limit(self):self.bad(self.attempt(source_files={'A.java':'a'*30000,'B.java':'b'*30000,'C.java':'c'*30000}))
    def test_max_envelope_bytes(self):self.bad(' '* (128*1024+1))
    def test_duplicate_json_key(self):self.bad('{"schema":"x","schema":"y"}')
    def test_nonfinite(self):self.bad('{"x":NaN}')
    def test_invalid_utf8(self):self.bad(b'\xff')
    def test_invalid_top_level_array(self):self.bad('[]')
    def test_bool_as_mapping(self):self.bad('true')
    def test_unknown_keyword_to_execute_cannot_bypass(self):
        with self.assertRaises(IsolationUnavailable):execute_attempt(self.attempt(), sandbox_verified=True, approved=True)
    def test_execute_never_launches_subprocess(self):
        with patch('subprocess.run',side_effect=AssertionError('process launched')):
            with self.assertRaises(IsolationUnavailable):execute_attempt(self.attempt())
    def test_capability_probe_never_approves(self):
        probe=inspect_runtime();self.assertFalse(probe['isolation_verified']);self.assertFalse(probe['learner_code_execution_allowed'])
    def test_presence_of_runtime_is_not_security_proof(self):
        with patch('shutil.which',return_value='/usr/bin/nsjail'):
            self.assertFalse(inspect_runtime()['isolation_verified'])
    def test_no_secrets_or_hidden_tests_in_prepared_object(self):
        self.assertNotIn('class Solution',repr(prepare_attempt(self.attempt())))
        self.assertNotIn('answer',repr(prepare_attempt(self.attempt())))
    def test_known_demo_build_ids(self):
        for mid in ('EJ20-022-Q3','EJ20-023-Q3','EJ20-024-Q3'):
            self.assertEqual(prepare_attempt(self.attempt(mission_id=mid)).mission_id,mid)
    def test_policy_contains_required_isolation_gates(self):
        required=set(POLICY['requires'])
        self.assertIn('network_namespace_no_egress',required)
        self.assertIn('kernel_enforced_pid_memory_cpu_limits',required)
        self.assertIn('independent_security_approval',required)
        self.assertLessEqual(POLICY['max_stdout_bytes'],4096)
    def test_unicode_valid_source_deterministic(self):
        p=prepare_attempt(self.attempt(source_files={'Solution.java':'// café\nclass Solution {}'}));self.assertGreater(p.source_bytes,22)

if __name__=='__main__':unittest.main()