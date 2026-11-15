"""Regression for the former global-record collision. Both scoped records must survive."""
import importlib.util,json,uuid
from pathlib import Path
spec=importlib.util.spec_from_file_location('posting',Path(__file__).with_name('run.py'))
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
def run_case(p):
    a=p.group();b=p.group();p.join(a,1);p.join(b,1);shared=str(uuid.uuid4())
    # Both claim the same UUID, but the group+UUID document identities are distinct.
    p.begin(a,p.spend(a,101,(1,)),shared);p.begin(b,p.spend(b,203,(1,)),shared)
    p.resume(a);first=p.get(p.record_path(a,shared));assert first['gid']==a
    p.resume(b);after=p.get(p.record_path(a,shared));second=p.get(p.record_path(b,shared))
    assert after==first and second['gid']==b and second['amount']==203
    assert p.get(p.account(a,p.UIDS[0]))['net']==101 and p.get(p.account(b,p.UIDS[0]))['net']==203
    # The old global path is denied, and a foreign payload cannot overwrite A's head.
    p.reject(p.commit([p.write('records/'+shared,second)]))
    p.reject(p.commit([p.write(p.record_path(a,shared),second)]))
    out={'same_uuid_distinct_scoped_paths':True,'first_record_unchanged':True,'both_groups_idle':p.get('groups/'+a)['phase']==p.get('groups/'+b)['phase']=='IDLE','first_amount':first['amount'],'second_amount':second['amount']}
    Path('/tmp/posting-owner-cross-group.json').write_text(json.dumps(out,indent=2));return out
if __name__=='__main__':
    p.LABEL='scoped_cross_group_regression';p.setup();print(json.dumps(run_case(p),indent=2))
