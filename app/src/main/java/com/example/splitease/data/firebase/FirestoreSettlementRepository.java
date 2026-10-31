package com.example.splitease.data.firebase;

import com.example.splitease.auth.*;
import com.example.splitease.domain.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.example.splitease.repository.CloudFinancialException.Code.*;

/** Version-fenced cloud accounting. No cached/persisted derived balances, receipts or recurring data. */
public final class FirestoreSettlementRepository implements SettlementRepository,AutoCloseable {
 private final FirebaseFirestore db;
 private final FirebaseAuth sdk;
 private final CompletableFuture<Void> initialized=new CompletableFuture<>();
 private final AtomicLong epoch=new AtomicLong();
 private volatile SessionState session=SessionState.restoring(SessionState.Backend.FIREBASE);
 private volatile boolean closed;
 private final ObservableQuery.Subscription sessions;
 private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
 private final Set<Live> streams=ConcurrentHashMap.newKeySet();
 public FirestoreSettlementRepository(FirebaseFirestore db,AuthService auth){
  this.db=db;sdk=FirebaseAuth.getInstance(db.getApp());
  sessions=auth.sessions().subscribe(Runnable::run,new ObservableQuery.Observer<SessionState>(){
   public void onChanged(SessionState next){String prior=identity();session=next;if(!Objects.equals(prior,identity())){epoch.incrementAndGet();for(Live live:streams)live.fail(new CloudFinancialException(CANCELLED));}initialized.complete(null);}
   public void onError(Exception error){session=SessionState.unavailable(SessionState.Backend.FIREBASE);epoch.incrementAndGet();for(Live live:streams)live.fail(new CloudFinancialException(CANCELLED));initialized.complete(null);}
  });
 }
 private String identity(){return session.backend==SessionState.Backend.FIREBASE&&session.status==SessionState.Status.SIGNED_IN?session.user.id:null;}
 private String actor(){String uid=identity();return !closed&&uid!=null&&uid.equals(sdk.getUid())?uid:null;}
 private boolean same(String uid,long token){return uid!=null&&uid.equals(actor())&&epoch.get()==token;}
 private String require(String supplied)throws CloudFinancialException{String uid=actor();if(uid==null||!uid.equals(supplied))throw new CloudFinancialException(FORBIDDEN);return uid;}
 private Exception safe(Exception error){
  for(Throwable cause=error;cause!=null;cause=cause.getCause()){if(cause instanceof CloudFinancialException)return (CloudFinancialException)cause;if(cause instanceof DuplicateSettlementException)return (DuplicateSettlementException)cause;}
  if(error instanceof IllegalArgumentException||error instanceof ArithmeticException)return new CloudFinancialException(INVALID);
  if(error instanceof FirebaseFirestoreException){FirebaseFirestoreException.Code code=((FirebaseFirestoreException)error).getCode();if(code==FirebaseFirestoreException.Code.PERMISSION_DENIED||code==FirebaseFirestoreException.Code.UNAUTHENTICATED)return new CloudFinancialException(FORBIDDEN);if(code==FirebaseFirestoreException.Code.ABORTED)return new CloudFinancialException(CONFLICT);}
  return new CloudFinancialException(UNAVAILABLE);
 }
 private RuntimeException reject(CloudFinancialException.Code code){return new IllegalStateException(new CloudFinancialException(code));}
 private boolean confirmed(SnapshotMetadata metadata){return !metadata.isFromCache()&&!metadata.hasPendingWrites();}
 @SuppressWarnings("unchecked") private List<String> current(DocumentSnapshot group){return (List<String>)group.get("memberIds");}
 private void member(DocumentSnapshot group,String uid){if(!group.exists()||!current(group).contains(uid))throw reject(FORBIDDEN);}
 private static Settlement parse(DocumentSnapshot doc){return new Settlement(doc.getId(),doc.getString("groupId"),doc.getString("payerId"),doc.getString("payeeId"),doc.getLong("amountPaise"),doc.getLong("settlementDate"),doc.getLong("revision"));}
 private static final class Snapshot {
  final SettlementDetails details;final long financial,groupRevision;
  Snapshot(SettlementDetails details,long financial,long groupRevision){this.details=details;this.financial=financial;this.groupRevision=groupRevision;}
 }
 @SuppressWarnings("unchecked") private Snapshot build(DocumentSnapshot group,List<Expense> expenses,List<Settlement> settlements){
  Map<String,Object> history=(Map<String,Object>)group.get("members");Map<String,String> names=new TreeMap<>();List<MemberIdentity> members=new ArrayList<>();
  for(String uid:history.keySet()){String name=(String)((Map<String,Object>)history.get(uid)).get("username");names.put(uid,name);if(current(group).contains(uid))members.add(new MemberIdentity(uid,name));}
  members.sort(Comparator.comparing(m->m.id));
  Map<String,Long> balances=new TreeMap<>(Balances.calculate(expenses,settlements));for(String uid:names.keySet())balances.putIfAbsent(uid,0L);
  long total=0;for(Expense expense:expenses)total=Math.addExact(total,expense.amountPaise);
  Group value=new Group(group.getId(),group.getString("name"),Group.Type.valueOf(group.getString("type")),group.getString("adminId"),group.getString("creatorId"),group.getTimestamp("createdAt").toDate().getTime());
  GroupDetails details=new GroupDetails(value,members,false,expenses,balances,names,total,true,true);
  return new Snapshot(new SettlementDetails(details,settlements,SettlementCalculator.suggestIdentities(balances),Collections.emptyMap()),FirestoreFinancialRevision.value(group),group.getLong("revision"));
 }
 /** A server read before and after both queries rejects mixed revisions, including new/deleted records. */
 private void snapshot(String uid,long token,String groupId,Executor executor,RepositoryCallback<Snapshot> callback){
  DocumentReference ref=db.collection("groups").document(groupId);
  ref.get(Source.SERVER).addOnCompleteListener(executor,first->{
   if(!same(uid,token)){callback.onError(new CloudFinancialException(CANCELLED));return;}
   try{if(!first.isSuccessful()){callback.onError(safe(first.getException()));return;}if(!confirmed(first.getResult().getMetadata()))throw reject(CONFLICT);member(first.getResult(),uid);}catch(Exception e){callback.onError(safe(e));return;}
   long version=FirestoreFinancialRevision.value(first.getResult()),membership=first.getResult().getLong("revision");
   db.collection("expenses").whereEqualTo("groupId",groupId).get(Source.SERVER).addOnCompleteListener(executor,spending->{
    if(!same(uid,token)){callback.onError(new CloudFinancialException(CANCELLED));return;}if(!spending.isSuccessful()){callback.onError(safe(spending.getException()));return;}if(!confirmed(spending.getResult().getMetadata())){callback.onError(new CloudFinancialException(CONFLICT));return;}
    db.collection("settlements").whereEqualTo("groupId",groupId).get(Source.SERVER).addOnCompleteListener(executor,payments->{
     if(!same(uid,token)){callback.onError(new CloudFinancialException(CANCELLED));return;}if(!payments.isSuccessful()){callback.onError(safe(payments.getException()));return;}if(!confirmed(payments.getResult().getMetadata())){callback.onError(new CloudFinancialException(CONFLICT));return;}
     ref.get(Source.SERVER).addOnCompleteListener(executor,last->{
      if(!same(uid,token)){callback.onError(new CloudFinancialException(CANCELLED));return;}
      try{if(!last.isSuccessful()){callback.onError(safe(last.getException()));return;}if(!confirmed(last.getResult().getMetadata()))throw reject(CONFLICT);member(last.getResult(),uid);
       if(version!=FirestoreFinancialRevision.value(last.getResult())||membership!=last.getResult().getLong("revision"))throw reject(CONFLICT);
       List<Expense> expenses=new ArrayList<>();for(DocumentSnapshot doc:spending.getResult().getDocuments())if(!Boolean.TRUE.equals(doc.getBoolean("deleted")))expenses.add(FirestoreExpenseRepository.parse(doc));
       expenses.sort(Comparator.comparingLong((Expense e)->e.expenseDate).thenComparing(e->e.id));
       List<Settlement> records=new ArrayList<>();for(DocumentSnapshot doc:payments.getResult().getDocuments())records.add(parse(doc));records.sort(Comparator.comparingLong((Settlement s)->s.settlementDate).thenComparing(s->s.id));
       callback.onSuccess(build(last.getResult(),expenses,records));
      }catch(Exception e){callback.onError(safe(e));}
     });
    });
   });
  });
 }
 @Override public void saveSettlement(String actor,Settlement value,Executor executor,RepositoryCallback<Settlement> callback){saveSettlement(actor,value,false,executor,callback);}
 @Override public void saveSettlement(String supplied,Settlement value,boolean acknowledge,Executor executor,RepositoryCallback<Settlement> callback){
  if(!initialized.isDone()){initialized.thenRunAsync(()->saveSettlement(supplied,value,acknowledge,executor,callback),executor);return;}
  final String uid;try{uid=require(supplied);Rules.id(value.id);Rules.id(value.groupId);Rules.date(value.settlementDate);if(value.amountPaise<=0||value.amountPaise>CloudExpenseValidation.MAX_PAISE||value.revision<0||value.revision==Long.MAX_VALUE||value.payerId==null||value.payeeId==null||value.payerId.equals(value.payeeId))throw new IllegalArgumentException();}catch(Exception e){executor.execute(()->callback.onError(safe(e)));return;}
  long token=epoch.get();snapshot(uid,token,value.groupId,executor,new RepositoryCallback<Snapshot>(){
   public void onError(Exception error){callback.onError(error);}
   public void onSuccess(Snapshot before){
    DocumentReference ref=db.collection("settlements").document(value.id),group=db.collection("groups").document(value.groupId);
    db.runTransaction(tx->{
     if(!same(uid,token))throw reject(CANCELLED);
     DocumentSnapshot g=tx.get(group);member(g,uid);DocumentSnapshot old=tx.get(ref);
     if(FirestoreFinancialRevision.value(g)!=before.financial||g.getLong("revision")!=before.groupRevision)throw reject(CONFLICT);
     long limit;
     if(old.exists()){
      Settlement original=parse(old);if(value.revision!=original.revision)throw reject(CONFLICT);
      if(!value.groupId.equals(original.groupId)||!value.payerId.equals(original.payerId)||!value.payeeId.equals(original.payeeId))throw reject(INVALID);
      limit=SettlementCalculator.editLimit(before.details.group.expenses,before.details.records,original);
     }else{
      if(value.revision!=0)throw reject(CONFLICT);
      if(!uid.equals(value.payerId)&&!uid.equals(value.payeeId))throw reject(FORBIDDEN);
      if(!before.details.group.names.containsKey(value.payerId)||!before.details.group.names.containsKey(value.payeeId))throw reject(INVALID);
      limit=0;for(Transfer transfer:before.details.suggestions)if(transfer.payerId.equals(value.payerId)&&transfer.payeeId.equals(value.payeeId))limit=transfer.amountPaise;
     }
     if(value.amountPaise>limit)throw reject(INVALID);
     if(!acknowledge&&SettlementCalculator.potentialDuplicate(before.details.records,value))throw new IllegalStateException(new DuplicateSettlementException());
     long next=FirestoreFinancialRevision.advance(tx,group,g,"settlements",value.id);
     Map<String,Object> data=new HashMap<>();data.put("recordedBy",old.exists()?old.getString("recordedBy"):uid);data.put("groupId",value.groupId);data.put("payerId",value.payerId);data.put("payeeId",value.payeeId);data.put("amountPaise",value.amountPaise);data.put("settlementDate",value.settlementDate);data.put("revision",Math.addExact(value.revision,1));data.put("financialVersion",next);data.put("createdAt",old.exists()?old.get("createdAt"):FieldValue.serverTimestamp());data.put("updatedAt",FieldValue.serverTimestamp());tx.set(ref,data);
     List<Settlement> checked=new ArrayList<>(before.details.records);checked.removeIf(s->s.id.equals(value.id));checked.add(value);Balances.calculate(before.details.group.expenses,checked);
     return new Settlement(value.id,value.groupId,value.payerId,value.payeeId,value.amountPaise,value.settlementDate,Math.addExact(value.revision,1));
    }).addOnCompleteListener(executor,result->{if(!same(uid,token))callback.onError(new CloudFinancialException(CANCELLED));else if(result.isSuccessful())callback.onSuccess(result.getResult());else{
     Exception error=safe(result.getException());
     if(error instanceof CloudFinancialException&&((CloudFinancialException)error).code==FORBIDDEN){group.get(Source.SERVER).addOnCompleteListener(executor,read->{if(!same(uid,token))callback.onError(new CloudFinancialException(CANCELLED));else if(read.isSuccessful()&&read.getResult().exists()&&current(read.getResult()).contains(uid)&&FirestoreFinancialRevision.value(read.getResult())!=before.financial)callback.onError(new CloudFinancialException(CONFLICT));else callback.onError(error);});}
     else callback.onError(error);
    }});
   }
  });
 }
 @Override public void deleteSettlement(String actor,String id,Executor executor,RepositoryCallback<Boolean> callback){executor.execute(()->callback.onError(new CloudFinancialException(UNSUPPORTED)));}
 private final class Live implements ObservableQuery.Subscription {
  final String uid,group;final long token;final Executor executor;final ObservableQuery.Observer<SettlementDetails> observer;
  final AtomicBoolean active=new AtomicBoolean(true),cancelled=new AtomicBoolean();final List<ListenerRegistration> listeners=new CopyOnWriteArrayList<>();ScheduledFuture<?> deadline;
  boolean loading,dirty,confirmed;int conflicts;
  Live(String uid,String group,Executor executor,ObservableQuery.Observer<SettlementDetails> observer){this.uid=uid;this.group=group;this.executor=executor;this.observer=observer;token=epoch.get();streams.add(this);}
  void release(){streams.remove(this);for(ListenerRegistration listener:listeners)listener.remove();if(deadline!=null)deadline.cancel(false);}
  public void close(){cancelled.set(true);active.set(false);release();}
  void fail(Exception error){if(active.compareAndSet(true,false)){release();executor.execute(()->{if(!cancelled.get())observer.onError(same(uid,token)?safe(error):new CloudFinancialException(CANCELLED));});}}
  void changed(SnapshotMetadata metadata){if(!active.get())return;if(!same(uid,token)){fail(new CloudFinancialException(CANCELLED));return;}if(metadata.isFromCache()){if(confirmed)fail(new CloudFinancialException(UNAVAILABLE));return;}if(metadata.hasPendingWrites())return;if(loading){dirty=true;return;}reload();}
  void reload(){
   if(!active.get())return;loading=true;dirty=false;
   snapshot(uid,token,group,executor,new RepositoryCallback<Snapshot>(){
    public void onError(Exception error){loading=false;if(!active.get())return;if(error instanceof CloudFinancialException&&((CloudFinancialException)error).code==CONFLICT&&conflicts++<2){reload();return;}fail(error);}
    public void onSuccess(Snapshot value){loading=false;if(!active.get())return;if(!same(uid,token)){fail(new CloudFinancialException(CANCELLED));return;}if(dirty){reload();return;}conflicts=0;confirmed=true;if(deadline!=null)deadline.cancel(false);observer.onChanged(value.details);}
   });
  }
 }
 @Override public ObservableQuery<SettlementDetails> observeSettlementDetails(String supplied,String groupId){return (executor,observer)->{
  AtomicBoolean cancelled=new AtomicBoolean();AtomicReference<ObservableQuery.Subscription> source=new AtomicReference<>();
  initialized.thenRunAsync(()->{
   if(cancelled.get())return;Live live=new Live(supplied,groupId,executor,new ObservableQuery.Observer<SettlementDetails>(){public void onChanged(SettlementDetails value){if(!cancelled.get())observer.onChanged(value);}public void onError(Exception error){if(!cancelled.get())observer.onError(error);}});source.set(live);
   try{require(supplied);Rules.id(groupId);live.deadline=timer.schedule(()->live.fail(new CloudFinancialException(UNAVAILABLE)),10,TimeUnit.SECONDS);
    live.listeners.add(db.collection("groups").document(groupId).addSnapshotListener(executor,MetadataChanges.INCLUDE,(doc,error)->{if(error!=null)live.fail(error);else if(doc!=null)live.changed(doc.getMetadata());}));
    for(String collection:Arrays.asList("expenses","settlements"))live.listeners.add(db.collection(collection).whereEqualTo("groupId",groupId).addSnapshotListener(executor,MetadataChanges.INCLUDE,(docs,error)->{if(error!=null)live.fail(error);else if(docs!=null)live.changed(docs.getMetadata());}));
    if(!live.active.get()||cancelled.get())live.close();
   }catch(Exception error){live.fail(error);}
  },executor);
  return ()->{cancelled.set(true);ObservableQuery.Subscription sub=source.get();if(sub!=null)sub.close();};
 };}
 @Override public ObservableQuery<List<Settlement>> observeSettlements(String groupId){return (executor,observer)->observeSettlementDetails(actor(),groupId).subscribe(executor,new ObservableQuery.Observer<SettlementDetails>(){public void onChanged(SettlementDetails details){observer.onChanged(details.records);}public void onError(Exception error){observer.onError(error);}});}
 @Override public void close(){closed=true;sessions.close();epoch.incrementAndGet();initialized.complete(null);for(Live live:streams)live.fail(new CloudFinancialException(CANCELLED));timer.shutdown();}
}
