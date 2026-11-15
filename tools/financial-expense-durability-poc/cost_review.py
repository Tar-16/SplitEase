"""Single bounded cost review: named negative cases and maximum supported data.

Each invalid financial write is a separate atomic request with an unchanged
control check. Budget/dependency diagnostics are NOT semantic-rule passes.
"""
import json, time
import run as p

NEGATIVES=[]; MARGINS=[]
def reject(name,client,items,g,expected):
    before=client.get(p.cp(g)) if client.uid in IDS else OWNER.get(p.cp(g))
    response=client.commit(items); text=json.dumps(response)
    assert response[0]==403, (name,response)
    if 'maximum of 1000' in text or 'Service call error' in text:
        kind='BUDGET_OR_MIXED'
    elif 'evaluation error' in text or 'Null value' in text or 'Property' in text:
        kind='DEPENDENCY_EVALUATION'
    else:kind='CLEAN_RULE_DENIAL'
    assert OWNER.get(p.cp(g))==before, name+' changed control'
    NEGATIVES.append(dict(name=name,expected=expected,classification=kind,status=403,error=response[1]))

def mutate(items,index,key,value):
    out=p.copy.deepcopy(items);out[index][1][key]=value;return out

def main():
    global OWNER,IDS
    cs=[p.Client(i) for i in range(300,310)]
    for i,c in enumerate(cs):
        # A real, normal-token profile/registry creation at the allowed name bound.
        c.name=('ExpenseFixture'+str(i)).ljust(30,'X');c.profile()
    cs.sort(key=lambda c:c.uid); a,b=cs[:2]; outsider=cs[-1]; cs=cs[:9]
    OWNER=a; IDS=[c.uid for c in cs]
    g=p.newgroup(cs[:3])
    # Preserve financial values: widen only immutable group-name test fixture using emulator-owner.
    # No financial authorization or checkpoint is seeded/bypassed.
    group=a.get(p.root(g));group['name']='G'*100
    p.success(p.t.commit([p.write(p.root(g),group)],'owner'))
    for c in cs[3:]:
        v=p.advance(a.get(p.cp(g)),'JOIN',target=c.uid,currentMemberCount=a.get(p.cp(g))['currentMemberCount']+1)
        p.success(a.commit([(p.cp(g),v),(p.ap(g,c.uid),p.account(g,c))]))
    def data():return p.payload(g,a,IDS[:8],1000000000000,IDS[8],True)
    def individual():
        for key,val in [('amountPaise',0),('amountPaise',-1),('amountPaise',1.5),('amountPaise',1000000000001),('creatorId',b.uid),('groupId',str(p.uuid.uuid4())),('recordId','not-a-uuid'),('participantIds',[]),('participantIds',[IDS[0],IDS[0]])]:
            d=data();d[key]=val;items=p.begin_items(a,g,d,str(p.uuid.uuid4()))
            reject('BEGIN '+key+' '+str(val)[:55],a,items,g,'payload/identity/bounds')
        items=p.begin_items(a,g,data(),str(p.uuid.uuid4()))
        reject('BEGIN forged original actor',a,mutate(items,1,'actorId',b.uid),g,'actor identity')
        reject('BEGIN wrong expected revision',a,mutate(items,1,'expectedRevision',1),g,'expected absent head revision')
        reject('BEGIN missing command',a,items[:1],g,'atomic command/control pairing')
        reject('BEGIN missing control',a,items[1:],g,'atomic command/control pairing')
        reject('BEGIN nonmember',outsider,items,g,'current membership and actor binding')
        # Invalid share is intentionally rejected at staged validation, not admission.
        d=data();d['sharesPaise'][IDS[0]]+=1;oid,_=p.begin(a,g,d);p.step(b,g)
        reject('CHECK unequal first share',b,p.step_items(b,g),g,'exact equal share')
        p.step(b,g,action='CANCEL');assert p.resume(b,g,oid)['status']=='CANCELLED'
        d=data();d['payerId']=outsider.uid;oid,_=p.begin(a,g,d)
        reject('CHECK nonmember payer',b,p.step_items(b,g),g,'current payer membership')
        p.step(b,g,action='CANCEL')
        d=data();oid,begin=p.begin(a,g,d)
        reject('BEGIN duplicate operation',a,begin,g,'stable operation identity/control revision')
        x=b.get(p.opath(g,oid));x['after']['amountPaise']-=1
        reject('Pending payload modification',b,[(p.opath(g,oid),x)],g,'immutable admitted command')
        for _ in range(18):p.step(b,g)
        items=p.step_items(b,g)
        for name,changed,expected in [
            ('POST forged net',mutate(items,1,'netPaise',items[1][1]['netPaise']+1),'exact delta'),
            ('POST stale account revision',mutate(items,1,'revision',items[1][1]['revision']-1),'account revision'),
            ('POST wrong component marker',mutate(items,1,'lastComponent',1),'operation/component binding'),
            ('POST changed username',mutate(items,1,'username','Forged'),'immutable membership/identity'),
            ('POST skipped cursor',mutate(items,0,'cursor',2),'sequential cursor'),
            ('POST missing account',items[:1],'paired account/control update'),
            ('POST missing control',items[1:],'paired account/control update')]:reject(name,b,changed,g,expected)
        reject('APPLY cancellation',b,p.step_items(b,g,action='CANCEL'),g,'PREPARE-only cancellation')
        reject('Premature publication',b,p.step_items(b,g,action='FINISH'),g,'all components complete')
        reject('POST nonmember recovery',outsider,items,g,'current recovery membership')
        p.success(b.commit(items));reject('POST repeated exact request',b,items,g,'stale cursor/revision')
        for _ in range(8):p.step(b,g)
        final=p.step_items(b,g)
        for name,changed,expected in [
            ('FINALIZE forged amount',mutate(final,2,'amountPaise',1),'frozen command/head equality'),
            ('FINALIZE forged total',mutate(final,0,'totalExpensesPaise',1),'exact spending transition'),
            ('FINALIZE forged accounting revision',mutate(final,0,'accountingRevision',20),'accounting revision'),
            ('FINALIZE changed original actor',mutate(final,1,'actorId',b.uid),'immutable receipt identity'),
            ('FINALIZE missing receipt',[final[0],final[2]],'atomic terminal receipt'),
            ('FINALIZE missing record',final[:2],'atomic head publication'),
            ('FINALIZE missing control',final[1:],'atomic control publication')]:reject(name,b,changed,g,expected)
        p.success(b.commit(final));assert p.audit(b,g,IDS)==(1,1000000000000)
        reject('FINALIZE stale replay',b,final,g,'terminal immutability and stale revision')
        receipt=b.get(p.opath(g,oid));receipt['resultRevision']=2
        reject('Terminal receipt modification',b,[(p.opath(g,oid),receipt)],g,'immutable receipt')
        response=p.t.commit([{'delete':p.t.NAME+p.rp(g,d['recordId'])}],b.token)
        assert response[0]==403
        NEGATIVES.append(dict(name='Committed expense deletion',expected='no deletion/recreation',classification='BUDGET_OR_MIXED' if 'maximum of 1000' in str(response) else 'CLEAN_RULE_DENIAL',status=403,error=response[1]))
    if not p.expect('Individually classified maximum-size invalid financial requests',individual):return
    def margins():
        # Fixed target, chosen before execution: 32 extra terms and 4 distinct gets.
        # Also sample 64 terms and 7 gets to locate a bounded failure interval.
        for phase,steps in [('BEGIN',0),('CHECK_SHARE',8),('CHECK_RESOURCE',17),('POST_LAST',26),('FINISH',27),('CANCEL',8)]:
            for kind,value in [('expr',2),('expr',4),('read',4),('read',7)]:
                d=data();oid=str(p.uuid.uuid4())
                if phase=='BEGIN':items=p.begin_items(a,g,d,oid,**{kind:value})
                else:
                    oid,_=p.begin(a,g,d)
                    for _ in range(steps):p.step(b,g)
                    items=p.step_items(b,g,action='CANCEL' if phase=='CANCEL' else None,**{kind:value})
                result=(a if phase=='BEGIN' else b).commit(items)
                assert result[0] in (200,403)
                MARGINS.append(dict(phase=phase,padding=kind,value=value,status=result[0],error=None if result[0]==200 else result[1]))
                if phase=='BEGIN' and result[0]!=200:continue
                if b.get(p.opath(g,oid))['status']=='PENDING':
                    if b.get(p.cp(g))['phase']=='PREPARE':p.step(b,g,action='CANCEL')
                    else:p.resume(b,g,oid)
                p.audit(b,g,IDS)
        assert b.get(p.cp(g))['phase']=='IDLE'
    p.expect('Maximum-size records/names and fixed expression/access margin measurements',margins)

if __name__=='__main__':
    try:main()
    finally:
        result=dict(results=p.RESULTS,negativeCases=NEGATIVES,margins=MARGINS,seconds=round(time.monotonic()-p.START,3))
        (p.HERE/'cost-results.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps({'cohorts':p.RESULTS,'negativeCases':len(NEGATIVES),'negativeBudgetDiagnostics':sum(r['classification']=='BUDGET_OR_MIXED' for r in NEGATIVES),'seconds':result['seconds']}))
