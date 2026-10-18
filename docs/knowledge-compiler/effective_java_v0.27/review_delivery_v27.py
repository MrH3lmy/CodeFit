"""Deterministic, author-side independent-review PACKET builder (not an approver).

Builds two original-only packets, binds each request to its complete v0.22
subject for the v0.23 signed reviewer protocol, and prevents accidental
export of private calibrations, grading answers, source books, or key files.
No external reviewer is enrolled by this module; no approvals are granted.
"""
from __future__ import annotations
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / 'output' / 'deep_v22'
PACKET_DIR = ROOT / 'output' / 'deep_v27' / 'reviewer_delivery'
NAME_RE = re.compile(r'^(EJ22-0(?:19|20|21|22|23|24|25)-C[1-4]|EJ20-0(?:21|22|23|24)-Q[13])$')

class PacketError(ValueError):
    pass


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode('utf-8')


def hash_data(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def _strict_read(path):
    def pairs(kv):
        d = {}
        for k, v in kv:
            if k in d:
                raise PacketError('duplicate JSON property')
            d[k] = v
        return d
    try:
        return json.loads(path.read_bytes(), object_pairs_hook=pairs,
                          parse_constant=lambda x: (_ for _ in ()).throw(PacketError('non-finite number')))
    except (OSError, ValueError, UnicodeError) as exc:
        raise PacketError(f'input could not be validated: {path.name}') from exc


def _exact(obj, keys):
    return type(obj) is dict and set(obj) == set(keys)


def select_subjects(claims_doc, pedagogy_doc):
    if not _exact(claims_doc, {'schema','items','claim_count','full_sections_privately_revalidated',
                               'reviewer_is_independent','accepted_claims','claims'}):
        raise PacketError('unexpected claims container')
    if claims_doc['schema']!='codefit-claim-review-register/v0.22' or claims_doc['items']!=7 or claims_doc['claim_count']!=28 or claims_doc['accepted_claims']!=0 or claims_doc['reviewer_is_independent'] is not False:
        raise PacketError('claims baseline not pending')
    if not _exact(pedagogy_doc, {'schema','blind_missions','key_visibility','approved_missions'}):
        raise PacketError('unexpected pedagogy container')
    if pedagogy_doc['schema']!='codefit-blind-pedagogy-packet/v0.22' or pedagogy_doc['key_visibility']!='WITHHELD_FROM_REVIEWER' or pedagogy_doc['approved_missions']!=0:
        raise PacketError('pedagogy baseline not blind')
    claims = claims_doc['claims']; lessons = pedagogy_doc['blind_missions']
    if type(claims) is not list or len(claims)!=28 or type(lessons) is not list or len(lessons)!=6:
        raise PacketError('unexpected review scope')
    allowed_claim={'claim_id','item_id','source_note_id','claim_tag','original_author_claim',
                   'independent_challenge_request','existing_test_or_mission_reference','source_locator',
                   'java_baseline','source_passages_included','author_evidence_status','review_decision',
                   'reviewer_id','reviewer_evidence','pedagogical_approval','mastery_eligible'}
    allowed_lesson={'mission_id','stage','prompt','reviewer_task','independent_pedagogy_review',
                    'author_rubric_withheld','mastery_eligible'}
    ids=set(); claim_rows=[]; lesson_rows=[]
    for subject in claims:
        if not _exact(subject,allowed_claim):raise PacketError('invalid claim fields')
        cid=subject['claim_id']
        if type(cid) is not str or not NAME_RE.fullmatch(cid) or cid in ids:
            raise PacketError('unexpected/duplicate claim id')
        ids.add(cid)
        if (subject['review_decision']!='PENDING' or subject['reviewer_id'] is not None
           or subject['reviewer_evidence'] is not None or subject['source_passages_included'] is not False
           or subject['mastery_eligible'] is not False or subject['pedagogical_approval'] is not False):
            raise PacketError('a claim is no longer unsigned or contains source content')
        locator=subject['source_locator']
        if (not _exact(locator, {'pdf_pages_1_based','source_body_sha256'}) or
            type(locator['source_body_sha256']) is not str or not re.fullmatch(r'[a-f0-9]{64}',locator['source_body_sha256'])):
            raise PacketError('source locator invalid')
        for key in ('original_author_claim','independent_challenge_request'):
            if type(subject[key]) is not str or not 12<=len(subject[key])<=1000:
                raise PacketError('claim prompt invalid')
        claim_rows.append({'subject_id':cid,'subject_sha256':hash_data(subject),
                           'item_id':subject['item_id'],'claim_tag':subject['claim_tag'],
                           'author_claim':subject['original_author_claim'],
                           'independent_challenge':subject['independent_challenge_request'],
                           'source_locator':locator,'java_baseline':subject['java_baseline'],
                           'review_status':'PENDING_EXTERNAL_TECHNICAL_REVIEW'})
    if len({c['item_id'] for c in claim_rows})!=7 or sorted(c['subject_id'] for c in claim_rows)!=[f'EJ22-{n:03d}-C{k}' for n in range(19,26) for k in range(1,5)]:
        raise PacketError('claims are incomplete or out of scope')
    ids.clear()
    for subject in lessons:
        if not _exact(subject,allowed_lesson):raise PacketError('invalid pedagogy fields')
        mid=subject['mission_id']
        if type(mid) is not str or not NAME_RE.fullmatch(mid) or mid in ids:
            raise PacketError('unexpected/duplicate mission id')
        ids.add(mid)
        if subject['independent_pedagogy_review']!='PENDING' or subject['author_rubric_withheld'] is not True or subject['mastery_eligible'] is not False:
            raise PacketError('pedagogy packet not pending or answer masking disabled')
        if subject['stage'] not in ('predict','build') or not 15<=len(subject['prompt'])<=1000:
            raise PacketError('pedagogy prompt invalid')
        lesson_rows.append({'subject_id':mid,'subject_sha256':hash_data(subject),
                            'stage':subject['stage'],'prompt':subject['prompt'],
                            'reviewer_task':subject['reviewer_task'],
                            'review_status':'PENDING_EXTERNAL_PEDAGOGY_REVIEW'})
    if ids != {'EJ20-021-Q1','EJ20-022-Q3','EJ20-023-Q1','EJ20-023-Q3','EJ20-024-Q1','EJ20-024-Q3'}:
        raise PacketError('unexpected blind pedagogy mission set')
    return sorted(claim_rows,key=lambda c:c['subject_id']), sorted(lesson_rows,key=lambda c:c['subject_id'])


def build_packets(source_dir=SOURCE):
    claims,lessons=select_subjects(_strict_read(source_dir/'claim_register_v0.22.json'),
                                   _strict_read(source_dir/'blind_pedagogy_packet_v0.22.json'))
    technical={'schema':'codefit-review-delivery/v0.27','review_role':'technical',
               'required_decision':'ACCEPT | REJECT | NEEDS_REVISION',
               'approval_count':0,'subjects':claims,
               'instructions':'Independently verify each claim using authorized source and original reproducible evidence. Do not quote publisher material into public artifacts. Signature proof alone does not grant approval.'}
    pedagogy={'schema':'codefit-review-delivery/v0.27','review_role':'pedagogy',
              'required_decision':'ACCEPT | REJECT | NEEDS_REVISION',
              'approval_count':0,'subjects':lessons,
              'instructions':'Create own expected response, plausible misconception, discriminating follow-up and fairness caveat BEFORE seeing withheld author rubric. No answer keys included.'}
    return technical,pedagogy


def write_packets(destination=PACKET_DIR, source_dir=SOURCE):
    technical,pedagogy=build_packets(source_dir)
    destination.mkdir(parents=True,exist_ok=True)
    outputs={'technical_claim_packet_v0.27.json':technical,'blind_pedagogy_packet_v0.27.json':pedagogy}
    for name,document in outputs.items():
        (destination/name).write_bytes(json.dumps(document,sort_keys=True,ensure_ascii=False,indent=2).encode('utf-8')+b'\n')
    files={name:hashlib.sha256((destination/name).read_bytes()).hexdigest() for name in outputs}
    manifest={'schema':'codefit-review-delivery-manifest/v0.27','technical_claims':28,
              'blind_pedagogy_missions':6,'independently_approved':0,
              'reviewer_enrolled':False,'private_keys_exported':False,'private_answer_keys_exported':False,
              'publisher_passages_exported':False,'release_eligible':False,
              'files_sha256':files,'owner_must_pin_manifest_out_of_band':True}
    path=destination/'review_delivery_manifest_v0.27.json'
    path.write_bytes(json.dumps(manifest,sort_keys=True,ensure_ascii=False,indent=2).encode('utf-8')+b'\n')
    return manifest


def verify_packets(directory=PACKET_DIR,source_dir=SOURCE):
    manifest=_strict_read(directory/'review_delivery_manifest_v0.27.json')
    expected=write_packets_dummy_manifest(source_dir)
    if set(manifest)!=set(expected): raise PacketError('manifest fields invalid')
    if manifest!=expected:raise PacketError('subject fingerprint or source mismatch')
    for name,digest in expected['files_sha256'].items():
        if hashlib.sha256((directory/name).read_bytes()).hexdigest()!=digest:
            raise PacketError('packet changed after generation')
    return True


def write_packets_dummy_manifest(source_dir):
    technical,pedagogy=build_packets(source_dir)
    docs={'technical_claim_packet_v0.27.json':technical,'blind_pedagogy_packet_v0.27.json':pedagogy}
    hashes={name:hashlib.sha256(json.dumps(doc,sort_keys=True,ensure_ascii=False,indent=2).encode('utf-8')+b'\n').hexdigest() for name,doc in docs.items()}
    return {'schema':'codefit-review-delivery-manifest/v0.27','technical_claims':28,
            'blind_pedagogy_missions':6,'independently_approved':0,
            'reviewer_enrolled':False,'private_keys_exported':False,'private_answer_keys_exported':False,
            'publisher_passages_exported':False,'release_eligible':False,
            'files_sha256':hashes,'owner_must_pin_manifest_out_of_band':True}


if __name__=='__main__':
    print(json.dumps(write_packets(),indent=2))
    assert verify_packets()
