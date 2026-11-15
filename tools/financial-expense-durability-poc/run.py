"""Disposable expense durability proof. Loopback demo project only; no SDK/live fallback.

Run once with the colocated emulators ready. Stop at the first baseline blocker.
All financial assertions use normal Auth emulator credentials, never admin bypass.
"""
import copy, importlib.util, json, time, uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('transport', HERE.parent/'financial-ledger-poc/run.py')
t = importlib.util.module_from_spec(spec); spec.loader.exec_module(t)
t.PROJECT = 'demo-splitease-expense-durability'
t.BASE = f'/v1/projects/{t.PROJECT}/databases/(default)/documents'
t.NAME = f'projects/{t.PROJECT}/databases/(default)/documents/'
RESULTS=[]; METRICS=[]; DENIALS=[]; START=time.monotonic()

def write(path, value):
    v=copy.deepcopy(value); transforms=[]; fields={}
    for k,x in v.items():
        if k in ('createdAt','updatedAt','joinedAt','finishedAt') and x is not None:
            if x=='SERVER': transforms.append({'fieldPath':k,'setToServerValue':'REQUEST_TIME'})
            else: fields[k]={'timestampValue':x}
        else: fields[k]=t.enc(x)
    w={'update':{'name':t.NAME+path,'fields':fields}}
    if transforms: w['updateTransforms']=transforms
    return w

def success(r):
    if r[0]!=200: raise RuntimeError(json.dumps(r)[:3500])
    return r[1]
def denied(r):
    assert r[0]==403, str(r)[:2000]
    # Invalid missing-pair reads can produce both null and budget diagnostics.
    # A 403 proves rejection only; never count this as valid-operation headroom.
    DENIALS.append({'status':r[0],'error':str(r[1])[:2200]})

class Client:
    def __init__(self,i):
        email=f'expense-poc-{i}@example.test'; body={'email':email,'password':'Synthetic1!','returnSecureToken':True}
        r=t.http('POST','/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=demo-expense-key',body,auth=True)
        if r[0]!=200: r=t.http('POST','/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-expense-key',body,auth=True)
        a=success(r); self.uid=a['localId']; self.token=a['idToken']; self.name=f'ExpenseUser{i}'; self.email=email
    def get(self,path): return t.get(path,self.token)
    def raw(self,path): return t.http('GET',t.BASE+'/'+path,token=self.token)
    def commit(self,items): return t.commit([write(p,v) for p,v in items],self.token)
    def profile(self):
        if self.raw('profiles/'+self.uid)[0]==200:return
        public={'uid':self.uid,'displayUsername':self.name,'normalizedUsername':self.name.lower()}
        success(self.commit([('profiles/'+self.uid,dict(public,email=self.email,upiId=None,createdAt='SERVER',updatedAt='SERVER')),
            ('usernames/'+self.name.lower(),{'uid':self.uid,'displayUsername':self.name}),('publicIdentities/'+self.uid,public)]))

def root(g): return f'groups/{g}'
def cp(g): return root(g)+'/ledger/control'
def ap(g,u): return root(g)+'/accounts/'+u
def opath(g,o): return root(g)+'/operations/'+o
def rp(g,r): return root(g)+'/records/'+r
def initial(g,n):
    return dict(groupId=g,controlRevision=0,accountingRevision=0,phase='IDLE',action='INIT',activeOperationId='',target='',checked=0,cursor=0,totalExpensesPaise=0,currentMemberCount=n,updatedAt='SERVER',readPad=0,exprPad=0)
def account(g,c):
    return dict(groupId=g,uid=c.uid,username=c.name,normalizedUsername=c.name.lower(),status='ACTIVE',joinedAt='SERVER',leftAt=None,netPaise=0,revision=0,lastOperationId='',lastComponent=-1)
def newgroup(cs):
    g=str(uuid.uuid4()); actor=cs[0]
    metadata=dict(schemaVersion=3,name='Durability synthetic group',type='FLAT',creatorId=actor.uid,adminId=actor.uid,createdAt='SERVER',updatedAt='SERVER',revision=1,initialMemberIds=sorted(c.uid for c in cs))
    success(actor.commit([(root(g),metadata),(cp(g),initial(g,len(cs)))]+[(ap(g,c.uid),account(g,c)) for c in cs]))
    return g
def advance(c,action,**kw):
    return dict(c,controlRevision=c['controlRevision']+1,action=action,updatedAt='SERVER',readPad=0,exprPad=0,**kw)
def payload(g,actor,ids,amount=101,payer=None,large=False):
    ids=sorted(ids); q,r=divmod(amount,len(ids))
    return dict(groupId=g,recordId=str(uuid.uuid4()),kind='expense',revision=1,creatorId=actor.uid,payerId=payer or actor.uid,amountPaise=amount,occurredAtMillis=1794182400000,title='X'*200 if large else 'Synthetic lunch',description='Y'*2000 if large else '',category='Other' if large else 'Food',participantIds=ids,sharesPaise={u:q+(i<r) for i,u in enumerate(ids)},deleted=False)
def begin_items(c,g,p,oid,read=0,expr=0):
    b=c.get(cp(g)); n=advance(b,'BEGIN',phase='PREPARE',activeOperationId=oid,target='',checked=0,cursor=0)
    n.update(readPad=read,exprPad=expr)
    x=dict(operationId=oid,groupId=g,recordId=p['recordId'],kind='expense_create',actorId=c.uid,expectedRevision=0,acceptedRevision=n['controlRevision'],createdAt='SERVER',status='PENDING',before=None,after=p)
    return [(cp(g),n),(opath(g,oid),x)]
def begin(c,g,p,**pads):
    oid=str(uuid.uuid4()); items=begin_items(c,g,p,oid,**pads); success(c.commit(items)); return oid,items
def step_items(c,g,action=None,read=0,expr=0):
    b=c.get(cp(g)); oid=b['activeOperationId']; x=c.get(opath(g,oid)); p=x['after']; n=len(p['participantIds'])+1
    action=action or ('CHECK' if b['phase']=='PREPARE' else 'POST' if b['cursor']<n else 'FINISH')
    if action=='CHECK':
        v=advance(b,action,checked=b['checked']+1,phase='APPLY' if b['checked']+1==2*n else 'PREPARE'); items=[(cp(g),v)]
    elif action=='POST':
        i=b['cursor']; u=p['payerId'] if i==0 else p['participantIds'][i-1]
        a=c.get(ap(g,u)); a.update(netPaise=a['netPaise']+(p['amountPaise'] if i==0 else -p['sharesPaise'][u]),revision=a['revision']+1,lastOperationId=oid,lastComponent=i)
        v=advance(b,action,cursor=i+1); items=[(cp(g),v),(ap(g,u),a)]
    elif action in ('FINISH','CANCEL'):
        v=advance(b,action,phase='IDLE',activeOperationId='',target='',checked=0,cursor=0)
        receipt={k:val for k,val in x.items() if k not in ('before','after')}
        receipt.update(status='COMMITTED' if action=='FINISH' else 'CANCELLED',resultRevision=1 if action=='FINISH' else 0,finishedAt='SERVER')
        items=[(cp(g),v),(opath(g,oid),receipt)]
        if action=='FINISH':
            v.update(accountingRevision=b['accountingRevision']+1,totalExpensesPaise=b['totalExpensesPaise']+p['amountPaise'])
            items.append((rp(g,p['recordId']),dict(p,createdAt=x['createdAt'],updatedAt='SERVER',lastOperationId=oid,accountingRevision=v['accountingRevision'])))
    else:raise ValueError(action)
    v.update(readPad=read,exprPad=expr); return items
def step(c,g,**kw):
    items=step_items(c,g,**kw); success(c.commit(items)); return items
def resume(c,g,oid):
    # Fresh reads through this member's token only. No original client/draft needed.
    x=c.get(opath(g,oid))
    if x['status']!='PENDING':return x
    assert c.get(cp(g))['activeOperationId']==oid
    for _ in range(32):
        step(c,g)
        x=c.get(opath(g,oid))
        if x['status']!='PENDING':return x
    raise RuntimeError('Recovery exceeded deterministic step bound')
def absent(c,g,p):assert c.raw(rp(g,p['recordId']))[0]==404
def audit(c,g,ids):
    data=success(t.http('GET',t.BASE+'/'+root(g)+'/records',token=c.token)); net={u:0 for u in ids}; total=0
    for doc in data.get('documents',[]):
        r={k:t.dec(v) for k,v in doc['fields'].items()}; total+=r['amountPaise']; net[r['payerId']]+=r['amountPaise']
        for u,a in r['sharesPaise'].items():net[u]-=a
    assert sum(net.values())==0
    assert c.get(cp(g))['phase']=='IDLE' and c.get(cp(g))['totalExpensesPaise']==total
    assert {u:c.get(ap(g,u))['netPaise'] for u in ids}==net
    return len(data.get('documents',[])),total
def expect(name,fn):
    start=time.monotonic()
    try:fn(); result={'name':name,'status':'PASS'}
    except Exception as e:result={'name':name,'status':'BLOCKED','error':str(e)[:4000]}
    result['seconds']=round(time.monotonic()-start,3); RESULTS.append(result); print(json.dumps(result),flush=True)
    return result['status']=='PASS'

def main():
    global CS,G
    def setup():
        global CS,G
        CS=[Client(i) for i in range(9)]
        for c in CS:c.profile()
        CS.sort(key=lambda c:c.uid); G=newgroup(CS[:3])
        assert CS[1].get(root(G))['creatorId']==CS[0].uid
        assert all(CS[1].get(ap(G,c.uid))['netPaise']==0 for c in CS[:3])
    if not expect('Auth/profile compatibility and atomic three-member zero initialization',setup):return
    a,b=CS[:2]; ids=[c.uid for c in CS[:3]]
    def admission():
        p=payload(G,a,ids); oid=str(uuid.uuid4()); items=begin_items(a,G,p,oid)
        denied(a.commit(items[:1])); denied(a.commit(items[1:])); assert a.get(cp(G))['phase']=='IDLE'
        success(a.commit(items)); absent(b,G,p)
        assert b.get(opath(G,oid))['after']['sharesPaise']==dict(zip(sorted(ids),[34,34,33]))
        denied(a.commit(items)); first=step(b,G); denied(b.commit(first)); absent(b,G,p)
        r=resume(b,G,oid); assert r['status']=='COMMITTED' and 'after' not in r
        assert resume(b,G,oid)==r; assert audit(b,G,ids)==(1,101)
    if not expect('Atomic pending command, duplicate/stale rejection, exact shares and different-member recovery',admission):return
    def interruptions():
        # BEGIN, first/mid PREPARE, APPLY entry, first/mid/last POST, post-final lost ack.
        for cut in (0,1,4,8,9,10,12,13):
            p=payload(G,a,ids); oid,_=begin(a,G,p)
            for _ in range(cut):step(a,G)
            if cut<13:absent(b,G,p); assert b.get(opath(G,oid))['status']=='PENDING'
            r=resume(b,G,oid); assert r['status']=='COMMITTED'; assert r['actorId']==a.uid
            assert b.get(rp(G,p['recordId']))['creatorId']==a.uid
            audit(b,G,ids)
    if not expect('Recovery at eight interruption boundaries without original actor state',interruptions):return
    def cancellations():
        for checks in (0,4,7):
            before={u:b.get(ap(G,u))['netPaise'] for u in ids}; p=payload(G,a,ids); oid,_=begin(a,G,p)
            for _ in range(checks):step(a,G)
            items=step(b,G,action='CANCEL'); denied(b.commit(items)); absent(b,G,p)
            r=resume(b,G,oid); assert r['status']=='CANCELLED' and 'after' not in r
            assert {u:b.get(ap(G,u))['netPaise'] for u in ids}==before
            denied(a.commit(begin_items(a,G,p,oid)))
            # New durable request may reuse an unpublished, cancelled record key.
            nxt,_=begin(a,G,p); assert resume(b,G,nxt)['status']=='COMMITTED'; audit(b,G,ids)
        p=payload(G,a,ids); oid,_=begin(a,G,p)
        for _ in range(8):step(a,G)
        denied(b.commit(step_items(b,G,action='CANCEL')))
        assert resume(b,G,oid)['status']=='COMMITTED'; audit(b,G,ids)
    if not expect('Safe pre-post cancellation, terminal retry, next-operation progress and APPLY completion',cancellations):return
    def maximum():
        for c in CS[3:]:
            v=advance(a.get(cp(G)),'JOIN',target=c.uid,currentMemberCount=a.get(cp(G))['currentMemberCount']+1)
            success(a.commit([(cp(G),v),(ap(G,c.uid),account(G,c))]))
        assert b.get(cp(G))['currentMemberCount']==9
        p=payload(G,a,[c.uid for c in CS[:8]],999999999999,CS[8].uid,True)
        oid,_=begin(a,G,p); assert resume(b,G,oid)['status']=='COMMITTED'
        count,total=audit(b,G,[c.uid for c in CS]); METRICS.append({'maxParticipants':8,'retainedAccounts':9,'amountPaise':p['amountPaise'],'committedRecords':count,'totalPaise':total,'checks':18,'postSteps':9,'atomicRequests':29,'documentWrites':41})
    if not expect('Nine retained accounts and maximum-size eight-participant expense workflow',maximum):return
    budget(a,b)

def budget(a,b):
    # Diagnostic fixture documents only; admin emulator bypass never performs financial writes.
    success(t.commit([write('budgetProbes/'+str(i),{'ok':True}) for i in range(11)],'owner'))
    pids=[c.uid for c in CS[:8]]
    def exercise():
        for phase in ('BEGIN','CHECK_SHARE','CHECK_RESOURCE','POST','FINISH','CANCEL'):
            for kind,value in (('read',7),('expr',4)):
                p=payload(G,a,pids,999999999999,CS[8].uid,True); oid=str(uuid.uuid4())
                if phase=='BEGIN': items=begin_items(a,G,p,oid,**{kind:value})
                else:
                    oid,_=begin(a,G,p)
                    steps={'CHECK_SHARE':8,'CHECK_RESOURCE':17,'POST':18,'FINISH':27,'CANCEL':8}[phase]
                    for _ in range(steps):step(b,G)
                    items=step_items(b,G,action='CANCEL' if phase=='CANCEL' else None,**{kind:value})
                actor=a if phase=='BEGIN' else b; response=actor.commit(items)
                METRICS.append({'phase':phase,'padding':kind,'value':value,'status':response[0],'error':None if response[0]==200 else str(response[1])[:2200]})
                assert response[0] in (200,403),str(response)
                if phase=='BEGIN' and response[0]!=200:continue
                # Failed diagnostic request must leave baseline safely recoverable.
                receipt=b.get(opath(G,oid))
                if receipt['status']=='PENDING':
                    if b.get(cp(G))['phase']=='PREPARE':step(b,G,action='CANCEL')
                    else:resume(b,G,oid)
                audit(b,G,[c.uid for c in CS])
    expect('Finite Rules headroom probes on maximum-size valid commands',exercise)

if __name__=='__main__':
    try:main()
    finally:
        result={'project':t.PROJECT,'seconds':round(time.monotonic()-START,3),'results':RESULTS,'metrics':METRICS,'denials':DENIALS}
        (HERE/'results.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps({'results':len(RESULTS),'passed':sum(r['status']=='PASS' for r in RESULTS),'seconds':result['seconds']}),flush=True)
