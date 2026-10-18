#!/usr/bin/env python3
"""NON-PRODUCTION v0.22 calibration harness for closed-world predict decisions.

Does not execute, compile, import, persist or sandbox submitted code. No learner
mastery, XP, reward, network, hosted execution or AI free-text grading.
Private rubric keys MUST NOT be put into the public GitHub agent handoff.
"""
import hashlib
import json
import re

MAX_SUBMISSION_BYTES=8192
MAX_RUBRIC_BYTES=16384
MISSION_ID = re.compile(r'^EJ20-0(?:21|23|24)-Q1$')
CRITERION_ID = re.compile(r'^[a-z][a-z0-9_]{2,48}$')
ALLOWED_OPTIONS = frozenset(('A','B','C','D'))

class InvalidSubmission(ValueError): pass
class InvalidRubric(ValueError): pass

def _no_duplicates(pairs):
    obj={}
    for key,value in pairs:
        if key in obj: raise ValueError('duplicate JSON key')
        obj[key]=value
    return obj

def _no_nonfinite(_:str):raise ValueError('non-finite JSON number forbidden')

def _strict_json(payload, byte_limit, error_type):
    try:
        if not isinstance(payload,(str,bytes)):
            raise ValueError('a JSON string or bytes value is required')
        if len(payload.encode('utf-8') if isinstance(payload,str) else payload)>byte_limit:
            raise ValueError('input exceeds byte budget')
        return json.loads(payload,object_pairs_hook=_no_duplicates,parse_constant=_no_nonfinite)
    except (UnicodeError,ValueError,TypeError) as exc:
        raise error_type('malformed or disallowed JSON') from exc

def _exact_keys(obj,keys):return type(obj) is dict and set(obj)==set(keys)

def load_rubric(payload):
    doc=_strict_json(payload,MAX_RUBRIC_BYTES,InvalidRubric)
    if not _exact_keys(doc,('schema','mission_id','stage','criteria','release_gate')):raise InvalidRubric('invalid rubric envelope')
    if (doc['schema']!='codefit-private-calibration-rubric/v0.22' or
        not isinstance(doc['mission_id'],str) or not MISSION_ID.fullmatch(doc['mission_id']) or
        doc['stage']!='predict' or doc['release_gate']!='CALIBRATION_ONLY_NOT_FOR_LEARNERS'):
        raise InvalidRubric('unsupported rubric configuration')
    criteria=doc['criteria']
    if type(criteria) is not list or not 2<=len(criteria)<=8:raise InvalidRubric('invalid number of criteria')
    seen=set()
    for item in criteria:
        if not _exact_keys(item,('id','weight','mandatory','correct','options')):raise InvalidRubric('invalid criterion')
        cid=item['id']; weight=item['weight']; options=item['options']
        if not isinstance(cid,str) or not CRITERION_ID.fullmatch(cid) or cid in seen:raise InvalidRubric('invalid criterion ID')
        if type(weight) is not int or not 1<=weight<=5 or type(item['mandatory']) is not bool or not item['mandatory']:
            raise InvalidRubric('invalid weight or mandatory status')
        if type(options) is not list or not 2<=len(options)<=4 or len(set(map(str,options)))!=len(options) or any(type(o) is not str or o not in ALLOWED_OPTIONS for o in options):
            raise InvalidRubric('invalid options')
        if type(item['correct']) is not str or item['correct'] not in options:raise InvalidRubric('correct option missing')
        seen.add(cid)
    return doc

def evaluate(rubric, submission):
    if type(rubric) is not dict:raise InvalidRubric('load rubric with load_rubric')
    # validate supplied dict again, preventing a caller from mutating validation results
    rubric=load_rubric(json.dumps(rubric,ensure_ascii=False,allow_nan=False))
    answer=_strict_json(submission,MAX_SUBMISSION_BYTES,InvalidSubmission)
    if not _exact_keys(answer,('schema','mission_id','stage','answers')):raise InvalidSubmission('invalid answer envelope')
    if answer['schema']!='codefit-closed-world-answer/v0.22' or answer['mission_id']!=rubric['mission_id'] or answer['stage']!='predict':
        raise InvalidSubmission('unsupported mission/stage/schema')
    choices=answer['answers']; ids={c['id'] for c in rubric['criteria']}
    if type(choices) is not dict or set(choices)!=ids:raise InvalidSubmission('missing or additional criteria')
    for criterion in rubric['criteria']:
        option=choices[criterion['id']]
        if type(option) is not str or option not in criterion['options']:
            raise InvalidSubmission('invalid choice')
    raw=sum(c['weight'] for c in rubric['criteria'] if choices[c['id']]==c['correct'])
    total=sum(c['weight'] for c in rubric['criteria'])
    canonical=json.dumps(answer,sort_keys=True,ensure_ascii=False,separators=(',',':'))
    return {'schema':'codefit-offline-calibration-result/v0.22','mission_id':rubric['mission_id'],
            'status':'ALL_CORRECT_IN_SYNTHETIC_CALIBRATION' if raw==total else 'NOT_ALL_CORRECT_IN_SYNTHETIC_CALIBRATION',
            'points':raw,'max_points':total,'submission_sha256':hashlib.sha256(canonical.encode()).hexdigest(),
            'mastery_eligible':False,'rewards_eligible':False,'independent_review':False,
            'learner_release':False,'production_grader':False}
