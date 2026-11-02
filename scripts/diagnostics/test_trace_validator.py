import unittest
from verify_timeout_trace import validate


class TraceValidatorTest(unittest.TestCase):
    def valid(self):
        kinds = ['REQUEST', 'DEADLINE_STARTED', 'SPAWN_RETURNED', 'REGISTRATION_RETURNED',
                 'WAIT_ENTER', 'WAIT_RETURNED', 'TERMINATION_REQUESTED', 'CHILD_EXIT_OBSERVED', 'RETURNED']
        events = [dict(event=k, atNanos=i, invocation='submission-1', challenge='synthetic@1',
                       phase='RUN', phaseId=1, check='synthetic-loop', status='', remainingNanos=-1,
                       childAlive=False) for i, k in enumerate(kinds)]
        events[5]['status'] = 'DEADLINE_EXHAUSTED'
        events[-1]['status'] = 'TIMED_OUT'
        return dict(schemaVersion=1, scenarioExit=1, synchronousObserverNanos=1, observerErrors=0,
                    truncated=False, anchors={'com/codefit/service/JavaCodeRunner': 12,
                    'com/codefit/service/RunCancellationToken': 1,
                    'com/codefit/evolution/evaluation/JavaChallengeEvaluator': 1}, events=events)

    def test_timeout_and_cleanup_boundaries(self):
        self.assertEqual((1, 0), validate(self.valid()))

    def test_sensitive_extra_fields_rejected(self):
        for key in ['source', 'stdin', 'stderr', 'expectedOutput', 'credentials', 'artifact']:
            data = self.valid()
            data['events'][0][key] = 'must-not-cross-boundary'
            with self.assertRaises(AssertionError):
                validate(data)

    def test_unattributed_or_truncated_capture_rejected(self):
        for key, value in [('observerErrors', 1), ('truncated', True), ('anchors', {})]:
            data = self.valid()
            data[key] = value
            with self.assertRaises(AssertionError):
                validate(data)

    def test_positive_remaining_deadline_cannot_be_called_exhaustion(self):
        data = self.valid()
        data['events'][5]['remainingNanos'] = 500
        with self.assertRaises(AssertionError):
            validate(data)

    def test_missing_cleanup_or_mismatched_invocation_rejected(self):
        data = self.valid()
        data['events'].pop(7)
        with self.assertRaises(AssertionError):
            validate(data)
        data = self.valid()
        data['events'][1]['invocation'] = 'another-submission'
        with self.assertRaises(AssertionError):
            validate(data)

    def test_native_schema_requires_observation_and_preserves_v1_meaning(self):
        data = self.valid()
        data['schemaVersion'] = 2
        with self.assertRaises(AssertionError):
            validate(data)
        event = data['events'][5]
        event.update(nativeHandleAlive=True, kernelState='R', kernelSampleAtNanos=6,
                     childCpuAvailable=True)
        self.assertEqual((1, 0), validate(data))
        data['schemaVersion'] = 1
        with self.assertRaises(AssertionError):
            validate(data)
