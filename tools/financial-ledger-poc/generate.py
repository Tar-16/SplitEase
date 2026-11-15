"""Generate fixed eight-slot POC Rules. No deployment commands or live configuration."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ZERO = '[0,0,0,0,0,0,0,0]'
def all8(call):
    return ' && '.join(call.format(i=i) for i in range(8))

rules = r'''rules_version = '2';
// ISOLATED POC ONLY. Not the application's candidate or production Rules.
service cloud.firestore {
 match /databases/{database}/documents {
  function auth() { return request.auth != null && request.auth.token.aud == 'demo-splitease-ledger-poc'; }
  function group(g) { return get(/databases/$(database)/documents/groups/$(g)).data; }
  function member(g) { return auth() && request.auth.uid in group(g).members; }
  function uuid(id) { return id.matches('[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'); }
  function uidAt(g,i) {
   return g.slots[i] is string && (i==0 || g.slots[i-1]<g.slots[i])
    && get(/databases/$(database)/documents/identities/$(g.slots[i])).data.uid==g.slots[i];
  }
  match /identities/{uid} {
   allow create: if auth() && uid==request.auth.uid && request.resource.data=={'uid':uid};
   allow get: if auth();
  }
  match /groups/{id} {
   allow get: if member(id);
   allow create: if auth() && uuid(id)
    && request.resource.data.keys().hasOnly(['creator','slots','members','revision'])
    && request.resource.data.creator==request.auth.uid && request.resource.data.revision==1
    && request.resource.data.slots is list && request.resource.data.slots.size()==8
    && request.resource.data.members==request.resource.data.slots && request.auth.uid in request.resource.data.members
    && GROUP_UIDS
    && getAfter(/databases/$(database)/documents/ledgers/$(id)).data==initial(id,request.resource.data);
  }
  function plan(v,net) { return {'base':v,'step':0,'work':net,'transfers':[],'ready':net==ZERO}; }
  function initial(id,g) { return {'groupId':id,'version':0,'membershipRevision':g.revision,'net':ZERO,'total':0,'gross':0,'count':0,'kind':'init','id':'','plan':plan(0,ZERO),'updatedAt':request.time}; }
  function bounded(v) { return v is int && v>=-1000000000000000 && v<=1000000000000000; }
  function netAt(p,i) { return bounded(p.net[i]); }
  function shape(p) {
   return p.keys().hasOnly(['groupId','version','membershipRevision','net','total','gross','count','kind','id','plan','updatedAt'])
    && p.version is int && p.version>=0 && p.version<1000000000
    && p.membershipRevision is int && p.net is list && p.net.size()==8 && NET_BOUNDS
    && p.total is int && p.total>=0 && p.total<=1000000000000000
    && p.gross is int && p.gross>=0 && p.gross<=1000000000000000
    && p.count is int && p.count>=0 && p.count<=10000 && p.updatedAt==request.time;
  }
  function shareAt(p,n,b,r,i) {
   return n<=i || (p.participants[i] is string && (i==0 || p.participants[i-1]<p.participants[i])
    && p.shares[p.participants[i]] is int && p.shares[p.participants[i]]==b+(i<r?1:0));
  }
  function expense(p,o,g) {
   let n=p.participants.size();
   let allowed=g.members.toSet().union((o==null?[]:o.participants).toSet());
   return n>0 && n<=8 && p.participants.toSet().size()==n
    && allowed.hasAll(p.participants) && g.slots.hasAll(p.participants)
    && p.shares.keys().toSet()==p.participants.toSet()
    && (p.payer in g.members || (o!=null && p.payer==o.payer))
    && p.payee=='' && p.category in ['Food','Travel','Rent','Bills','Other']
    && SHARE_ROWS;
  }
  function record(p,o,g) {
   return p.keys().hasOnly(['groupId','kind','payer','payee','amount','date','participants','shares','creator','title','description','category','revision','ledgerVersion','deleted','createdAt','updatedAt'])
    && p.groupId is string && p.kind in ['expense','settlement']
    && p.payer is string && p.payer in g.slots && p.payee is string
    && p.amount is int && p.amount>0 && p.amount<=1000000000000
    && p.date is int && p.date>=0 && p.revision is int && p.revision==(o==null?1:o.revision+1)
    && p.deleted is bool && p.creator is string && p.creator in g.slots
    && p.title is string && p.title.size()>0 && p.title.size()<=200
    && p.description is string && p.description.size()<=2000 && p.category is string
    && p.participants is list && p.shares is map && p.createdAt is timestamp && p.updatedAt==request.time
    && (o==null?(p.creator==request.auth.uid && p.createdAt==request.time && p.deleted==false)
       :(p.groupId==o.groupId && p.kind==o.kind && p.creator==o.creator && p.createdAt==o.createdAt && o.deleted==false))
    && (p.kind=='expense' ? ((p.deleted==true && o!=null && p.diff(o).affectedKeys().hasOnly(['deleted','revision','ledgerVersion','updatedAt'])) || (p.deleted==false && expense(p,o,g)))
      :(p.deleted==false && p.payer!=p.payee && p.payee in g.slots && p.participants==[] && p.shares=={}
       && (o==null?request.auth.uid in [p.payer,p.payee]:p.diff(o).affectedKeys().hasOnly(['amount','date','revision','ledgerVersion','updatedAt']))));
  }
  function effect(p,u) {
   return p==null || p.deleted ? 0 : (p.kind=='expense' ? (p.payer==u?p.amount:0)-p.shares.get(u,0)
     :(p.payer==u?p.amount:(p.payee==u?-p.amount:0)));
  }
  function expenseAmount(p) { return p!=null && p.kind=='expense' && !p.deleted?p.amount:0; }
  function settlementAmount(p) { return p!=null && p.kind=='settlement'?p.amount:0; }
  function transitionAt(p,o,r,b,g,i) { return p.net[i]==o.net[i]-effect(b,g.slots[i])+effect(r,g.slots[i]); }
  function pair(t,g,p) { return g.slots[t.d]==p.payer && g.slots[t.c]==p.payee && p.amount<=t.amount; }
  function suggested(o,g,r,i) { return o.plan.transfers.size()>i && pair(o.plan.transfers[i],g,r); }
  function editCeiling(o,r,b,g) {
   let d=g.slots.indexOf(r.payer);
   let c=g.slots.indexOf(r.payee);
   let debt=o.net[d]-b.amount;
   let credit=o.net[c]+b.amount;
   let capacity=debt<0 && credit>0?(-debt<credit?-debt:credit):0;
   return r.amount<=(b.amount>capacity?b.amount:capacity);
  }
  function paymentLimit(o,r,b,g) {
   return r.kind!='settlement' || (b!=null?editCeiling(o,r,b,g)
    :(o.plan.ready && o.plan.base==o.version && (SUGGESTED_ROWS)));
  }
  function financial(p,o,id) {
   let g=group(id);
   let path=/databases/$(database)/documents/records/$(p.id);
   let b=exists(path)?get(path).data:null;
   let r=getAfter(path).data;
   return shape(p) && request.auth.uid in g.members && o.membershipRevision==g.revision
    && p.groupId==id && p.membershipRevision==o.membershipRevision && p.version==o.version+1
    && uuid(p.id) && p.kind==r.kind && r.groupId==id && r.ledgerVersion==p.version
    && record(r,b,g) && paymentLimit(o,r,b,g)
    && p.total==o.total-expenseAmount(b)+expenseAmount(r)
    && p.gross==o.gross-settlementAmount(b)+settlementAmount(r)
    && p.count==o.count+(b==null?1:0) && NET_TRANSITIONS
    && p.plan==plan(p.version,p.net);
  }
  function maxAt(w,d,c,i) { return w[i]>=w[d] && w[i]<=w[c] && (w[i]!=w[d] || i>=d) && (w[i]!=w[c] || i>=c); }
  function workAt(p,o,t,i) { return p.work[i]==o.work[i]+(i==t.d?t.amount:(i==t.c?-t.amount:0)); }
  function step(p,o) {
   let a=p.plan;
   let b=o.plan;
   let t=a.transfers[b.step];
   return p.diff(o).affectedKeys().hasOnly(['plan','updatedAt']) && p.updatedAt==request.time
    && !b.ready && b.step<7 && b.base==o.version
    && a.keys().hasOnly(['base','step','work','transfers','ready']) && a.base==o.version
    && a.step==b.step+1 && a.transfers==b.transfers.concat([t])
    && t.keys().hasOnly(['d','c','amount']) && t.d is int && t.c is int && t.d>=0 && t.d<8 && t.c>=0 && t.c<8
    && b.work[t.d]<0 && b.work[t.c]>0 && t.amount is int
    && t.amount==(-b.work[t.d]<b.work[t.c]?-b.work[t.d]:b.work[t.c])
    && a.work is list && a.work.size()==8 && MAX_ROWS && WORK_ROWS
    && a.ready==(a.work==ZERO);
  }
  match /ledgers/{id} {
   allow get: if member(id);
   allow create: if auth() && getAfter(/databases/$(database)/documents/groups/$(id)).data.creator==request.auth.uid
    && !exists(/databases/$(database)/documents/groups/$(id))
    && request.resource.data==initial(id,getAfter(/databases/$(database)/documents/groups/$(id)).data);
   allow update: if member(id) && ((request.resource.data.version==resource.data.version && step(request.resource.data,resource.data)) || (request.resource.data.version==resource.data.version+1 && financial(request.resource.data,resource.data,id)));
  }
  match /records/{id} {
   allow get: if auth() && (!exists(/databases/$(database)/documents/records/$(id)) || member(resource.data.groupId));
   allow create,update: if auth() && uuid(id) && member(request.resource.data.groupId)
    && getAfter(/databases/$(database)/documents/ledgers/$(request.resource.data.groupId)).data.id==id
    && getAfter(/databases/$(database)/documents/ledgers/$(request.resource.data.groupId)).data.version==get(/databases/$(database)/documents/ledgers/$(request.resource.data.groupId)).data.version+1
    && request.resource.data.ledgerVersion==getAfter(/databases/$(database)/documents/ledgers/$(request.resource.data.groupId)).data.version;
  }
  function touch(n,base,i) { return n<=i || get(/databases/$(database)/documents/probes/$(base+i)).data.ok==true; }
  function access(n,base) { return ACCESS_ROWS; }
  match /access/{id} { allow create: if auth() && access(request.resource.data.n,request.resource.data.base); }
  function expr(d) { return EXPR_CHUNK; }
  match /expression/{id} { allow create: if auth() && (request.resource.data.mode=='low'?expr(request.resource.data):EXPR_HIGH); }
  match /{doc=**} { allow read,write: if false; }
 }
}
'''
# Rules List has no indexOf: indices are explicit record metadata, validated against opaque UID slots.
rules = rules.replace("let d=g.slots.indexOf(r.payer);", "let d=r.d;").replace("let c=g.slots.indexOf(r.payee);", "let c=r.c;")
rules = rules.replace("'groupId','kind','payer','payee','amount'", "'groupId','kind','payer','payee','d','c','amount'")
rules = rules.replace("p.payer is string && p.payer in g.slots && p.payee is string", "p.payer is string && p.payer in g.slots && p.payee is string && p.d is int && p.d>=0 && p.d<8 && g.slots[p.d]==p.payer && p.c is int && p.c>=0 && p.c<8")
rules = rules.replace("p.payee in g.slots && p.participants", "p.payee in g.slots && g.slots[p.c]==p.payee && p.participants")
replacements = {
 'ZERO': ZERO,
 'GROUP_UIDS': all8('uidAt(request.resource.data,{i})'),
 'NET_BOUNDS': all8('netAt(p,{i})'),
 'SHARE_ROWS': all8('shareAt(p,n,int(p.amount / n),p.amount % n,{i})'),
 'NET_TRANSITIONS': all8('transitionAt(p,o,r,b,g,{i})'),
 'SUGGESTED_ROWS': ' || '.join(f'suggested(o,g,r,{i})' for i in range(7)),
 'MAX_ROWS': all8('maxAt(b.work,t.d,t.c,{i})'),
 'WORK_ROWS': all8('workAt(a,b,t,{i})'),
 'ACCESS_ROWS': ' && '.join(f'touch(n,base,{i})' for i in range(11)),
 'EXPR_CHUNK': ' && '.join('(d.x>=0)' for _ in range(16)),
 'EXPR_HIGH': ' && '.join('expr(request.resource.data)' for _ in range(32)),
}
for key,value in replacements.items(): rules=rules.replace(key,value)
(ROOT/'ledger.rules').write_text(rules)
print('Generated isolated eight-slot Rules:', len(rules.encode()), 'bytes')
