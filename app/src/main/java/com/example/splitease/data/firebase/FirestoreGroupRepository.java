package com.example.splitease.data.firebase;

import com.example.splitease.auth.*;
import com.example.splitease.domain.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.example.splitease.repository.CloudGroupException.Code.*;

/** One group document is an atomic membership ledger. No cloud accounting/zero-balance assumption. */
public final class FirestoreGroupRepository implements GroupRepository,AutoCloseable {
    private final FirebaseFirestore db;
    private ExpenseRepository expenses;
    public void attachExpenses(ExpenseRepository expenses){this.expenses=expenses;}
    private final CloudProfileRepository profiles;
    private final com.google.firebase.auth.FirebaseAuth sdkAuth;
    private final ObservableQuery.Subscription sessionSubscription;
    private volatile SessionState session=SessionState.restoring(SessionState.Backend.FIREBASE);
    private final AtomicLong epoch=new AtomicLong();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Set<Live<?>> streams=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"cloud-group-deadlines"));
    public FirestoreGroupRepository(FirebaseFirestore db,AuthService auth,CloudProfileRepository profiles){
        this.db=db;this.profiles=profiles;this.sdkAuth=com.google.firebase.auth.FirebaseAuth.getInstance(db.getApp());
        sessionSubscription=auth.sessions().subscribe(Runnable::run,new ObservableQuery.Observer<SessionState>(){
            public void onChanged(SessionState next){String before=sessionIdentity(session);session=next;String after=sessionIdentity(next);if(!Objects.equals(before,after)){epoch.incrementAndGet();for(Live<?> live:streams)live.error(new CloudGroupException(CANCELLED));}}
            public void onError(Exception failure){session=SessionState.unavailable(SessionState.Backend.FIREBASE);epoch.incrementAndGet();for(Live<?> live:streams)live.error(new CloudGroupException(NOT_READY));}
        });
    }
    private String sessionIdentity(SessionState value){return value.backend==SessionState.Backend.FIREBASE&&value.status==SessionState.Status.SIGNED_IN?value.user.id:null;}
    private String actor(){String uid=sessionIdentity(session);return !closed.get()&&uid!=null&&uid.equals(sdkAuth.getUid())?uid:null;}
    private boolean authorized(String uid,long token){return uid!=null&&uid.equals(actor())&&token==epoch.get();}
    private String require(String supplied)throws CloudGroupException{String uid=actor();if(uid==null)throw new CloudGroupException(NOT_READY);if(!uid.equals(supplied))throw new CloudGroupException(FORBIDDEN);return uid;}
    private Exception safe(Exception failure){
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof CloudGroupException)return (CloudGroupException)cause;
        if(failure instanceof SecurityException)return new CloudGroupException(FORBIDDEN);
        if(failure instanceof NoSuchElementException)return new NoSuchElementException();
        if(failure instanceof IllegalArgumentException)return new CloudGroupException(INVALID);
        if(failure instanceof FirebaseFirestoreException){FirebaseFirestoreException.Code code=((FirebaseFirestoreException)failure).getCode();if(code==FirebaseFirestoreException.Code.PERMISSION_DENIED||code==FirebaseFirestoreException.Code.UNAUTHENTICATED)return new CloudGroupException(FORBIDDEN);if(code==FirebaseFirestoreException.Code.UNAVAILABLE||code==FirebaseFirestoreException.Code.DEADLINE_EXCEEDED||code==FirebaseFirestoreException.Code.ABORTED)return new CloudGroupException(UNAVAILABLE);}
        return new CloudGroupException(UNAVAILABLE);
    }
    private <T>void failure(Executor executor,RepositoryCallback<T> callback,Exception error){executor.execute(()->callback.onError(safe(error)));}
    private <T>void success(String uid,long token,RepositoryCallback<T> callback,T value){if(authorized(uid,token))callback.onSuccess(value);else callback.onError(new CloudGroupException(CANCELLED));}
    private RuntimeException denied(CloudGroupException.Code code){return new IllegalStateException(new CloudGroupException(code));}
    private DocumentReference group(String id){Rules.id(id);return db.collection("groups").document(id);}
    @SuppressWarnings("unchecked") private Map<String,Object> members(DocumentSnapshot doc){return new LinkedHashMap<>((Map<String,Object>)doc.get("members"));}
    @SuppressWarnings("unchecked") private List<String> ids(DocumentSnapshot doc){return new ArrayList<>((List<String>)doc.get("memberIds"));}
    @SuppressWarnings("unchecked") private Map<String,Object> member(Map<String,Object> all,String uid){return (Map<String,Object>)all.get(uid);}
    private long millis(Object value){return ((Timestamp)value).toDate().getTime();}
    private Group parseGroup(DocumentSnapshot doc){if(!doc.exists())throw new NoSuchElementException();return new Group(doc.getId(),doc.getString("name"),Group.Type.valueOf(doc.getString("type")),doc.getString("adminId"),doc.getString("creatorId"),millis(doc.get("createdAt")));}
    private List<GroupMember> parseMembers(DocumentSnapshot doc){Map<String,Object> all=members(doc);List<GroupMember> result=new ArrayList<>();for(String uid:all.keySet()){Map<String,Object> m=member(all,uid);result.add(new GroupMember(doc.getId(),uid,GroupMember.Status.valueOf((String)m.get("status")),millis(m.get("joinedAt")),m.get("leftAt")==null?null:millis(m.get("leftAt"))));}return Collections.unmodifiableList(result);}
    private GroupDetails details(DocumentSnapshot doc,String uid){
        if(!doc.exists()||!ids(doc).contains(uid))throw new SecurityException();
        Map<String,Object> all=members(doc);Map<String,String> names=new LinkedHashMap<>();List<MemberIdentity> current=new ArrayList<>();
        for(String id:new TreeSet<>(all.keySet())){String name=(String)member(all,id).get("username");names.put(id,name);if(ids(doc).contains(id))current.add(new MemberIdentity(id,name));}
        return new GroupDetails(parseGroup(doc),current,false,Collections.emptyList(),Collections.emptyMap(),names,0,false);
    }
    private Map<String,Object> newMember(String username){Map<String,Object> value=new HashMap<>();value.put("username",username);value.put("status","ACTIVE");value.put("joinedAt",FieldValue.serverTimestamp());value.put("leftAt",null);return value;}
    private void checkAdmin(DocumentSnapshot doc,String uid){if(!doc.exists()||!uid.equals(doc.getString("adminId"))||!ids(doc).contains(uid))throw denied(FORBIDDEN);}
    private String identity(Transaction transaction,String uid)throws FirebaseFirestoreException{DocumentSnapshot publicUser=transaction.get(db.collection("publicIdentities").document(uid));if(!publicUser.exists()||!uid.equals(publicUser.getString("uid")))throw denied(INVALID);return publicUser.getString("displayUsername");}
    private void bump(Transaction transaction,DocumentReference reference,DocumentSnapshot doc,Map<String,Object> changes){changes.put("updatedAt",FieldValue.serverTimestamp());changes.put("revision",Math.addExact(Objects.requireNonNull(doc.getLong("revision")),1L));transaction.update(reference,changes);}
    private void readCreated(String uid,long token,DocumentReference reference,Executor executor,RepositoryCallback<Group> callback){reference.get(Source.SERVER).addOnCompleteListener(executor,read->{if(!authorized(uid,token)){callback.onError(new CloudGroupException(CANCELLED));return;}try{if(!read.isSuccessful()){callback.onError(safe(read.getException()));return;}success(uid,token,callback,parseGroup(read.getResult()));}catch(RuntimeException invalid){callback.onError(safe(invalid));}});}
    @Override public void createGroup(String supplied,Group input,List<String> selected,Executor executor,RepositoryCallback<Group> callback){
        final String uid;final DocumentReference reference;final List<String> current;final String name;
        try{uid=require(supplied);if(input==null||input.type==null||!uid.equals(input.adminId)||!uid.equals(input.creatorId))throw new CloudGroupException(INVALID);name=CloudGroupValidation.name(input.name);reference=group(input.id);current=CloudGroupValidation.initialMembers(uid,selected);}catch(Exception error){failure(executor,callback,error);return;}
        long token=epoch.get();
        db.runTransaction(transaction->{
            if(!authorized(uid,token))throw denied(CANCELLED);
            DocumentSnapshot existing=transaction.get(reference);
            if(existing.exists()){if(uid.equals(existing.getString("creatorId"))&&name.equals(existing.getString("name"))&&input.type.name().equals(existing.getString("type"))&&ids(existing).containsAll(current))return null;throw denied(INVALID);}
            Map<String,Object> all=new LinkedHashMap<>();for(String id:current)all.put(id,newMember(identity(transaction,id)));
            Map<String,Object> record=new HashMap<>();record.put("name",name);record.put("type",input.type.name());record.put("creatorId",uid);record.put("adminId",uid);record.put("createdAt",FieldValue.serverTimestamp());record.put("updatedAt",FieldValue.serverTimestamp());record.put("revision",1L);record.put("memberIds",current);record.put("members",all);transaction.set(reference,record);return null;
        }).addOnCompleteListener(executor,task->{if(!authorized(uid,token)){callback.onError(new CloudGroupException(CANCELLED));return;}if(!task.isSuccessful())callback.onError(safe(task.getException()));else readCreated(uid,token,reference,executor,callback);});
    }
    @Override public void createGroup(Group group,Executor executor,RepositoryCallback<Group> callback){createGroup(actor(),group,Collections.emptyList(),executor,callback);}
    @Override public void findMemberByUsername(String supplied,String username,Executor executor,RepositoryCallback<MemberIdentity> callback){
        final String uid;try{uid=require(supplied);}catch(Exception error){failure(executor,callback,error);return;}long token=epoch.get();
        profiles.findByUsername(username,executor,new RepositoryCallback<MemberIdentity>(){public void onSuccess(MemberIdentity value){success(uid,token,callback,value);}public void onError(Exception error){if(!authorized(uid,token))callback.onError(new CloudGroupException(CANCELLED));else if(error instanceof AuthException&&((AuthException)error).code==AuthException.Code.USER_NOT_FOUND)callback.onError(new NoSuchElementException());else callback.onError(safe(error));}});
    }
    private interface Edit {void apply(Transaction transaction,DocumentReference reference,DocumentSnapshot doc,String uid)throws FirebaseFirestoreException;}
    private void edit(String supplied,String id,Executor executor,RepositoryCallback<Boolean> callback,Edit edit){
        final String uid;final DocumentReference reference;try{uid=require(supplied);reference=group(id);}catch(Exception error){failure(executor,callback,error);return;}long token=epoch.get();
        editAttempt(uid,token,reference,executor,callback,edit,2);
    }
    private void editAttempt(String uid,long token,DocumentReference reference,Executor executor,RepositoryCallback<Boolean> callback,Edit edit,int retries){
        AtomicLong baseRevision=new AtomicLong(-1);
        db.runTransaction(transaction->{if(!authorized(uid,token))throw denied(CANCELLED);DocumentSnapshot doc=transaction.get(reference);checkAdmin(doc,uid);baseRevision.set(Objects.requireNonNull(doc.getLong("revision")));edit.apply(transaction,reference,doc,uid);return true;}).addOnCompleteListener(executor,task->{
            if(!authorized(uid,token)){callback.onError(new CloudGroupException(CANCELLED));return;}
            if(task.isSuccessful()){success(uid,token,callback,true);return;}
            Exception failure=task.getException();
            boolean conflict=failure instanceof FirebaseFirestoreException&&(((FirebaseFirestoreException)failure).getCode()==FirebaseFirestoreException.Code.PERMISSION_DENIED||((FirebaseFirestoreException)failure).getCode()==FirebaseFirestoreException.Code.ABORTED);
            if(!conflict||retries==0||baseRevision.get()<0){callback.onError(safe(failure));return;}
            // Rules may reject a stale revision before the SDK's optimistic retry. Retry only a
            // confirmed newer version while still admin; never retry a real permission failure.
            reference.get(Source.SERVER).addOnCompleteListener(executor,read->{
                if(!authorized(uid,token)){callback.onError(new CloudGroupException(CANCELLED));return;}
                DocumentSnapshot latest=read.isSuccessful()?read.getResult():null;
                if(latest!=null&&latest.exists()&&uid.equals(latest.getString("adminId"))&&ids(latest).contains(uid)&&Objects.requireNonNull(latest.getLong("revision"))>baseRevision.get())editAttempt(uid,token,reference,executor,callback,edit,retries-1);
                else callback.onError(safe(failure));
            });
        });
    }

    @Override public void addMember(String actor,String id,String target,long ignored,Executor executor,RepositoryCallback<Boolean> callback){edit(actor,id,executor,callback,(transaction,reference,doc,uid)->{
        List<String> current=ids(doc);if(current.contains(target))throw denied(DUPLICATE);
        String name=identity(transaction,target);Map<String,Object> all=members(doc);Map<String,Object> next;
        if(all.containsKey(target)){Map<String,Object> previous=member(all,target);if(!"LEFT".equals(previous.get("status")))throw denied(INVALID);next=new HashMap<>(previous);next.put("username",name);next.put("status","ACTIVE");}else next=newMember(name);
        all.put(target,next);current.add(target);Collections.sort(current);Map<String,Object> changes=new HashMap<>();changes.put("memberIds",current);changes.put("members",all);bump(transaction,reference,doc,changes);
    });}
    @Override public void transferAdmin(String actor,String id,String target,Executor executor,RepositoryCallback<Boolean> callback){edit(actor,id,executor,callback,(transaction,reference,doc,uid)->{if(target==null||target.equals(uid)||!ids(doc).contains(target))throw denied(INVALID);Map<String,Object> changes=new HashMap<>();changes.put("adminId",target);bump(transaction,reference,doc,changes);});}
    @Override public void updateGroup(String actor,Group input,Executor executor,RepositoryCallback<Group> callback){
        if(input==null){failure(executor,callback,new CloudGroupException(INVALID));return;}
        edit(actor,input.id,executor,new RepositoryCallback<Boolean>(){public void onError(Exception error){callback.onError(error);}public void onSuccess(Boolean result){readCreated(actor,epoch.get(),group(input.id),executor,callback);}},(transaction,reference,doc,uid)->{
            if(input.type==null||!input.creatorId.equals(doc.getString("creatorId"))||!input.adminId.equals(doc.getString("adminId")))throw denied(INVALID);Map<String,Object> changes=new HashMap<>();changes.put("name",CloudGroupValidation.name(input.name));changes.put("type",input.type.name());bump(transaction,reference,doc,changes);
        });
    }
    @Override public void leaveGroup(String actor,String id,long ignored,Executor executor,RepositoryCallback<Boolean> callback){unsupported(actor,executor,callback);}
    @Override public void deleteGroup(String actor,String id,Executor executor,RepositoryCallback<Boolean> callback){unsupported(actor,executor,callback);}
    private <T>void unsupported(String supplied,Executor executor,RepositoryCallback<T> callback){try{require(supplied);failure(executor,callback,new CloudGroupException(UNSUPPORTED));}catch(Exception error){failure(executor,callback,error);}}

    private final class Live<T> implements ObservableQuery.Subscription {
        final String uid;final long token;final Executor executor;final ObservableQuery.Observer<T> observer;
        final AtomicBoolean active=new AtomicBoolean(true),cancelled=new AtomicBoolean();final AtomicReference<ListenerRegistration> listener=new AtomicReference<>();
        volatile ScheduledFuture<?> timeout;boolean confirmed;
        Live(String uid,Executor executor,ObservableQuery.Observer<T> observer){this.uid=uid;this.token=epoch.get();this.executor=executor;this.observer=observer;streams.add(this);}
        void release(){streams.remove(this);ListenerRegistration registration=listener.get();if(registration!=null)registration.remove();ScheduledFuture<?> deadline=timeout;if(deadline!=null)deadline.cancel(false);}
        void attach(ListenerRegistration value){listener.set(value);if(!active.get())value.remove();}
        boolean server(SnapshotMetadata metadata){if(!active.get())return false;if(!authorized(uid,token)){error(new CloudGroupException(CANCELLED));return false;}if(metadata.isFromCache()){if(confirmed)error(new CloudGroupException(UNAVAILABLE));return false;}if(metadata.hasPendingWrites())return false;confirmed=true;ScheduledFuture<?> deadline=timeout;if(deadline!=null)deadline.cancel(false);return true;}
        void value(T value){if(active.get()&&authorized(uid,token))observer.onChanged(value);else if(active.get())error(new CloudGroupException(CANCELLED));}
        void error(Exception failure){if(active.compareAndSet(true,false)){release();executor.execute(()->{if(!cancelled.get())observer.onError(failure instanceof FirebaseFirestoreException&&!authorized(uid,token)?new CloudGroupException(CANCELLED):safe(failure));});}}
        public void close(){cancelled.set(true);active.set(false);release();}
        void deadline(){try{timeout=timer.schedule(()->error(new CloudGroupException(UNAVAILABLE)),10,TimeUnit.SECONDS);if(!active.get()||confirmed)timeout.cancel(false);}catch(RejectedExecutionException closing){error(new CloudGroupException(CANCELLED));}}
    }
    private interface Watch<T> {void start(Live<T> live);}
    private <T>ObservableQuery<T> observe(String supplied,Watch<T> watch){return (executor,observer)->{Live<T> live=new Live<>(supplied,executor,observer);try{require(supplied);if(closed.get())throw new CloudGroupException(CANCELLED);live.deadline();watch.start(live);}catch(Exception failure){live.error(failure);}return live;};}
    @Override public ObservableQuery<List<GroupDetails>> observeGroupSummaries(String supplied){return observe(supplied,live->live.attach(db.collection("groups").whereArrayContains("memberIds",supplied).addSnapshotListener(live.executor,MetadataChanges.INCLUDE,(snapshot,error)->{
        if(error!=null){live.error(error);return;}if(snapshot==null||!live.server(snapshot.getMetadata()))return;
        try{List<GroupDetails> result=new ArrayList<>();for(DocumentSnapshot doc:snapshot.getDocuments())result.add(details(doc,supplied));result.sort((a,b)->{int date=Long.compare(b.group.createdAt,a.group.createdAt);return date!=0?date:a.group.id.compareTo(b.group.id);});live.value(Collections.unmodifiableList(result));}catch(RuntimeException invalid){live.error(invalid);}
    })));}
    private <T>ObservableQuery<T> document(String supplied,String id,java.util.function.Function<DocumentSnapshot,T> parse){return observe(supplied,live->live.attach(group(id).addSnapshotListener(live.executor,MetadataChanges.INCLUDE,(snapshot,error)->{if(error!=null){live.error(error);return;}if(snapshot==null||!live.server(snapshot.getMetadata()))return;try{if(!ids(snapshot).contains(supplied))throw new SecurityException();live.value(parse.apply(snapshot));}catch(RuntimeException invalid){live.error(invalid);}})));}
    @Override public ObservableQuery<GroupDetails> observeGroupDetails(String actor,String id){
        ObservableQuery<GroupDetails> source=document(actor,id,doc->details(doc,actor));
        if(expenses==null)return source;
        return (executor,observer)->{
            final GroupDetails[] group={null};final List<Expense>[] rows=new List[]{null};
            AtomicBoolean active=new AtomicBoolean(true);ObservableQuery.Subscription[] subscriptions=new ObservableQuery.Subscription[2];
            Runnable emit=()->{if(active.get()&&group[0]!=null&&rows[0]!=null){GroupDetails g=group[0];observer.onChanged(new GroupDetails(g.group,g.members,false,rows[0],Collections.emptyMap(),g.names,0,false,true));}};
            java.util.function.Consumer<Exception> fail=error->{if(active.compareAndSet(true,false)){for(ObservableQuery.Subscription sub:subscriptions)if(sub!=null)sub.close();observer.onError(error);}};
            subscriptions[0]=source.subscribe(executor,new ObservableQuery.Observer<GroupDetails>(){public void onChanged(GroupDetails value){group[0]=value;emit.run();}public void onError(Exception error){fail.accept(error);}});
            if(active.get())subscriptions[1]=expenses.observeExpenses(id).subscribe(executor,new ObservableQuery.Observer<List<Expense>>(){public void onChanged(List<Expense> value){rows[0]=value;emit.run();}public void onError(Exception error){fail.accept(error);}});
            if(!active.get())for(ObservableQuery.Subscription sub:subscriptions)if(sub!=null)sub.close();
            return ()->{active.set(false);for(ObservableQuery.Subscription sub:subscriptions)if(sub!=null)sub.close();};
        };
    }
    private <A,B>ObservableQuery<B> map(ObservableQuery<A> source,java.util.function.Function<A,B> transform){return (executor,observer)->source.subscribe(executor,new ObservableQuery.Observer<A>(){public void onChanged(A value){observer.onChanged(transform.apply(value));}public void onError(Exception error){observer.onError(error);}});}
    @Override public ObservableQuery<List<Group>> observeGroups(String actor){return map(observeGroupSummaries(actor),values->{List<Group> result=new ArrayList<>();for(GroupDetails value:values)result.add(value.group);return Collections.unmodifiableList(result);});}
    @Override public ObservableQuery<List<GroupMember>> observeMembers(String id){String uid=actor();return document(uid,id,this::parseMembers);}
    @Override public ObservableQuery<Map<String,Long>> observeBalances(String id){return (executor,observer)->{AtomicBoolean cancelled=new AtomicBoolean();executor.execute(()->{if(!cancelled.get())observer.onError(new CloudGroupException(UNSUPPORTED));});return ()->cancelled.set(true);};}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;sessionSubscription.close();epoch.incrementAndGet();for(Live<?> live:streams)live.error(new CloudGroupException(CANCELLED));timer.shutdown();}
}
