"""Read-only emulator-owner diagnostic: independently reconstruct every committed group's accounts.

This bypasses read ACLs only to enumerate synthetic fixtures. It never writes or authorizes finance.
"""
import importlib.util,json
from pathlib import Path
spec=importlib.util.spec_from_file_location('posting',Path(__file__).with_name('run.py'))
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
def docs(path):
    code,data=p.http('GET',p.rest.BASE+'/'+path,token='owner');assert code==200
    return [(d['name'].split('/')[-1],{k:p.rest.dec(v) for k,v in d['fields'].items()}) for d in data.get('documents',[])]
summaries=[];record_count=0
for gid,g in docs('groups'):
    records=docs('groups/'+gid+'/records');record_count+=len(records)
    accounts=dict(docs('groups/'+gid+'/accounts'));expected={u:0 for u in accounts};spending=gross=0
    assert g['phase']=='IDLE' and g['op'] is None and g['cursor']==0 and g['checked']==0
    for _,r in records:
        if r['gid']!=gid or r['deleted']:continue
        expected[r['payer']]+=r['amount']
        if r['kind']=='expense':
            ids=r['participants'];base,remainder=divmod(r['amount'],len(ids))
            assert ids==sorted(set(ids))
            assert r['shares']=={u:base+(i<remainder) for i,u in enumerate(ids)}
            assert sum(r['shares'].values())==r['amount']
            for u,share in r['shares'].items():expected[u]-=share
            spending+=r['amount']
        else:expected[r['payee']]-=r['amount'];gross+=r['amount']
    assert expected=={u:a['net'] for u,a in accounts.items()}
    assert sum(expected.values())==0 and spending==g['total'] and gross==g['gross']
    summaries.append({'retained_accounts':len(accounts),'left_accounts':sum(not a['active'] for a in accounts.values()),'account_nets_match_heads':True,'zero_sum':True,'totals_match':True,'idle':True})
out={'groups':summaries,'record_count':record_count,'all_consistent':True}
Path('/tmp/posting-poc-audit.json').write_text(json.dumps(out,indent=2));print(json.dumps(out,indent=2))
