#!/usr/bin/env python3
"""Validate bounded allowlisted diagnostics; never parse or print private evaluation payloads."""
import json
import sys

ROOT = {'schemaVersion', 'scenarioExit', 'synchronousObserverNanos', 'observerErrors', 'truncated', 'anchors', 'events'}
FIELDS = {'event', 'atNanos', 'utc', 'invocation', 'challenge', 'phase', 'phaseId', 'check', 'status',
          'remainingNanos', 'budgetNanos', 'childAlive', 'childCpuNanos', 'cpuQuota', 'nr_throttled',
          'throttled_usec', 'jvmProcesses', 'available', 'evidenceOutcome', 'gateActive', 'replayEqual'}
EVENTS = {'SUBMISSION_BEGIN', 'SUBMISSION_ENVIRONMENT', 'PHASE_ENVIRONMENT', 'PHASE_END_ENVIRONMENT',
          'FINAL_ENVIRONMENT', 'REQUEST', 'DEADLINE_STARTED', 'SPAWN_RETURNED', 'REGISTRATION_RETURNED',
          'WAIT_ENTER', 'WAIT_RETURNED', 'TERMINATION_REQUESTED', 'FIRST_OBSERVED_CHILD_CPU',
          'CHILD_EXIT_OBSERVED', 'RETURNED', 'FINALIZED', 'INTEGRITY'}


def validate(data):
    assert set(data) == ROOT and data['schemaVersion'] == 1
    assert data['observerErrors'] == 0 and data['truncated'] is False
    assert data['scenarioExit'] in (0, 1)
    assert data['anchors'] == {
        'com/codefit/service/JavaCodeRunner': 12,
        'com/codefit/service/RunCancellationToken': 1,
        'com/codefit/evolution/evaluation/JavaChallengeEvaluator': 1}
    assert 0 < len(data['events']) <= 512
    phases = {}
    for e in data['events']:
        assert set(e) <= FIELDS and e['event'] in EVENTS
        for k, v in e.items():
            assert isinstance(v, (str, int, bool)) and (not isinstance(v, str) or len(v) < 160)
        if e.get('phaseId', 0):
            phases.setdefault(e['phaseId'], []).append(e)
        if e['event'] == 'INTEGRITY':
            assert e['gateActive'] is False and e['replayEqual'] is True
    assert phases
    for events in phases.values():
        kinds = [e['event'] for e in events]
        for once in ('REQUEST', 'DEADLINE_STARTED', 'SPAWN_RETURNED', 'REGISTRATION_RETURNED', 'WAIT_ENTER', 'WAIT_RETURNED', 'RETURNED', 'CHILD_EXIT_OBSERVED'):
            assert kinds.count(once) == 1, (once, kinds)
        identity = {(e['invocation'], e['challenge'], e['phase'], e['check']) for e in events}
        assert len(identity) == 1
        boundary = {e['event']: e for e in events}
        ordered = ('DEADLINE_STARTED', 'SPAWN_RETURNED', 'REGISTRATION_RETURNED', 'WAIT_ENTER', 'WAIT_RETURNED', 'RETURNED')
        assert [boundary[k]['atNanos'] for k in ordered] == sorted(boundary[k]['atNanos'] for k in ordered)
        assert boundary['CHILD_EXIT_OBSERVED']['childAlive'] is False
        if boundary['RETURNED']['status'] == 'TIMED_OUT':
            assert boundary['WAIT_RETURNED']['status'] == 'DEADLINE_EXHAUSTED'
            assert boundary['WAIT_RETURNED']['remainingNanos'] <= 0
            assert 'TERMINATION_REQUESTED' in kinds
    timed = [e for e in data['events'] if e['event'] == 'FINALIZED' and e['status'] == 'TIMED_OUT']
    for e in timed:
        assert any(i['event'] == 'INTEGRITY' and i['invocation'] == e['invocation'] and i['evidenceOutcome'] == 'UNAVAILABLE' for i in data['events'])
    return len(phases), len(timed)


if __name__ == '__main__':
    for name in sys.argv[1:]:
        with open(name, encoding='utf-8') as source:
            data = json.load(source)
        phases, timed = validate(data)
        print(json.dumps({'file': name, 'phases': phases, 'timedOutSubmissions': timed,
                          'scenarioExit': data['scenarioExit'], 'observerNanos': data['synchronousObserverNanos']}))
