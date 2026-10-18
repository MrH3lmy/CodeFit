#!/usr/bin/env python3
"""Author-only negative Java21 cases. No publisher code or learner grading."""
import json, subprocess, tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent
CASES=[
 ('final_class_extension', {'Case.java':'final class Base {} class Derived extends Base {}'}, 'cannot inherit'),
 ('object_method_default', {'Case.java':'interface Example { default boolean equals(Object o) { return true; } }'}, 'overrides'),
 ('conflicting_defaults', {'Case.java':'interface A {default int f(){return 1;}} interface B {default int f(){return 2;}} class C implements A,B {}'}, 'inherits unrelated defaults'),
 ('interface_instance_state', {'Case.java':'interface A { private int value = 1; }'}, 'modifier private not allowed'),
 ('interface_constant_assignment', {'Case.java':'interface A {int MAX=4;} class C {void f(){ A.MAX=8; }}'}, 'assign a value'),
 ('record_extension', {'Case.java':'record Rec(int v) {} class Derived extends Rec {Derived(){super(1);}}'}, 'cannot inherit'),
 ('sealed_unauthorized', {'Case.java':'sealed interface A permits B {} final class B implements A {} final class C implements A {}'}, 'not allowed to extend'),
 ('inner_without_enclosing', {'Case.java':'class Holder {class Inner {} static Object create(){return new Inner();}}'}, 'non-static variable'),
 ('public_filename_mismatch', {'Case.java':'public class Other {}'}, 'should be declared in a file'),
 ('two_public_top_levels', {'Case.java':'public class Case {} public class Other {}'}, 'should be declared in a file'),
 ('duplicate_qualified_type', {'A.java':'class Dupe {}','B.java':'class Dupe {}'}, 'duplicate class'),
 ('nonexhaustive_sealed_switch', {'Case.java':'sealed interface Choice permits A,B {} record A() implements Choice {} record B() implements Choice {} class Case { static int f(Choice c){return switch(c){case A a -> 1;};} }'}, 'does not cover all possible input values'),
]

def run():
 rows=[]
 with tempfile.TemporaryDirectory(prefix='codefit-v21-neg-') as root:
  for case,files,fragment in CASES:
   p=Path(root)/case;p.mkdir()
   for name,source in files.items():(p/name).write_text(source,encoding='utf-8')
   out=p/'classes';out.mkdir()
   cmd=['javac','--release','21','-Xlint:all','-d',str(out),*map(str, sorted(p.glob('*.java')))];r=subprocess.run(cmd,capture_output=True,text=True,timeout=15)
   passed=r.returncode!=0 and fragment.lower() in r.stderr.lower()
   rows.append({'case':case,'rejected':r.returncode!=0,'diagnostic_matched':fragment,'passed':passed})
 assert all(x['passed'] for x in rows), json.dumps([x for x in rows if not x['passed']])
 result={'schema':'codefit-author-review-negative/v0.21','toolchain':'javac --release 21','expected_rejections':len(rows),'passed':len(rows),'independent_review':False,'cases':rows}
 (ROOT/'output/deep_v21').mkdir(exist_ok=True)
 (ROOT/'output/deep_v21/negative_javac_v0.21.json').write_text(json.dumps(result,indent=2)+'\n')
 return result
if __name__=='__main__':
 print('AUTHOR_REVIEW_NEGATIVE_JAVA21='+str(run()['passed']))
