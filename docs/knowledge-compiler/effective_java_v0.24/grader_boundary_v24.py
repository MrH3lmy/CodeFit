"""Author-only, non-executing boundary for future isolated Java grading.

Only creates a fingerprinted work order and refuses local execution. It deliberately
has NO runner adapter and NO subprocess integration for learner-supplied Java.
An external audited and separately deployed execution service is required.
"""
from __future__ import annotations
import hashlib
import json
import re
from dataclasses import dataclass

MAX_ENVELOPE_BYTES = 128 * 1024
MAX_FILE_BYTES = 32 * 1024
MAX_TOTAL_SOURCE_BYTES = 64 * 1024
MAX_SOURCE_FILES = 4
ID_RE = re.compile(r'^[0-9a-f]{32}$')
NAME_RE = re.compile(r'^[A-Za-z_$][A-Za-z0-9_$]{0,63}\.java$')
# Build mission IDs selected from the existing v0.22 blind reviewer packet.
ELIGIBLE_OFFLINE_DEMOS = frozenset({'EJ20-022-Q3', 'EJ20-023-Q3', 'EJ20-024-Q3'})
POLICY = {
    'schema': 'codefit-isolated-worker-policy/v0.24',
    'jdk': '21', 'wall_ms': 5000, 'cpu_ms': 3000, 'memory_mib': 128,
    'max_pids': 16, 'disk_mib': 16, 'max_stdout_bytes': 4096,
    'max_stderr_bytes': 4096, 'max_files': 64,
    'requires': [
        'separate_unprivileged_worker', 'kernel_enforced_pid_memory_cpu_limits',
        'network_namespace_no_egress', 'read_only_toolchain',
        'ephemeral_no_host_secret_mounts', 'no_privilege_escalation',
        'process_tree_termination', 'hidden_tests_separate_from_submission',
        'bounded_output_outside_worker', 'independent_security_approval'
    ],
}

class InvalidAttempt(ValueError): pass
class IsolationUnavailable(RuntimeError): pass

def _no_duplicates(pairs):
    obj = {}
    for k, v in pairs:
        if k in obj:
            raise ValueError('duplicate key')
        obj[k] = v
    return obj

def _reject_special_number(v):
    raise ValueError('non-finite number')

def _load_json(serialized: bytes | str):
    if type(serialized) not in (bytes, str):
        raise InvalidAttempt('serialized bytes or string required')
    try:
        raw = serialized if type(serialized) is bytes else serialized.encode('utf-8')
        if len(raw) > MAX_ENVELOPE_BYTES:
            raise ValueError('oversized envelope')
        obj = json.loads(raw, object_pairs_hook=_no_duplicates, parse_constant=_reject_special_number)
    except (ValueError, TypeError, UnicodeError) as exc:
        raise InvalidAttempt('invalid JSON envelope') from exc
    return obj

def _valid_source(src):
    if type(src) is not str:
        return False
    try:
        data = src.encode('utf-8', errors='strict')
    except UnicodeError:
        return False
    if not data or len(data) > MAX_FILE_BYTES or '\x00' in src:
        return False
    # Controls and directional format characters distort code/evidence presentation.
    if any(ord(c) < 32 and c not in '\n\r\t' for c in src):
        return False
    if any(c in src for c in ('\ufeff', '\u202a', '\u202b', '\u202c', '\u202d', '\u202e', '\u2066', '\u2067', '\u2068', '\u2069')):
        return False
    return True

def canonical(obj):
    return json.dumps(obj, sort_keys=True, ensure_ascii=False,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')

@dataclass(frozen=True)
class PreparedAttempt:
    work_order_sha256: str
    attempt_id: str
    mission_id: str
    source_file_count: int
    source_bytes: int
    status: str = 'VALIDATED_NOT_EXECUTED'
    mastery_eligible: bool = False
    rewards_eligible: bool = False
    production_grader: bool = False

def prepare_attempt(serialized):
    """Validate input and bind it to a policy digest; do not run or store source."""
    obj = _load_json(serialized)
    if type(obj) is not dict or set(obj) != {'schema', 'attempt_id', 'mission_id', 'source_files'}:
        raise InvalidAttempt('unexpected envelope fields')
    if obj['schema'] != 'codefit-java-attempt/v0.24':
        raise InvalidAttempt('unsupported schema')
    aid = obj['attempt_id']
    if type(aid) is not str or not ID_RE.fullmatch(aid):
        raise InvalidAttempt('invalid attempt identifier')
    mission = obj['mission_id']
    if type(mission) is not str or mission not in ELIGIBLE_OFFLINE_DEMOS:
        raise InvalidAttempt('mission not in demo allowlist')
    files = obj['source_files']
    if type(files) is not dict or not 1 <= len(files) <= MAX_SOURCE_FILES:
        raise InvalidAttempt('source file count invalid')
    total = 0
    for name, contents in files.items():
        if type(name) is not str or not NAME_RE.fullmatch(name) or not _valid_source(contents):
            raise InvalidAttempt('invalid source filename or bytes')
        total += len(contents.encode('utf-8'))
    if total > MAX_TOTAL_SOURCE_BYTES:
        raise InvalidAttempt('source payload too large')
    # Work-order digest binds immutable policy and exact source bytes, without
    # disclosing the source in returned data or logging it anywhere.
    work_order = {'policy': POLICY, 'attempt': obj}
    return PreparedAttempt(
        work_order_sha256=hashlib.sha256(canonical(work_order)).hexdigest(),
        attempt_id=aid, mission_id=mission,
        source_file_count=len(files), source_bytes=total)

def inspect_runtime() -> dict:
    """Non-authoritative environment hint; presence of a tool is not isolation proof."""
    import shutil
    tools = {t: shutil.which(t) is not None for t in ('nsjail','bwrap','firecracker','docker','podman')}
    return {'schema':'codefit-boundary-capability-probe/v0.24',
            'tools_detected':tools,
            'isolation_verified':False,
            'independent_security_review':False,
            'learner_code_execution_allowed':False}

def execute_attempt(*_args, **_kwargs):
    """Cannot be overridden by untrusted fields or a pretend sandbox flag."""
    raise IsolationUnavailable('No independently security-approved isolated worker is connected; learner Java execution is blocked')

if __name__ == '__main__':
    print(json.dumps({'runtime':inspect_runtime(),'release_eligible':False,
                      'production_grader':False,'mastery_eligible':False}, indent=2))
