"""One final measurement variant, never selected by firebase.json or deployed.

Strengthen the optimized proof's membership predicate with real group, own
profile and public-identity reads. This is NOT the application's legacy map
membership schema and does not assert application integration compatibility.
"""
import json,time
import run as p

def main():
    source=(p.HERE/'expense.rules').read_text()
    old="function member(g) { return signedIn() && account(g,request.auth.uid).status=='ACTIVE'; }"
    guard="""function member(g) {
 let a=account(g,request.auth.uid);
 let m=get(/databases/$(database)/documents/groups/$(g)).data;
 let profile=get(/databases/$(database)/documents/profiles/$(request.auth.uid)).data;
 let identity=get(/databases/$(database)/documents/publicIdentities/$(request.auth.uid)).data;
 return signedIn() && a.status=='ACTIVE' && a.uid==request.auth.uid && a.groupId==g
  && m.schemaVersion==3 && m.type in ['FLAT','TRIP']
  && profile.uid==request.auth.uid && identity.uid==request.auth.uid
  && identity.normalizedUsername==a.normalizedUsername;
}"""
    assert source.count(old)==1
    envelope=source.replace(old,guard)
    (p.HERE/'authorization-measurement.rules').write_text(envelope)
    url='/emulator/v1/projects/'+p.t.PROJECT+':securityRules'
    def upload(content):
        p.success(p.t.http('PUT',url,{'rules':{'files':[{'name':'firestore.rules','content':content}]}}))
    upload(envelope)
    rows=[]
    try:
        cs=[p.Client(i) for i in range(300,309)]
        # The preceding fixture registered 30-character names; restore their real
        # profile identities rather than Client's default synthetic name label.
        for c in cs:c.name=c.get('profiles/'+c.uid)['displayUsername']
        cs.sort(key=lambda c:c.uid);a,b=cs[:2];ids=[c.uid for c in cs]
        g=p.newgroup(cs[:3])
        for c in cs[3:]:
            v=p.advance(a.get(p.cp(g)),'JOIN',target=c.uid,currentMemberCount=a.get(p.cp(g))['currentMemberCount']+1)
            p.success(a.commit([(p.cp(g),v),(p.ap(g,c.uid),p.account(g,c))]))
        for phase,steps in [('BEGIN',0),('POST_LAST',26),('FINISH',27)]:
            for kind,value in [('expr',0),('expr',1),('expr',2),('read',1),('read',4)]:
                d=p.payload(g,a,ids[:8],1000000000000,ids[8],True);oid=str(p.uuid.uuid4())
                if phase=='BEGIN':items=p.begin_items(a,g,d,oid,**{kind:value})
                else:
                    oid,_=p.begin(a,g,d)
                    for _ in range(steps):p.step(b,g)
                    items=p.step_items(b,g,**{kind:value})
                response=(a if phase=='BEGIN' else b).commit(items)
                rows.append(dict(phase=phase,padding=kind,value=value,status=response[0],error=None if response[0]==200 else response[1]))
                print(json.dumps({k:v for k,v in rows[-1].items() if k!='error'}),flush=True)
                if value==0:p.success(response) # Stop on a realistic valid baseline blocker.
                assert response[0] in (200,403)
                if phase=='BEGIN' and response[0]!=200:continue
                if b.get(p.opath(g,oid))['status']=='PENDING':
                    if b.get(p.cp(g))['phase']=='PREPARE':p.step(b,g,action='CANCEL')
                    else:p.resume(b,g,oid)
                p.audit(b,g,ids)
    finally:
        (p.HERE/'authorization-results.json').write_text(json.dumps({'rows':rows,'seconds':round(time.monotonic()-p.START,3)},indent=2)+'\n')
        # Restore only the disposable optimized source, even when a check blocks.
        upload(source)

if __name__=='__main__':main()
