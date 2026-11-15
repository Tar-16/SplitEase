"""One bounded, disposable posting proof; actual Auth/Firestore Emulator REST clients.

Reuses the earlier POC's standard-library transport/Firestore value codec. No live fallback.
"""
import concurrent.futures, copy, importlib.util, json, time, uuid
from pathlib import Path

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('poc_transport',HERE.parent/'financial-ledger-poc'/'run.py')
rest=importlib.util.module_from_spec(spec);spec.loader.exec_module(rest)
rest.PROJECT='demo-splitease-posting-poc'
rest.BASE=f'/v1/projects/{rest.PROJECT}/databases/(default)/documents'
rest.NAME=f'projects/{rest.PROJECT}/databases/(default)/documents/'
raw_http=rest.http;REQUESTS=[];RESULTS=[];BUDGETS=[];REJECTIONS=0;LABEL='setup'
class Blocker(Exception): pass
def http(method,path,body=None,token=None,auth=False):
    start=time.monotonic();result=raw_http(method,path,body,token,auth)
    if path.endswith(':commit'):
        actions=[]
        for w in body['writes']:
            fields=w.get('update',{}).get('fields',{});actions.append(fields.get('action',{}).get('stringValue','account/head/fixture'))
        REQUESTS.append({'scenario':LABEL,'actions':actions,'writes':len(body['writes']),'status':result[0],'ms':round((time.monotonic()-start)*1000,3)})
    return result
rest.http=http
write=rest.write
def commit(w,token=None): return rest.commit(w,token or TOKENS[0])
def get(path,token=None): return rest.get(path,token or TOKENS[0])
def budget(response): return 'maximum of 1000' in json.dumps(response) or 'Service call error' in json.dumps(response)
def ok(response):
    if budget(response): raise Blocker(json.dumps(response))
    assert response[0]==200,response
def reject(response):
    global REJECTIONS
    if budget(response): raise Blocker('Adversarial guard reached a budget/service error; not counted as semantic validation: '+json.dumps(response))
    assert response[0]==403,('Attack accepted or unexpected failure',response);REJECTIONS+=1
def account(g,u): return f'groups/{g}/accounts/{u}'
def record_path(g,id): return f'groups/{g}/records/{id}'
def zero(g,u): return {'gid':g,'uid':u,'net':0,'active':True,'rev':0,'markSeq':0,'markStep':-1}
def initial(): return {'admin':UIDS[0],'creator':UIDS[0],'rev':0,'phase':'IDLE','action':'INIT','target':'','total':0,'gross':0,'op':None,'checked':0,'cursor':0,'diag':0,'expr':0}
def group():
    g=str(uuid.uuid4());ok(commit([write('groups/'+g,initial()),write(account(g,UIDS[0]),zero(g,UIDS[0]))]));return g
def move(g,u,join=True,token=None):
    p=get('groups/'+g);p['rev']+=1;p['action']='JOIN' if join else 'LEAVE';p['target']=u
    status,_=http('GET',rest.BASE+'/'+account(g,u),token=token or TOKENS[0])
    a=zero(g,u) if status==404 else get(account(g,u));
    if status!=404:a['active']=join;a['rev']+=1
    return [write('groups/'+g,p),write(account(g,u),a)]
def join(g,index): ok(commit(move(g,UIDS[index])))
def leave(g,index): ok(commit(move(g,UIDS[index],False),TOKENS[index]))
def spend(g,amount=101,indices=(0,1,2),payer=0):
    ids=sorted(UIDS[i] for i in indices);q,r=divmod(amount,len(ids))
    return {'gid':g,'kind':'expense','payer':UIDS[payer],'payee':'','amount':amount,'date':0,'participants':ids,'shares':{u:q+(i<r) for i,u in enumerate(ids)},'rev':1,'deleted':False,'creator':UIDS[0]}
def payment(g,amount=10,payer=1,payee=0,actor=1):
    return {'gid':g,'kind':'settlement','payer':UIDS[payer],'payee':UIDS[payee],'amount':amount,'date':0,'participants':[],'shares':{},'rev':1,'deleted':False,'creator':UIDS[actor]}
def begin_w(g,r,id=None,before=None,token=None):
    token=token or TOKENS[0];p=get('groups/'+g);p['rev']+=1;p['action']='BEGIN';p['target']='';id=id or str(uuid.uuid4());r=copy.deepcopy(r)
    if before is not None:r['rev']=before['rev']+1
    p['op']={'id':id,'seq':p['rev'],'actor':UIDS[TOKENS.index(token)],'before':copy.deepcopy(before),'after':r}
    p['checked']=0;p['cursor']=0;p['phase']='PREPARE' if r['kind']=='expense' and not r['deleted'] else 'APPLY'
    return id,[write('groups/'+g,p)]
def begin(g,r,id=None,before=None,token=None):
    id,w=begin_w(g,r,id,before,token);ok(commit(w,token));return id
def prep_w(g):
    p=get('groups/'+g);p['rev']+=1;p['action']='CHECK';p['checked']+=1
    p['phase']='APPLY' if p['checked']==len(p['op']['after']['participants'])+1 else 'PREPARE'
    return [write('groups/'+g,p)]
def prepare(g,token=None):
    while get('groups/'+g)['phase']=='PREPARE':ok(commit(prep_w(g),token))
def pieces(r):
    if r is None or r['deleted']:return []
    if r['kind']=='expense':return [(r['payer'],r['amount'])]+[(u,-r['shares'][u]) for u in r['participants']]
    return [(r['payer'],r['amount']),(r['payee'],-r['amount'])]
def post_w(g,pad=0,expr=0):
    p=get('groups/'+g);op=p['op'];seq=[(u,-d) for u,d in pieces(op['before'])]+pieces(op['after']);u,delta=seq[p['cursor']];a=get(account(g,u))
    a['net']+=delta;a['rev']+=1;a['markSeq']=op['seq'];a['markStep']=p['cursor']
    p['rev']+=1;p['action']='POST';p['cursor']+=1;p['diag']=pad;p['expr']=expr
    return [write('groups/'+g,p),write(account(g,u),a)]
def money(r,kind):return r['amount'] if r is not None and r['kind']==kind and not r['deleted'] else 0
def finish_w(g):
    p=get('groups/'+g);op=p['op'];p['rev']+=1;p['action']='FINISH';p['phase']='IDLE';p['op']=None;p['checked']=0;p['cursor']=0;p['target']=''
    p['total']+=money(op['after'],'expense')-money(op['before'],'expense');p['gross']+=money(op['after'],'settlement')-money(op['before'],'settlement')
    return [write('groups/'+g,p),write(record_path(g,op['id']),op['after'])]
def resume(g,token=None,verify_finish=False):
    prepare(g,token)
    p=get('groups/'+g);n=len(pieces(p['op']['before']))+len(pieces(p['op']['after']))
    while get('groups/'+g)['cursor']<n:ok(commit(post_w(g),token))
    w=finish_w(g)
    if verify_finish:reject_unchanged(g,w[:1],token=token);reject_unchanged(g,w[1:],token=token)
    ok(commit(w,token))
def publish(g,r,id=None,before=None,token=None):
    id=begin(g,r,id,before,token);resume(g,token);return id,get(record_path(g,id))
def abort_w(g):
    p=get('groups/'+g);p.update(rev=p['rev']+1,action='ABORT',phase='IDLE',op=None,checked=0,cursor=0,target='');return [write('groups/'+g,p)]
def nets(g,indices):return {UIDS[i]:get(account(g,UIDS[i]))['net'] for i in indices}
def reject_unchanged(g,w,indices=(0,1,2),token=None):
    before=get('groups/'+g);accounts={i:get(account(g,UIDS[i])) for i in indices};reject(commit(w,token));assert get('groups/'+g)==before
    assert {i:get(account(g,UIDS[i])) for i in indices}==accounts
def modified(w,index,field,value):
    w=copy.deepcopy(w);w[index]['update']['fields'][field]=rest.enc(value);return w
def coverage(label):
    status,data=http('GET',f'/emulator/v1/projects/{rest.PROJECT}:ruleCoverage');assert status==200
    Path('/tmp/posting-poc-coverage-'+label+'.json').write_text(json.dumps(data))
def bootstrap():
    g=str(uuid.uuid4());bad=zero(g,UIDS[0]);bad['net']=1
    reject(commit([write('groups/'+g,initial()),write(account(g,UIDS[0]),bad)]))
    reject(commit([write(account(g,UIDS[0]),zero(g,UIDS[0]))]));reject(commit([write('groups/'+g,initial())]))
    g=group();join(g,1);a=get(account(g,UIDS[1]));a['net']=100
    reject(commit([write(account(g,UIDS[1]),a)]));reject(commit([{'delete':rest.NAME+account(g,UIDS[1])}]))
    # A non-admin cannot assign membership, and new-account value must be exactly zero.
    reject(commit(move(g,UIDS[2]),TOKENS[1]));w=move(g,UIDS[2]);reject(commit(modified(w,1,'net',1)))
    join(g,2)
    w=move(g,UIDS[1],False);w=modified(w,1,'net',0);ok(commit(w,TOKENS[1]))
    assert not get(account(g,UIDS[1]))['active'];join(g,1)
    assert get(account(g,UIDS[1]))['net']==0
    return g
def posting():
    global MAIN,EXPENSE,OLD
    g=MAIN;id=begin(g,spend(g));EXPENSE=id
    p=get('groups/'+g);w=prep_w(g);reject_unchanged(g,modified(w,0,'checked',2))
    bad=copy.deepcopy(p['op']);bad['after']['amount']=102;reject_unchanged(g,modified(w,0,'op',bad))
    prepare(g)
    w=post_w(g);reject_unchanged(g,w[:1]);reject_unchanged(g,w[1:]);reject_unchanged(g,modified(w,1,'net',102));reject_unchanged(g,modified(w,0,'cursor',2))
    wrong=copy.deepcopy(w);wrong[1]['update']['name']=rest.NAME+account(g,UIDS[2]);wrong[1]['update']['fields']['uid']=rest.enc(UIDS[2]);reject_unchanged(g,wrong)
    reject_unchanged(g,finish_w(g));reject_unchanged(g,abort_w(g))
    # Same ID/revision, but arbitrary new operation during APPLY.
    _,blocked=begin_w(g,spend(g,8));reject_unchanged(g,blocked)
    ok(commit(w));reject_unchanged(g,w) # replay after acknowledgement
    assert get('groups/'+g)['cursor']==1 and get(account(g,UIDS[0]))['net']==101
    join_index=3
    reject_unchanged(g,move(g,UIDS[join_index]),indices=(0,1,2))
    # Simulate the initiating client disappearing; another current member finishes.
    resume(g,TOKENS[2],verify_finish=True);OLD=get(record_path(g,id))
    assert nets(g,(0,1,2))=={UIDS[0]:67,UIDS[1]:-34,UIDS[2]:-33}
    assert get('groups/'+g)['total']==101 and get('groups/'+g)['phase']=='IDLE'
    reject_unchanged(g,move(g,UIDS[1],False),token=TOKENS[1])
    reject(commit([write(record_path(g,id),spend(g,1))]))
def capacity_history():
    g=MAIN
    for amount in (0,-1,35):
        _,w=begin_w(g,payment(g,amount),token=TOKENS[1]);reject_unchanged(g,w,token=TOKENS[1])
    _,w=begin_w(g,payment(g,1,actor=2),token=TOKENS[2]);reject_unchanged(g,w,token=TOKENS[2])
    id,old=publish(g,payment(g,10),token=TOKENS[1]);assert get(account(g,UIDS[1]))['net']==-24
    corrected=copy.deepcopy(old);corrected['amount']=20;corrected['date']=86400000
    id,record=publish(g,corrected,id,old,TOKENS[2]);assert get(account(g,UIDS[1]))['net']==-14
    for field,value in [('amount',35),('payer',UIDS[2]),('date',-1)]:
        bad=copy.deepcopy(record);bad[field]=value;_,w=begin_w(g,bad,id,record,TOKENS[2]);reject_unchanged(g,w,token=TOKENS[2])
    publish(g,payment(g,14,actor=0));assert get(account(g,UIDS[1]))['net']==0
    leave(g,1);left=get(account(g,UIDS[1]));assert not left['active']
    # An authorized historical edit may recreate a former member's balance.
    changed=spend(g,104);changed['creator']=OLD['creator'];publish(g,changed,EXPENSE,OLD)
    after=get(account(g,UIDS[1]));assert not after['active'] and after['net']==-1
    _,w=begin_w(g,spend(g,1));reject(commit(w,TOKENS[1]))
    w=move(g,UIDS[1]);reject(commit(modified(w,1,'net',0)));join(g,1);assert get(account(g,UIDS[1]))['net']==-1
def membership_ninth():
    global LARGE,LARGE_RECORD
    g=group();LARGE=g
    for i in range(1,8):join(g,i)
    leave(g,6);leave(g,7);before=[get(account(g,UIDS[i])) for i in range(8)]
    join(g,8);assert len([get(account(g,u)) for u in UIDS[:9]])==9
    assert get(account(g,UIDS[6]))==before[6] and get(account(g,UIDS[7]))==before[7]
    for i in (6,7,9,10,11):join(g,i)
    id,old=publish(g,spend(g,101,range(8)));LARGE_RECORD=id
    new=spend(g,207,range(4,12),payer=11);publish(g,new,id,old)
    expected={u:0 for u in UIDS}
    for u,d in pieces(new):expected[u]+=d
    assert nets(g,range(12))==expected and sum(expected.values())==0
def invalid_prepare():
    g=group();join(g,1);join(g,2);join(g,3);leave(g,3)
    begin(g,spend(g,3,(0,1,3)));ok(commit(prep_w(g)));ok(commit(prep_w(g)));ok(commit(prep_w(g)))
    reject(commit(prep_w(g)));assert all(n==0 for n in nets(g,range(4)).values());ok(commit(abort_w(g)))
    assert get('groups/'+g)['phase']=='IDLE'
    bad=spend(g,3,(0,1,2));bad['shares'][UIDS[2]]=1.5;begin(g,bad)
    for _ in range(3):ok(commit(prep_w(g)))
    reject(commit(prep_w(g)));ok(commit(abort_w(g)));assert all(n==0 for n in nets(g,range(4)).values())
    # Leaving is denied even for a zero-net member while an otherwise valid operation is incomplete.
    begin(g,spend(g));reject(commit(move(g,UIDS[2],False),TOKENS[2]));resume(g)
def concurrency():
    g=group();join(g,1);join(g,2)
    one,w1=begin_w(g,spend(g,10));two,w2=begin_w(g,spend(g,11))
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        replies=list(pool.map(commit,[w1,w2]))
    assert sorted(r[0] for r in replies)==[200,403],replies
    ok(commit(abort_w(g)));id,old=publish(g,spend(g,10));new=spend(g,13);_,record=publish(g,new,id,old)
    _,w=begin_w(g,spend(g,14),id,old);reject(commit(w));assert get(record_path(g,id))==record
    join(g,3);_,wbegin=begin_w(g,spend(g,1,(3,),payer=0));wleave=move(g,UIDS[3],False)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        replies=list(pool.map(lambda x:commit(x[0],x[1]),[(wbegin,TOKENS[0]),(wleave,TOKENS[3])]))
    assert sorted(r[0] for r in replies)==[200,403],replies
    if get('groups/'+g)['phase']=='PREPARE':resume(g)
    assert get('groups/'+g)['phase']=='IDLE'
def measured_budgets():
    ok(commit([write('probes/'+str(i),{'ok':True}) for i in range(32)],'owner'))
    for n,expected in [(10,200),(11,403)]:
        response=commit([write('access/'+str(uuid.uuid4()),{'n':n,'base':0})]);assert response[0]==expected;BUDGETS.append({'probe':'single-write-get','n':n,'status':response[0]})
    for n,bases,expected in [(10,(0,10),200),(8,(0,8,16),403)]:
        response=commit([write('access/'+str(uuid.uuid4()),{'n':n,'base':base}) for base in bases]);assert response[0]==expected;BUDGETS.append({'probe':'batch-get','n':n*len(bases),'status':response[0]})
    # A real expense edit has 18 components, enough for finite padding controls without retries.
    g=LARGE
    # Keep explicit fixture head reference; no unbounded query or admin-seeded finance state.
    old=get(record_path(g,LARGE_RECORD));r=copy.deepcopy(old);r['amount']=208;q,rem=divmod(208,8);r['shares']={u:q+(i<rem) for i,u in enumerate(r['participants'])}
    begin(g,r,LARGE_RECORD,old);prepare(g)
    for n in (0,7,8,9,10,11):
        before=get('groups/'+g);response=commit(post_w(g,pad=n));BUDGETS.append({'probe':'actual-post-extra-distinct-gets','n':n,'status':response[0],'error':response[1].get('error',{}).get('message','')[:1800]})
        if response[0]!=200:assert response[0]==403 and get('groups/'+g)==before;break
    for n in (0,1,2,4,8,16):
        before=get('groups/'+g);response=commit(post_w(g,expr=n));BUDGETS.append({'probe':'actual-post-extra-16-comparison-chunks','n':n,'status':response[0],'error':response[1].get('error',{}).get('message','')[:1800]})
        if response[0]!=200:assert response[0]==403 and get('groups/'+g)==before;break
    resume(g)

def setup():
    global UIDS,TOKENS
    UID_TOKENS={}
    for i in range(12):
        fixture={'email':f'posting-{i}@example.test','password':'Synthetic1!','returnSecureToken':True}
        status,user=http('POST','/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo-posting-key',fixture,auth=True)
        if status!=200:status,user=http('POST','/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-posting-key',fixture,auth=True)
        assert status==200,user.get('error');UID_TOKENS[user['localId']]=user['idToken']
    UIDS=sorted(UID_TOKENS);TOKENS=[UID_TOKENS[u] for u in UIDS]
    for u,t in zip(UIDS,TOKENS):
        status,_=http('GET',rest.BASE+'/identities/'+u,token=t)
        if status==404:ok(commit([write('identities/'+u,{'uid':u})],t))

if __name__=='__main__':
    start=time.monotonic();setup()
    coverage('before');stopped=False
    cases=[('initialization_membership_authorization',bootstrap),('exact_posting_attacks_interruption_recovery',posting),('settlement_capacity_and_historical_accounts',capacity_history),('ninth_lifetime_member_and_twelve_identity_edit',membership_ninth),('invalid_candidate_abort_and_incomplete_leave',invalid_prepare),('concurrent_operations_stale_edit_and_leave_race',concurrency),('actual_rules_budgets',measured_budgets)]
    for label,fn in cases:
        LABEL=label
        if stopped:RESULTS.append({'name':label,'status':'NOT_RUN'});continue
        then=time.monotonic()
        try:
            value=fn()
            if label==cases[0][0]:MAIN=value
            if label==cases[3][0]:pass
            RESULTS.append({'name':label,'status':'PASS','seconds':round(time.monotonic()-then,3)});print('PASS',label,flush=True)
        except Exception as error:
            RESULTS.append({'name':label,'status':'BLOCKED' if isinstance(error,Blocker) else 'FAIL','seconds':round(time.monotonic()-then,3),'error':str(error)[:4000]});print(RESULTS[-1],flush=True);stopped=True
    coverage('after')
    out={'project':rest.PROJECT,'seconds':round(time.monotonic()-start,3),'results':RESULTS,'semantic_rejections':REJECTIONS,'budgets':BUDGETS,'commit_requests':REQUESTS}
    Path('/tmp/posting-poc-results.json').write_text(json.dumps(out,indent=2));print(json.dumps({k:v for k,v in out.items() if k!='commit_requests'},indent=2),flush=True)
    raise SystemExit(1 if stopped else 0)
