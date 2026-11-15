"""Bounded REST clients against disposable Auth/Firestore emulators. Standard library only.

No live fallback. Tokens remain in memory; output contains only synthetic results/metrics.
"""
import copy, json, time, uuid, urllib.request, urllib.error, concurrent.futures
from pathlib import Path

PROJECT='demo-splitease-ledger-poc'
HOST='http://127.0.0.1:8180'
BASE=f'/v1/projects/{PROJECT}/databases/(default)/documents'
NAME=f'projects/{PROJECT}/databases/(default)/documents/'
RESULTS=[]
DENIAL_LIMITS=[]
class Blocker(Exception): pass

def http(method,path,body=None,token=None,auth=False):
    host='http://127.0.0.1:9199' if auth else HOST
    assert host in ('http://127.0.0.1:8180','http://127.0.0.1:9199')
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    req=urllib.request.Request(host+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=12) as response: return response.status,json.load(response)
    except urllib.error.HTTPError as e:
        data=json.loads(e.read()); message=json.dumps(data)
        return e.code,data

def enc(v):
    if isinstance(v,bool): return {'booleanValue':v}
    if isinstance(v,int): return {'integerValue':str(v)}
    if isinstance(v,float): return {'doubleValue':v}
    if isinstance(v,str): return {'stringValue':v}
    if v is None: return {'nullValue':None}
    if isinstance(v,list): return {'arrayValue':{'values':[enc(x) for x in v]}}
    return {'mapValue':{'fields':{k:enc(x) for k,x in v.items()}}}
def dec(v):
    if 'integerValue' in v: return int(v['integerValue'])
    if 'mapValue' in v: return {k:dec(x) for k,x in v['mapValue'].get('fields',{}).items()}
    if 'arrayValue' in v: return [dec(x) for x in v['arrayValue'].get('values',[])]
    return next(iter(v.values()))
def write(path,value,new=False):
    value=copy.deepcopy(value); transforms=[]
    for key in ('createdAt','updatedAt'):
        if key in value:
            if value[key]=='SERVER':
                del value[key];transforms.append({'fieldPath':key,'setToServerValue':'REQUEST_TIME'})
            else: value[key]={'__timestamp':value[key]}
    fields={k:({'timestampValue':v['__timestamp']} if isinstance(v,dict) and '__timestamp' in v else enc(v)) for k,v in value.items()}
    out={'update':{'name':NAME+path,'fields':fields}}
    if transforms: out['updateTransforms']=transforms
    if new: out['currentDocument']={'exists':False}
    return out
def commit(writes,token,transaction=None):
    body={'writes':writes}
    if transaction: body['transaction']=transaction
    return http('POST',BASE+':commit',body,token)
def get(path,token,transaction=None):
    status,data=http('GET',BASE+'/'+path+('?' + urllib.parse.urlencode({'transaction':transaction}) if transaction else ''),token=token)
    assert status==200,(status,data)
    return {k:dec(v) for k,v in data.get('fields',{}).items()}
def ok(response):
    if 'maximum of 1000' in json.dumps(response): raise Blocker(json.dumps(response))
    assert response[0]==200,response
def denied(response):
    assert response[0]==403,response
    if 'maximum of 1000' in json.dumps(response): DENIAL_LIMITS.append(json.dumps(response)[:2500])
def expect(name,fn):
    start=time.monotonic()
    try:
        fn(); RESULTS.append({'name':name,'status':'PASS','seconds':round(time.monotonic()-start,3)});print('PASS',name,flush=True)
    except Exception as e:
        RESULTS.append({'name':name,'status':'BLOCKED' if isinstance(e,Blocker) else 'FAIL','seconds':round(time.monotonic()-start,3),'error':str(e)[:4000]});print(RESULTS[-1],flush=True);return False
    return True

def plan(v,net): return {'base':v,'step':0,'work':net[:],'transfers':[],'ready':net==[0]*8}
def initial(g): return {'groupId':g,'version':0,'membershipRevision':1,'net':[0]*8,'total':0,'gross':0,'count':0,'kind':'init','id':'','plan':plan(0,[0]*8),'updatedAt':'SERVER'}
def group_data(): return {'creator':UIDS[0],'slots':UIDS[:],'members':UIDS[:],'revision':1}
def newgroup():
    g=str(uuid.uuid4());ok(commit([write('groups/'+g,group_data()),write('ledgers/'+g,initial(g))],TOKENS[0]));return g
def expense(g,amount=101,participants=None,payer=0):
    ids=sorted(UIDS if participants is None else participants);base,rem=divmod(amount,len(ids))
    return {'groupId':g,'kind':'expense','payer':UIDS[payer],'payee':'','d':payer,'c':0,'amount':amount,'date':0,'participants':ids,'shares':{u:base+(i<rem) for i,u in enumerate(ids)},'creator':UIDS[0],'title':'Synthetic meal','description':'POC','category':'Food','revision':1,'ledgerVersion':1,'deleted':False,'createdAt':'SERVER','updatedAt':'SERVER'}
def payment(g,amount=5,d=1,c=0,actor=0):
    return {'groupId':g,'kind':'settlement','payer':UIDS[d],'payee':UIDS[c],'d':d,'c':c,'amount':amount,'date':0,'participants':[],'shares':{},'creator':UIDS[actor],'title':'Synthetic payment','description':'POC','category':'Other','revision':1,'ledgerVersion':1,'deleted':False,'createdAt':'SERVER','updatedAt':'SERVER'}
def effect(r):
    a=[0]*8
    if r is None or r['deleted']: return a
    a[r['d']]+=r['amount']
    if r['kind']=='expense':
        for u,share in r['shares'].items(): a[UIDS.index(u)]-=share
    else: a[r['c']]-=r['amount']
    return a
def transition(g,id,r,token=None,old=None,ledger=None):
    token=token or TOKENS[0];ledger=ledger or get('ledgers/'+g,token);p=copy.deepcopy(ledger);r=copy.deepcopy(r)
    r['ledgerVersion']=p['version']+1;r['revision']=1 if old is None else old['revision']+1;r['updatedAt']='SERVER'
    a,b=effect(old),effect(r);p['net']=[x-y+z for x,y,z in zip(p['net'],a,b)]
    for kind,key in [('expense','total'),('settlement','gross')]:
        p[key]+=(r['amount'] if r['kind']==kind and not r['deleted'] else 0)-(old['amount'] if old is not None and old['kind']==kind and not old['deleted'] else 0)
    p['count']+=old is None;p['version']+=1;p['kind']=r['kind'];p['id']=id;p['plan']=plan(p['version'],p['net']);p['updatedAt']='SERVER'
    return p,r,[write('ledgers/'+g,p),write('records/'+id,r)]
def put(g,r,old=None,id=None,token=None):
    id=id or str(uuid.uuid4());p,r,w=transition(g,id,r,old=old,token=token);ok(commit(w,token or TOKENS[0]));return id,get('records/'+id,token or TOKENS[0])
def nextstep(p):
    q=copy.deepcopy(p);w=q['plan']['work'];d=min(range(8),key=lambda i:(w[i],i));c=min(range(8),key=lambda i:(-w[i],i));amount=min(-w[d],w[c]);assert amount>0
    q['plan']['transfers'].append({'d':d,'c':c,'amount':amount});w[d]+=amount;w[c]-=amount;q['plan']['step']+=1;q['plan']['ready']=w==[0]*8;q['updatedAt']='SERVER';return q
def build(g):
    p=get('ledgers/'+g,TOKENS[0]);steps=0
    while not p['plan']['ready']:
        assert steps<7;p=nextstep(p);ok(commit([write('ledgers/'+g,p)],TOKENS[0]));p=get('ledgers/'+g,TOKENS[0]);steps+=1
    return p
def unchanged(g,w,token=None):
    before=get('ledgers/'+g,TOKENS[0]);denied(commit(w,token or TOKENS[0]));assert get('ledgers/'+g,TOKENS[0])==before
def coverage(label):
    status,data=http('GET',f'/emulator/v1/projects/{PROJECT}:ruleCoverage')
    Path('/tmp/ledger-poc-coverage-'+label+'.json').write_text(json.dumps(data))
    return status

def bootstrap_cases():
    g=newgroup();s=get('ledgers/'+g,TOKENS[0]);assert s['net']==[0]*8 and s['version']==0
    g=str(uuid.uuid4());a=initial(g);a['net']=[1,-1,0,0,0,0,0,0]
    denied(commit([write('groups/'+g,group_data()),write('ledgers/'+g,a)],TOKENS[0]))
    denied(commit([write('ledgers/'+g,initial(g))],TOKENS[0]))
    denied(commit([write('groups/'+g,group_data())],TOKENS[0]))
    g=newgroup();s=get('ledgers/'+g,TOKENS[0]);assert s['net']==[0]*8 and s['version']==0
    denied(commit([{'delete':NAME+'ledgers/'+g}],TOKENS[0]))
    denied(commit([write('ledgers/'+g,initial(g))],TOKENS[0]))
    g=str(uuid.uuid4());n=group_data();n['slots'].append('ninth');n['members']=n['slots']
    denied(commit([write('groups/'+g,n),write('ledgers/'+g,initial(g))],TOKENS[0]))

def valid_expenses():
    g=newgroup();r=expense(g);id,old=put(g,r);s=get('ledgers/'+g,TOKENS[0]);assert s['net']==effect(old) and s['total']==101 and s['count']==1
    new=expense(g,207,payer=4);new['creator']=old['creator'];new['createdAt']=old['createdAt'];id,new=put(g,new,old,id)
    s=get('ledgers/'+g,TOKENS[0]);assert s['net']==effect(new) and s['total']==207
    deleted=copy.deepcopy(new);deleted['deleted']=True;put(g,deleted,new,id);s=get('ledgers/'+g,TOKENS[0]);assert s['net']==[0]*8 and s['total']==0 and s['count']==1
    # Neither hard deletion nor resurrection is permitted.
    denied(commit([{'delete':NAME+'records/'+id}],TOKENS[0]))
    tomb=get('records/'+id,TOKENS[0]);_,_,w=transition(g,id,new,old=tomb);unchanged(g,w)

def malicious():
    g=newgroup();id=str(uuid.uuid4());p,r,w=transition(g,id,expense(g))
    unchanged(g,w[:1]);unchanged(g,w[1:])
    for key in ('total','gross','count'):
        bad=copy.deepcopy(p);bad[key]+=1;unchanged(g,[write('ledgers/'+g,bad),write('records/'+id,r)])
    for i in range(8):
        bad=copy.deepcopy(p);bad['net'][i]+=1;bad['net'][(i+1)%8]-=1;bad['plan']=plan(bad['version'],bad['net']);unchanged(g,[write('ledgers/'+g,bad),write('records/'+id,r)])
    bad=copy.deepcopy(r);bad['shares'][UIDS[7]]+=1;unchanged(g,[write('ledgers/'+g,p),write('records/'+id,bad)])
    bad=copy.deepcopy(r);bad['amount']=1.5;unchanged(g,[write('ledgers/'+g,p),write('records/'+id,bad)])
    ok(commit(w,TOKENS[0]));unchanged(g,w) # exact stale replay
    other=str(uuid.uuid4());bad=copy.deepcopy(r);bad['revision']=1
    p2,r2,w2=transition(g,str(uuid.uuid4()),expense(g,33));w2.append(write('records/'+other,bad));unchanged(g,w2)

def staged_plan():
    g=newgroup();put(g,expense(g));p=get('ledgers/'+g,TOKENS[0]);q=nextstep(p);assert q['plan']['transfers'][0]['d']==1
    variants=[]
    bad=copy.deepcopy(q);bad['plan']['transfers'][0]['d']=2;variants.append(bad)
    bad=copy.deepcopy(q);bad['plan']['transfers'][0]['amount']+=1;variants.append(bad)
    bad=copy.deepcopy(q);bad['plan']['ready']=True;variants.append(bad)
    bad=copy.deepcopy(q);bad['plan']['step']=2;variants.append(bad)
    bad=copy.deepcopy(q);bad['net'][7]+=1;variants.append(bad)
    bad=copy.deepcopy(q);bad['plan']['work'][7]+=1;variants.append(bad)
    for bad in variants: unchanged(g,[write('ledgers/'+g,bad)])
    finished=build(g);assert finished['plan']['step']==7 and [t['d'] for t in finished['plan']['transfers']]==list(range(1,8))
    before=copy.deepcopy(finished);id,record=put(g,payment(g,5));after=get('ledgers/'+g,TOKENS[0]);assert after['plan']['step']==0 and not after['plan']['ready']
    before['updatedAt']='SERVER';unchanged(g,[write('ledgers/'+g,before)])
    # Positive shape alone is insufficient: a fresh payment must wait for the new plan.
    _,_,w=transition(g,str(uuid.uuid4()),payment(g,1));unchanged(g,w)

def settlements():
    g=newgroup();put(g,expense(g));build(g)
    _,_,w=transition(g,str(uuid.uuid4()),payment(g,14));unchanged(g,w)
    id,old=put(g,payment(g,5));assert get('ledgers/'+g,TOKENS[0])['net'][1]==-8
    changed=copy.deepcopy(old);changed['amount']=10;changed['date']=86400000;id,new=put(g,changed,old,id,token=TOKENS[6]);assert get('ledgers/'+g,TOKENS[0])['net'][1]==-3
    for amount in (0,-1,14):
        bad=copy.deepcopy(new);bad['amount']=amount;_,_,w=transition(g,id,bad,old=new);unchanged(g,w)
    bad=copy.deepcopy(new);bad['payer']=UIDS[2];bad['d']=2;_,_,w=transition(g,id,bad,old=new);unchanged(g,w)
    bad=copy.deepcopy(new);bad['date']=-1;_,_,w=transition(g,id,bad,old=new);unchanged(g,w)

def concurrency():
    g=newgroup();transactions=[];drafts=[]
    for amount in (17,19):
        status,response=http('POST',BASE+':beginTransaction',{'options':{'readWrite':{}}},TOKENS[0]);assert status==200,response
        tx=response['transaction'];s=get('ledgers/'+g,TOKENS[0],tx);get('groups/'+g,TOKENS[0],tx)
        id=str(uuid.uuid4());http('GET',BASE+'/records/'+id+'?'+urllib.parse.urlencode({'transaction':tx}),token=TOKENS[0]);transactions.append(tx)
        drafts.append(transition(g,id,expense(g,amount),ledger=s)[2])
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        responses=list(pool.map(lambda args:commit(args[0],TOKENS[0],args[1]),zip(drafts,transactions)))
    assert sorted(x[0] for x in responses)==[200,409],responses
    assert get('ledgers/'+g,TOKENS[0])['count']==1

def historical():
    g=newgroup();id,old=put(g,expense(g));s=get('ledgers/'+g,TOKENS[0]);group=get('groups/'+g,TOKENS[0]);group['members'].remove(UIDS[7]);group['revision']=2;s['membershipRevision']=2;s['updatedAt']='SERVER'
    # Trusted legacy-departure fixture ONLY; this does not test a leave workflow.
    ok(commit([write('groups/'+g,group),write('ledgers/'+g,s)],'owner'))
    changed=expense(g,201);changed['createdAt']=old['createdAt'];id,new=put(g,changed,old,id);assert get('ledgers/'+g,TOKENS[0])['net'][7]==-25
    _,_,w=transition(g,str(uuid.uuid4()),expense(g,8));unchanged(g,w)
    _,_,w=transition(g,id,changed,old=new);unchanged(g,w,TOKENS[7])
    p,r,w=transition(g,id,changed,old=new);p['net'][7]=0;p['net'][0]-=25;p['plan']=plan(p['version'],p['net']);unchanged(g,[write('ledgers/'+g,p),write('records/'+id,r)])

def limits():
    ok(commit([write('probes/'+str(i),{'ok':True}) for i in range(32)],'owner'))
    ok(commit([write('access/'+str(uuid.uuid4()),{'n':10,'base':0})],TOKENS[0]))
    denied(commit([write('access/'+str(uuid.uuid4()),{'n':11,'base':0})],TOKENS[0]))
    ok(commit([write('access/'+str(uuid.uuid4()),{'n':10,'base':i}) for i in (0,10)],TOKENS[0]))
    denied(commit([write('access/'+str(uuid.uuid4()),{'n':8,'base':i}) for i in (0,8,16)],TOKENS[0]))
    ok(commit([write('expression/'+str(uuid.uuid4()),{'mode':'low','x':1})],TOKENS[0]))
    try:
        response=commit([write('expression/'+str(uuid.uuid4()),{'mode':'high','x':1})],TOKENS[0]);denied(response)
        RESULTS.append({'name':'expression_saturation_diagnostic','status':'OBSERVED_LIMIT' if 'maximum of 1000' in json.dumps(response) else 'OBSERVED_DENIAL','error':str(response)[:2000]})
    except Blocker as e:
        RESULTS.append({'name':'expression_saturation_diagnostic','status':'OBSERVED_LIMIT','error':str(e)[:2000]})

if __name__=='__main__':
    import urllib.parse
    start=time.monotonic(); UID_TOKENS={}
    # Fixture users are created through actual Auth Emulator email/password endpoints.
    status,existing=http('GET',f'/emulator/v1/projects/{PROJECT}/accounts',token='owner',auth=True)
    existing=existing.get('users',[]) if status==200 else []
    for i in range(8):
        email=existing[i]['email'] if len(existing)>i else f'ledger-{i}@example.test'
        action='signInWithPassword' if len(existing)>i else 'signUp'
        status,user=http('POST',f'/identitytoolkit.googleapis.com/v1/accounts:{action}?key=demo-poc-key',{'email':email,'password':'Synthetic1!','returnSecureToken':True},auth=True)
        assert status==200,(status,user.get('error'));UID_TOKENS[user['localId']]=user['idToken']
    UIDS=sorted(UID_TOKENS);TOKENS=[UID_TOKENS[u] for u in UIDS]
    for uid,token in zip(UIDS,TOKENS):
        status,_=http('GET',BASE+'/identities/'+uid,token=token)
        if status==404: ok(commit([write('identities/'+uid,{'uid':uid})],token))
    cases=[('zero_bootstrap_and_forged_initialization',bootstrap_cases),('eight_slot_expense_create_edit_delete',valid_expenses),('unpaired_forged_stale_and_multiple_records',malicious),('staged_greedy_ties_exact_steps_and_invalidation',staged_plan),('partial_settlement_and_edit_ceiling',settlements),('concurrent_transactions',concurrency),('departed_member_integrity',historical)]
    coverage('before')
    stopped=False
    for name,fn in cases:
        if stopped: RESULTS.append({'name':name,'status':'NOT_RUN'});continue
        if not expect(name,fn): stopped=True
    # Independent engine-limit diagnostics remain useful even if the ledger hits a fundamental budget blocker.
    expect('engine_access_and_expression_limits',limits)
    coverage('after')
    output={'project':PROJECT,'seconds':round(time.monotonic()-start,3),'results':RESULTS,'denials_hitting_expression_limit':DENIAL_LIMITS}
    Path('/tmp/ledger-poc-results.json').write_text(json.dumps(output,indent=2));print(json.dumps(output,indent=2),flush=True)
    raise SystemExit(1 if any(r['status'] in ('FAIL','BLOCKED') for r in RESULTS) else 0)
