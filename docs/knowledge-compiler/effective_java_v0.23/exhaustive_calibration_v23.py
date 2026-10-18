#!/usr/bin/env python3
"""Exhaustively exercise all 3^n structured choices for three PRIVATE author rubrics.
Not a learner trial, not an independent/held-out assessment and not Java execution.
"""
import hashlib
import itertools
import json
from pathlib import Path
from offline_closed_world_v22 import load_rubric, evaluate
ROOT=Path(__file__).resolve().parent
PRIVATE=ROOT/'output/deep_v22/private_calibration_rubrics_v0.22.json'
OUT=ROOT/'output/deep_v23'

def run():
    rubrics=json.loads(PRIVATE.read_text())['rubrics']
    rows=[]
    for raw in rubrics:
        rubric=load_rubric(json.dumps(raw))
        criteria=rubric['criteria'];correct=0;incorrect=0
        expected=len(list(itertools.product(*(c['options'] for c in criteria))))
        seen=set()
        for values in itertools.product(*(c['options'] for c in criteria)):
            choice=dict(zip((c['id'] for c in criteria),values))
            input_doc={'schema':'codefit-closed-world-answer/v0.22','mission_id':rubric['mission_id'],
                       'stage':'predict','answers':choice}
            first=evaluate(rubric,json.dumps(input_doc))
            alternate={**input_doc,'answers':dict(reversed(list(choice.items())))}
            second=evaluate(rubric,json.dumps(alternate))
            assert first==second,'JSON ordering affected deterministic result'
            independent_oracle=sum(c['weight'] for c in criteria if choice[c['id']]==c['correct'])
            assert first['points']==independent_oracle
            assert first['max_points']==sum(c['weight'] for c in criteria)
            assert first['status']==('ALL_CORRECT_IN_SYNTHETIC_CALIBRATION' if independent_oracle==first['max_points'] else 'NOT_ALL_CORRECT_IN_SYNTHETIC_CALIBRATION')
            assert not any(first[k] for k in ('mastery_eligible','rewards_eligible','learner_release','production_grader','independent_review'))
            assert all(k not in first for k in ('correct','answers','rubric','answer_key','evidence'))
            assert first['submission_sha256'] not in seen
            seen.add(first['submission_sha256'])
            correct+=int(independent_oracle==first['max_points'])
            incorrect+=int(independent_oracle!=first['max_points'])
        assert correct==1 and incorrect==expected-1
        rows.append({'mission_id':rubric['mission_id'],'enumerated_cases':expected,
                     'perfect_cases':correct,'nonperfect_cases':incorrect,
                     'all_expected_scores_verified':True,'stable_across_json_key_order':True})
    assert sum(x['enumerated_cases'] for x in rows)==135
    report={'schema':'codefit-exhaustive-author-calibration/v0.23','missions':3,
            'structured_answer_combinations':135,'total_assessments_run':270,
            'all_assertions_pass':True,'original_author_cases_only':True,
            'independent_or_learner_holdout':False,'executes_user_code':False,
            'contains_answer_keys':False,'production_grader':False,'mastery_eligible':False,
            'cases':rows}
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'calibration_exhaustive_summary_v0.23.json').write_text(json.dumps(report,indent=2)+'\n')
    return report

if __name__=='__main__':
    print(json.dumps(run(),indent=2))