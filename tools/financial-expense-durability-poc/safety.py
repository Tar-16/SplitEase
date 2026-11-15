"""Final small defensive cohort and the more expensive participant-side POST branch."""
import json, time
import run as p

def main():
    cs=[p.Client(i) for i in range(9)]; cs.sort(key=lambda c:c.uid); a,b=cs[:2]; ids=[c.uid for c in cs[:3]]
    g=p.newgroup(cs[:3])
    def safety():
        data=p.payload(g,a,ids); oid,items=p.begin(a,g,data)
        changed=b.get(p.opath(g,oid)); changed['after']['amountPaise']+=1
        p.denied(b.commit([(p.opath(g,oid),changed)]))
        # Cannot publish an expense while its accounts have not been posted.
        p.denied(b.commit(p.step_items(b,g,action='FINISH')))
        for _ in range(8):p.step(b,g)
        items=p.step_items(b,g); corrupted=p.copy.deepcopy(items); corrupted[1][1]['netPaise']+=1
        p.denied(b.commit(corrupted)); assert b.get(p.cp(g))['cursor']==0
        p.denied(b.commit(items[:1])); p.denied(b.commit(items[1:])); assert b.get(p.cp(g))['cursor']==0
        p.success(b.commit(items)); p.denied(b.commit(items))
        assert p.resume(b,g,oid)['status']=='COMMITTED'; p.audit(b,g,ids)
        # A still-registered nonmember cannot take over recovery.
        data=p.payload(g,a,ids); oid,_=p.begin(a,g,data)
        assert cs[8].raw(p.opath(g,oid))[0]==403
        p.denied(cs[8].commit(p.step_items(b,g)))
        p.step(b,g,action='CANCEL'); p.audit(b,g,ids)
    if not p.expect('Frozen command, incomplete publication, exact paired postings and recovery authorization',safety):return
    def last_post():
        for c in cs[3:]:
            v=p.advance(a.get(p.cp(g)),'JOIN',target=c.uid,currentMemberCount=a.get(p.cp(g))['currentMemberCount']+1)
            p.success(a.commit([(p.cp(g),v),(p.ap(g,c.uid),p.account(g,c))]))
        for kind,value in (('read',4),('read',7),('expr',1),('expr',2)):
            data=p.payload(g,a,[c.uid for c in cs[:8]],999999999999,cs[8].uid,True); oid,_=p.begin(a,g,data)
            for _ in range(26):p.step(b,g)
            assert b.get(p.cp(g))['cursor']==8
            response=b.commit(p.step_items(b,g,**{kind:value})); assert response[0] in (200,403)
            p.METRICS.append({'phase':'POST_LAST_PARTICIPANT','padding':kind,'value':value,'status':response[0],'error':None if response[0]==200 else str(response[1])[:2200]})
            assert p.resume(b,g,oid)['status']=='COMMITTED'; p.audit(b,g,[c.uid for c in cs])
    p.expect('Largest participant debit and finite recovery budget samples',last_post)

if __name__=='__main__':
    try:main()
    finally:
        out={'results':p.RESULTS,'metrics':p.METRICS,'denials':p.DENIALS,'seconds':round(time.monotonic()-p.START,3)}
        (p.HERE/'safety-results.json').write_text(json.dumps(out,indent=2)+'\n')
        print(json.dumps({'passed':sum(r['status']=='PASS' for r in p.RESULTS),'cohorts':len(p.RESULTS),'seconds':out['seconds']}))
