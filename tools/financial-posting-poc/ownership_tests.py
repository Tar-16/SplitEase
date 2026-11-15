"""Focused normal-token ownership attacks; stops at the first accepted attack or budget blocker."""
import concurrent.futures,copy,importlib.util,json,time,uuid
from pathlib import Path
HERE=Path(__file__).resolve().parent
def module(name,file):
    spec=importlib.util.spec_from_file_location(name,HERE/file);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
p=module('posting','run.py');race=module('collision','cross_group_race.py')
def rejects(g,w,token=None):p.reject_unchanged(g,w,indices=(0,1),token=token)
def same_path_concurrent_claims():
    g=p.group();p.join(g,1);id=str(uuid.uuid4())
    _,one=p.begin_w(g,p.spend(g,11,(1,)),id)
    other=p.spend(g,13,(1,));other['creator']=p.UIDS[1];_,two=p.begin_w(g,other,id,token=p.TOKENS[1])
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        replies=list(pool.map(lambda x:p.commit(x[0],x[1]),[(one,p.TOKENS[0]),(two,p.TOKENS[1])]))
    assert sorted(r[0] for r in replies)==[200,403],replies
    winner=p.get('groups/'+g)['op'];p.resume(g);head=p.get(p.record_path(g,id));assert head==winner['after'] and head['creator']==winner['actor']
    _,duplicate=p.begin_w(g,p.spend(g,15,(1,)),id);rejects(g,duplicate)
    before=copy.deepcopy(head);stale=p.spend(g,head['amount']+1,(1,));stale['creator']=head['creator']
    _,edit=p.begin_w(g,stale,id,head);p.ok(p.commit(edit));p.prepare(g)
    # Immutable command and claim sequence cannot be replaced after BEGIN.
    w=p.post_w(g);bad=copy.deepcopy(p.get('groups/'+g)['op']);bad['seq']+=1;rejects(g,p.modified(w,0,'op',bad))
    p.resume(g);assert p.get(p.record_path(g,id))['rev']==before['rev']+1
def foreign_continuation_and_forged_owner():
    a=p.group();b=p.group();p.join(a,1);p.join(b,2);id=str(uuid.uuid4())
    bad=p.spend(a,7,(1,));bad['gid']=b;_,w=p.begin_w(a,bad,id);rejects(a,w)
    p.begin(a,p.spend(a,19,(1,)),id);p.begin(b,p.spend(b,23,(2,)),id)
    w=p.prep_w(a);p.reject(p.commit(w,p.TOKENS[2]));p.prepare(a);p.prepare(b)
    # B's active command cannot be substituted into A, even with matching UUIDs.
    w=p.post_w(a);foreign=copy.deepcopy(p.get('groups/'+b)['op']);rejects(a,p.modified(w,0,'op',foreign))
    wrong=copy.deepcopy(w);wrong[1]['update']['name']=p.rest.NAME+p.account(b,p.UIDS[0]);wrong[1]['update']['fields']['gid']=p.rest.enc(b);rejects(a,wrong)
    p.reject(p.commit(w,p.TOKENS[2]));p.resume(a,p.TOKENS[1]);p.resume(b,p.TOKENS[2])
    assert p.get(p.record_path(a,id))['amount']==19 and p.get(p.record_path(b,id))['amount']==23
def aborted_and_interrupted_claims():
    g=p.group();p.join(g,1);p.join(g,2);id=str(uuid.uuid4())
    p.begin(g,p.spend(g,17,(1,)),id);old_seq=p.get('groups/'+g)['op']['seq'];old_prepare=p.prep_w(g)
    p.ok(p.commit(p.abort_w(g)));assert p.nets(g,(0,1,2))=={u:0 for u in p.UIDS[:3]}
    status,_=p.http('GET',p.rest.BASE+'/'+p.record_path(g,id),token=p.TOKENS[0]);assert status==404
    p.begin(g,p.spend(g,29,(1,)),id);assert p.get('groups/'+g)['op']['seq']>old_seq
    p.reject(p.commit(old_prepare));p.prepare(g);first=p.post_w(g);p.ok(p.commit(first));p.reject(p.commit(first));p.reject(p.commit(p.abort_w(g)))
    p.resume(g,p.TOKENS[2],verify_finish=True)
    assert p.get(p.record_path(g,id))['amount']==29 and p.get(p.account(g,p.UIDS[1]))['net']==-29
def immutable_revision_and_tombstone():
    g=p.group();p.join(g,1);id,head=p.publish(g,p.spend(g,31,(1,)))
    p.begin(g,p.spend(g,37,(1,)),id,head);p.prepare(g)
    while p.get('groups/'+g)['cursor']<4:p.ok(p.commit(p.post_w(g)))
    w=p.finish_w(g)
    for field,value in [('gid',str(uuid.uuid4())),('rev',head['rev']),('creator',p.UIDS[1])]:rejects(g,p.modified(w,1,field,value))
    p.ok(p.commit(w));head=p.get(p.record_path(g,id));assert head['rev']==2
    tomb=copy.deepcopy(head);tomb['deleted']=True;p.publish(g,tomb,id,head)
    saved=p.get(p.record_path(g,id));assert saved['deleted'] and saved['rev']==3 and p.get('groups/'+g)['total']==0
    p.reject(p.commit([{'delete':p.rest.NAME+p.record_path(g,id)}]))
    _,w=p.begin_w(g,p.spend(g,1,(1,)),id);rejects(g,w)
    restored=copy.deepcopy(saved);restored['deleted']=False;_,w=p.begin_w(g,restored,id,saved);rejects(g,w)
    p.reject(p.commit([p.write(p.record_path(g,id),p.spend(g,1,(1,)))]))
    assert p.get(p.record_path(g,id))==saved and p.nets(g,(0,1))=={u:0 for u in p.UIDS[:2]}

if __name__=='__main__':
    start=time.monotonic();p.setup();results=[]
    cases=[('former_cross_group_collision',lambda:race.run_case(p)),('same_scoped_record_concurrent_claims',same_path_concurrent_claims),('foreign_operation_and_owner_forgery',foreign_continuation_and_forged_owner),('abort_release_and_interrupted_resume',aborted_and_interrupted_claims),('immutable_revision_deletion_and_recreation',immutable_revision_and_tombstone)]
    p.coverage('ownership-before');failed=False
    for name,fn in cases:
        p.LABEL=name
        if failed:results.append({'name':name,'status':'NOT_RUN'});continue
        then=time.monotonic()
        try:fn();results.append({'name':name,'status':'PASS','seconds':round(time.monotonic()-then,3)});print('PASS',name,flush=True)
        except Exception as e:results.append({'name':name,'status':'FAIL','error':str(e)[:4000]});failed=True;print(results[-1],flush=True)
    p.coverage('ownership-after')
    out={'seconds':round(time.monotonic()-start,3),'results':results,'semantic_rejections':p.REJECTIONS,'commit_requests':p.REQUESTS}
    Path('/tmp/posting-owner-focused.json').write_text(json.dumps(out,indent=2));print(json.dumps({k:v for k,v in out.items() if k!='commit_requests'},indent=2))
    raise SystemExit(1 if failed else 0)
