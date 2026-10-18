"""Author-side packet structure and leakage tests; no independent review is asserted."""
import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from review_delivery_v27 import (SOURCE, PacketError, build_packets,
    select_subjects, write_packets, verify_packets, hash_data)

class ReviewDeliveryV27(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.claims=json.loads((SOURCE/'claim_register_v0.22.json').read_text())
        cls.lessons=json.loads((SOURCE/'blind_pedagogy_packet_v0.22.json').read_text())
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.out=Path(self.temp.name)/'out'
    def invalid(self,mutate):
        claims=copy.deepcopy(self.claims);lessons=copy.deepcopy(self.lessons)
        mutate(claims,lessons)
        with self.assertRaises(PacketError):select_subjects(claims,lessons)
    def test_exact_28_technical_claims(self):
        t,_=build_packets();self.assertEqual(len(t['subjects']),28)
    def test_exact_six_blind_missions(self):
        _,p=build_packets();self.assertEqual(len(p['subjects']),6)
    def test_distinct_role_packets(self):
        t,p=build_packets();self.assertEqual(t['review_role'],'technical');self.assertEqual(p['review_role'],'pedagogy')
    def test_no_approval_claims(self):
        for p in build_packets():self.assertEqual(p['approval_count'],0)
    def test_subject_hash_binds_complete_source_record(self):
        t,_=build_packets();cid=t['subjects'][0]['subject_id']
        src=next(x for x in self.claims['claims'] if x['claim_id']==cid)
        self.assertEqual(t['subjects'][0]['subject_sha256'],hash_data(src))
    def test_pedagogy_subject_hash_binds_complete_source_record(self):
        _,p=build_packets();mid=p['subjects'][0]['subject_id']
        src=next(x for x in self.lessons['blind_missions'] if x['mission_id']==mid)
        self.assertEqual(p['subjects'][0]['subject_sha256'],hash_data(src))
    def test_subject_change_changes_hash(self):
        row=copy.deepcopy(self.claims['claims'][0]);old=hash_data(row);row['original_author_claim']+=' Changed.'
        self.assertNotEqual(hash_data(row),old)
    def test_deterministic_bytes_on_rebuild(self):
        write_packets(self.out);before={x.name:x.read_bytes() for x in self.out.iterdir()}
        write_packets(self.out);self.assertEqual(before,{x.name:x.read_bytes() for x in self.out.iterdir()})
    def test_verified_unmodified_manifest(self):
        write_packets(self.out);self.assertTrue(verify_packets(self.out))
    def test_detect_modified_review_text(self):
        write_packets(self.out);p=self.out/'technical_claim_packet_v0.27.json'
        p.write_bytes(p.read_bytes()+b' ')
        with self.assertRaises(PacketError):verify_packets(self.out)
    def test_detect_modified_manifest(self):
        write_packets(self.out);p=self.out/'review_delivery_manifest_v0.27.json'
        d=json.loads(p.read_text());d['independently_approved']=1;p.write_text(json.dumps(d))
        with self.assertRaises(PacketError):verify_packets(self.out)
    def test_no_publisher_or_private_calibration_material(self):
        write_packets(self.out)
        self.assertEqual(len(list(self.out.iterdir())),3)
        for p in self.out.iterdir():
            self.assertNotIn('private_calibration_',p.name)
            for secret in ['correct_option','public_key_ed25519','private_key','source_passage','publisher_text','signing_key']:
                self.assertNotIn('"'+secret+'"',p.read_text())
    def test_incomplete_claims_rejected(self):
        self.invalid(lambda c,p:c['claims'].pop())
    def test_duplicate_claim_id_rejected(self):
        self.invalid(lambda c,p:c['claims'][1].update(claim_id=c['claims'][0]['claim_id']))
    def test_claim_with_forged_approval_rejected(self):
        self.invalid(lambda c,p:c['claims'][0].update(review_decision='ACCEPT'))
    def test_claim_with_pubtext_flag_rejected(self):
        self.invalid(lambda c,p:c['claims'][0].update(source_passages_included=True))
    def test_claim_with_reviewer_identity_rejected(self):
        self.invalid(lambda c,p:c['claims'][0].update(reviewer_id='unauthorized'))
    def test_invalid_source_digest_rejected(self):
        self.invalid(lambda c,p:c['claims'][0]['source_locator'].update(source_body_sha256='not-a-digest'))
    def test_added_answer_key_rejected(self):
        self.invalid(lambda c,p:p['blind_missions'][0].update(answer_key='A'))
    def test_blind_flag_downgraded_rejected(self):
        self.invalid(lambda c,p:p['blind_missions'][0].update(author_rubric_withheld=False))
    def test_blind_review_already_approved_rejected(self):
        self.invalid(lambda c,p:p['blind_missions'][0].update(independent_pedagogy_review='ACCEPT'))
    def test_missing_blind_mission_rejected(self):
        self.invalid(lambda c,p:p['blind_missions'].pop())
    def test_extra_claim_field_rejected(self):
        self.invalid(lambda c,p:c['claims'][0].update(internal_feedback='secret'))
    def test_correct_keys_only_in_public_packet(self):
        t,p=build_packets()
        self.assertEqual(set(t['subjects'][0]),{'subject_id','subject_sha256','item_id','claim_tag','author_claim','independent_challenge','source_locator','java_baseline','review_status'})
        self.assertEqual(set(p['subjects'][0]),{'subject_id','subject_sha256','stage','prompt','reviewer_task','review_status'})
    def test_manifest_pinning_required(self):
        m=write_packets(self.out);self.assertTrue(m['owner_must_pin_manifest_out_of_band']);self.assertFalse(m['release_eligible'])
    def test_injected_private_key_filename_not_generated(self):
        write_packets(self.out);self.assertEqual(sorted(p.suffix for p in self.out.iterdir()),['.json','.json','.json'])

if __name__=='__main__':unittest.main()
