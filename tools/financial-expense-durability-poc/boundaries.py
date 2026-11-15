"""One supplemental bounded campaign: untested cuts, numeric admission and smaller budget margins."""
import concurrent.futures, json, time
import run as p

def campaigns():
    p.CS=[p.Client(i) for i in range(9)]; p.CS.sort(key=lambda c:c.uid)
    a,b=p.CS[:2]; ids=[c.uid for c in p.CS[:3]]
    # A new fixture; never re-run the earlier complete journey.
    p.G=g=p.newgroup(p.CS[:3])
    def cuts():
        for cut in (2,3,5,6,7,11):
            data=p.payload(g,a,ids); oid,_=p.begin(a,g,data)
            for _ in range(cut):p.step(a,g)
            p.absent(b,g,data); assert p.resume(b,g,oid)['status']=='COMMITTED'; p.audit(b,g,ids)
        # Two current members race the same exact next revision; exactly one advances.
        data=p.payload(g,a,ids); oid,_=p.begin(a,g,data); items=p.step_items(a,g)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            responses=list(pool.map(lambda c:c.commit(items),(a,b)))
        assert sorted(r[0] for r in responses)==[200,403]
        assert b.get(p.cp(g))['checked']==1
        assert p.resume(b,g,oid)['status']=='COMMITTED'; p.audit(b,g,ids)
    if not p.expect('Remaining interruption cuts and concurrent next-revision conflict',cuts):return
    def headroom():
        # Privileged setup only seeds near-exhausted counters. All transitions use normal tokens.
        for available in (14,13):
            h=p.newgroup(p.CS[:3]); c=a.get(p.cp(h)); c['controlRevision']=999999999-available
            p.success(p.t.commit([p.write(p.cp(h),c)],'owner'))
            data=p.payload(h,a,ids); oid=str(p.uuid.uuid4()); items=p.begin_items(a,h,data,oid)
            if available==14:
                p.success(a.commit(items)); assert p.resume(b,h,oid)['status']=='COMMITTED'
                assert b.get(p.cp(h))['controlRevision']==999999999; p.audit(b,h,ids)
            else:
                p.denied(a.commit(items)); assert b.get(p.cp(h))['phase']=='IDLE'; p.absent(b,h,data)
        h=p.newgroup(p.CS[:3]); acct=a.get(p.ap(h,a.uid)); acct['revision']=999999998
        p.success(p.t.commit([p.write(p.ap(h,a.uid),acct)],'owner'))
        data=p.payload(h,a,ids); oid,_=p.begin(a,h,data)
        for _ in range(4):p.step(b,h)
        p.denied(b.commit(p.step_items(b,h)))
        assert b.get(p.cp(h))['phase']=='PREPARE'
        p.step(b,h,action='CANCEL'); assert p.resume(b,h,oid)['status']=='CANCELLED'; p.audit(b,h,ids)
        # Different payer and participants can still proceed in that same group.
        data=p.payload(h,b,[c.uid for c in p.CS[1:3]])
        oid,_=p.begin(b,h,data); assert p.resume(a,h,oid)['status']=='COMMITTED'; p.audit(b,h,ids)
    if not p.expect('Control/account revision preflight, cancellation and subsequent progress',headroom):return
    def smaller_margins():
        for c in p.CS[3:]:
            v=p.advance(a.get(p.cp(g)),'JOIN',target=c.uid,currentMemberCount=a.get(p.cp(g))['currentMemberCount']+1)
            p.success(a.commit([(p.cp(g),v),(p.ap(g,c.uid),p.account(g,c))]))
        for phase in ('BEGIN','CHECK_SHARE','CHECK_RESOURCE','POST','FINISH','CANCEL'):
            for kind,value in (('read',4),('expr',1),('expr',2)):
                data=p.payload(g,a,[c.uid for c in p.CS[:8]],999999999999,p.CS[8].uid,True); oid=str(p.uuid.uuid4())
                if phase=='BEGIN':items=p.begin_items(a,g,data,oid,**{kind:value})
                else:
                    oid,_=p.begin(a,g,data)
                    for _ in range({'CHECK_SHARE':8,'CHECK_RESOURCE':17,'POST':18,'FINISH':27,'CANCEL':8}[phase]):p.step(b,g)
                    items=p.step_items(b,g,action='CANCEL' if phase=='CANCEL' else None,**{kind:value})
                response=(a if phase=='BEGIN' else b).commit(items)
                p.METRICS.append({'phase':phase,'padding':kind,'value':value,'status':response[0],'error':None if response[0]==200 else str(response[1])[:2200]})
                assert response[0] in (200,403)
                if phase=='BEGIN' and response[0]!=200:continue
                if b.get(p.opath(g,oid))['status']=='PENDING':
                    if b.get(p.cp(g))['phase']=='PREPARE':p.step(b,g,action='CANCEL')
                    else:p.resume(b,g,oid)
                p.audit(b,g,[c.uid for c in p.CS])
    p.expect('Finite smaller-margin probes on complex valid expense/recovery branches',smaller_margins)

if __name__=='__main__':
    try:campaigns()
    finally:
        out={'results':p.RESULTS,'metrics':p.METRICS,'denials':p.DENIALS,'seconds':round(time.monotonic()-p.START,3)}
        (p.HERE/'boundaries-results.json').write_text(json.dumps(out,indent=2)+'\n')
        print(json.dumps({'passed':sum(r['status']=='PASS' for r in p.RESULTS),'cohorts':len(p.RESULTS),'seconds':out['seconds']}))
