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
import static com.example.splitease.repository.CloudExpenseException.Code.*;

/** Server-authoritative, revision-checked expenses. Tombstones prevent stale resurrection. */
public final class FirestoreExpenseRepository implements ExpenseRepository,AutoCloseable {
 private final FirebaseFirestore db;
 private final FirebaseAuth sdk;
 private volatile SessionState session=SessionState.restoring(SessionState.Backend.FIREBASE);
 private final AtomicLong epoch=new AtomicLong();
 private final CompletableFuture<Void> initialized=new CompletableFuture<>();
 private final Set<Watch> watches=ConcurrentHashMap.newKeySet();
 private final ObservableQuery.Subscription sessions;
 private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
 private volatile boolean closed;
 public FirestoreExpenseRepository(FirebaseFirestore db,AuthService auth){
  this.db=db;sdk=FirebaseAuth.getInstance(db.getApp());
  sessions=auth.sessions().subscribe(Runnable::run,new ObservableQuery.Observer<SessionState>(){
   public void onChanged(SessionState next){String prior=identity();session=next;if(!Objects.equals(prior,identity())){epoch.incrementAndGet();for(Watch watch:watches)watch.fail(new CloudExpenseException(CANCELLED));}initialized.complete(null);}
   public void onError(Exception failure){session=SessionState.unavailable(SessionState.Backend.FIREBASE);epoch.incrementAndGet();for(Watch watch:watches)watch.fail(new CloudExpenseException(CANCELLED));initialized.complete(null);}
  });
 }
 private String identity(){return session.backend==SessionState.Backend.FIREBASE&&session.status==SessionState.Status.SIGNED_IN?session.user.id:null;}
 private String actor(){String uid=identity();return !closed&&uid!=null&&uid.equals(sdk.getUid())?uid:null;}
 private boolean same(String uid,long token){return uid!=null&&uid.equals(actor())&&epoch.get()==token;}
 private String require(String supplied)throws CloudExpenseException{String uid=actor();if(uid==null||!uid.equals(supplied))throw new CloudExpenseException(FORBIDDEN);return uid;}
 private Exception safe(Exception error){for(Throwable t=error;t!=null;t=t.getCause())if(t instanceof CloudExpenseException)return (CloudExpenseException)t;if(error instanceof IllegalArgumentException||error instanceof ArithmeticException)return new CloudExpenseException(INVALID);if(error instanceof FirebaseFirestoreException){FirebaseFirestoreException.Code code=((FirebaseFirestoreException)error).getCode();if(code==FirebaseFirestoreException.Code.PERMISSION_DENIED||code==FirebaseFirestoreException.Code.UNAUTHENTICATED)return new CloudExpenseException(FORBIDDEN);}return new CloudExpenseException(UNAVAILABLE);}
 private RuntimeException reject(CloudExpenseException.Code code){return new IllegalStateException(new CloudExpenseException(code));}
 private DocumentReference reference(String id){Rules.id(id);return db.collection("expenses").document(id);}
 @SuppressWarnings("unchecked") private List<String> current(DocumentSnapshot group){return (List<String>)group.get("memberIds");}
 private void member(DocumentSnapshot group,String uid){if(!group.exists()||!current(group).contains(uid))throw reject(FORBIDDEN);}
 @SuppressWarnings("unchecked") private Expense parse(DocumentSnapshot doc){List<ExpenseSplit> splits=new ArrayList<>();Map<String,Object> shares=(Map<String,Object>)doc.get("shares");for(String uid:(List<String>)doc.get("participants"))splits.add(new ExpenseSplit(doc.getId(),uid,((Number)shares.get(uid)).longValue()));return new Expense(doc.getId(),doc.getString("groupId"),doc.getString("title"),doc.getLong("amountPaise"),doc.getString("payerId"),doc.getString("creatorId"),doc.getString("category"),doc.getLong("expenseDate"),splits,doc.getLong("revision"),doc.getString("description"));}
 @Override public void saveExpense(String actor,Expense expense,ReceiptChange receipt,Executor executor,RepositoryCallback<Expense> callback){
  if(receipt==null||receipt.kind!=ReceiptChange.Kind.KEEP){executor.execute(()->callback.onError(new CloudExpenseException(UNSUPPORTED)));return;}saveExpense(actor,expense,executor,callback);
 }
 @Override public void saveExpense(String supplied,Expense expense,Executor executor,RepositoryCallback<Expense> callback){
  if(!initialized.isDone()){initialized.thenRunAsync(()->saveExpense(supplied,expense,executor,callback),executor);return;}
  final String uid;final Map<String,Long> shares;final DocumentReference ref;try{uid=require(supplied);shares=CloudExpenseValidation.validate(expense);ref=reference(expense.id);}catch(Exception error){executor.execute(()->callback.onError(safe(error)));return;}long token=epoch.get();
  db.runTransaction(tx->{
   if(!same(uid,token))throw reject(CANCELLED);
   DocumentSnapshot group=tx.get(db.collection("groups").document(expense.groupId));member(group,uid);DocumentSnapshot old=tx.get(ref);
   if(old.exists()){if(Boolean.TRUE.equals(old.getBoolean("deleted"))||expense.revision!=old.getLong("revision"))throw reject(CONFLICT);if(!expense.groupId.equals(old.getString("groupId"))||!expense.creatorId.equals(old.getString("creatorId")))throw reject(INVALID);}
   else if(expense.revision!=0||!uid.equals(expense.creatorId))throw reject(CONFLICT);
   List<String> active=current(group);List<String> historical=old.exists()?parse(old).splits.stream().map(s->s.userId).collect(java.util.stream.Collectors.toList()):Collections.emptyList();
   if(!active.contains(expense.payerId)&&(!old.exists()||!expense.payerId.equals(old.getString("payerId"))))throw reject(INVALID);
   for(String id:shares.keySet())if(!active.contains(id)&&!historical.contains(id))throw reject(INVALID);
   Map<String,Object> data=new HashMap<>();data.put("groupId",expense.groupId);data.put("title",expense.title.trim());data.put("description",expense.description);data.put("amountPaise",expense.amountPaise);data.put("payerId",expense.payerId);data.put("creatorId",expense.creatorId);data.put("category",expense.category);data.put("expenseDate",expense.expenseDate);data.put("participants",new ArrayList<>(shares.keySet()));data.put("shares",shares);data.put("revision",Math.addExact(expense.revision,1));data.put("deleted",false);data.put("createdAt",old.exists()?old.get("createdAt"):FieldValue.serverTimestamp());data.put("updatedAt",FieldValue.serverTimestamp());tx.set(ref,data);return new Expense(expense.id,expense.groupId,expense.title.trim(),expense.amountPaise,expense.payerId,expense.creatorId,expense.category,expense.expenseDate,expense.splits,Math.addExact(expense.revision,1),expense.description);
  }).addOnCompleteListener(executor,result->{if(!same(uid,token)){callback.onError(new CloudExpenseException(CANCELLED));return;}if(!result.isSuccessful()){
    Exception failure=result.getException();
    if(failure instanceof FirebaseFirestoreException && ((FirebaseFirestoreException)failure).getCode()==FirebaseFirestoreException.Code.PERMISSION_DENIED){
     ref.get(Source.SERVER).addOnCompleteListener(executor,read->{if(!same(uid,token))callback.onError(new CloudExpenseException(CANCELLED));else if(read.isSuccessful()&&read.getResult().exists()&&(Boolean.TRUE.equals(read.getResult().getBoolean("deleted"))||read.getResult().getLong("revision")!=expense.revision))callback.onError(new CloudExpenseException(CONFLICT));else callback.onError(safe(failure));});
    }else callback.onError(safe(failure));return;
   }callback.onSuccess(result.getResult());});
 }
 @Override public void deleteExpense(String supplied,String id,Executor executor,RepositoryCallback<Boolean> callback){executor.execute(()->callback.onError(new CloudExpenseException(CONFLICT)));}
 @Override public void deleteExpense(String supplied,Expense expected,Executor executor,RepositoryCallback<Boolean> callback){
  if(!initialized.isDone()){initialized.thenRunAsync(()->deleteExpense(supplied,expected,executor,callback),executor);return;}
  final String uid;final DocumentReference ref;try{uid=require(supplied);ref=reference(expected.id);}catch(Exception error){executor.execute(()->callback.onError(safe(error)));return;}long token=epoch.get();
  db.runTransaction(tx->{if(!same(uid,token))throw reject(CANCELLED);DocumentSnapshot group=tx.get(db.collection("groups").document(expected.groupId));member(group,uid);DocumentSnapshot old=tx.get(ref);if(!old.exists()||!expected.groupId.equals(old.getString("groupId"))||Boolean.TRUE.equals(old.getBoolean("deleted"))||old.getLong("revision")!=expected.revision)throw reject(CONFLICT);tx.update(ref,"deleted",true,"revision",Math.addExact(expected.revision,1),"updatedAt",FieldValue.serverTimestamp());return true;}).addOnCompleteListener(executor,result->{if(!same(uid,token))callback.onError(new CloudExpenseException(CANCELLED));else if(result.isSuccessful())callback.onSuccess(true);else callback.onError(safe(result.getException()));});
 }
 private final class Watch implements ObservableQuery.Subscription {
  final String uid,groupId;final long token;final Executor executor;final ObservableQuery.Observer<List<Expense>> observer;final AtomicBoolean active=new AtomicBoolean(true),cancelled=new AtomicBoolean();ListenerRegistration groupListener,expenseListener;ScheduledFuture<?> deadline;boolean groupReady,expensesReady;List<Expense> values;
  Watch(String uid,String groupId,Executor executor,ObservableQuery.Observer<List<Expense>> observer){this.uid=uid;this.groupId=groupId;this.executor=executor;this.observer=observer;token=epoch.get();watches.add(this);}
  void release(){watches.remove(this);if(groupListener!=null)groupListener.remove();if(expenseListener!=null)expenseListener.remove();if(deadline!=null)deadline.cancel(false);}
  void fail(Exception error){if(active.compareAndSet(true,false)){release();executor.execute(()->{if(!cancelled.get())observer.onError(same(uid,token)?safe(error):new CloudExpenseException(CANCELLED));});}}
  void emit(){if(!active.get())return;if(!same(uid,token)){fail(new CloudExpenseException(CANCELLED));return;}if(groupReady&&expensesReady){if(deadline!=null)deadline.cancel(false);observer.onChanged(values);}}
  boolean server(SnapshotMetadata metadata,boolean ready){if(metadata.isFromCache()){if(ready)fail(new CloudExpenseException(UNAVAILABLE));return false;}return !metadata.hasPendingWrites();}
  public void close(){cancelled.set(true);active.set(false);release();}
 }
 @Override public ObservableQuery<List<Expense>> observeExpenses(String groupId){return (executor,observer)->{
  AtomicBoolean cancelled=new AtomicBoolean();AtomicReference<ObservableQuery.Subscription> source=new AtomicReference<>();
  initialized.thenRunAsync(()->{if(cancelled.get())return;ObservableQuery.Subscription sub=readyExpenses(groupId).subscribe(executor,new ObservableQuery.Observer<List<Expense>>(){public void onChanged(List<Expense> value){if(!cancelled.get())observer.onChanged(value);}public void onError(Exception error){if(!cancelled.get())observer.onError(error);}});source.set(sub);if(cancelled.get())sub.close();},executor);
  return ()->{cancelled.set(true);ObservableQuery.Subscription sub=source.get();if(sub!=null)sub.close();};
 };}
 private ObservableQuery<List<Expense>> readyExpenses(String groupId){return (executor,observer)->{
  String uid=actor();Watch watch=new Watch(uid,groupId,executor,observer);
  try{require(uid);Rules.id(groupId);watch.deadline=timer.schedule(()->watch.fail(new CloudExpenseException(UNAVAILABLE)),10,TimeUnit.SECONDS);
   watch.groupListener=db.collection("groups").document(groupId).addSnapshotListener(executor,MetadataChanges.INCLUDE,(group,error)->{if(!watch.active.get())return;if(error!=null){watch.fail(error);return;}if(group==null||!watch.server(group.getMetadata(),watch.groupReady))return;try{member(group,uid);watch.groupReady=true;watch.emit();}catch(Exception invalid){watch.fail(invalid);}});
   watch.expenseListener=db.collection("expenses").whereEqualTo("groupId",groupId).addSnapshotListener(executor,MetadataChanges.INCLUDE,(snapshot,error)->{if(!watch.active.get())return;if(error!=null){watch.fail(error);return;}if(snapshot==null||!watch.server(snapshot.getMetadata(),watch.expensesReady))return;try{List<Expense> values=new ArrayList<>();for(DocumentSnapshot doc:snapshot.getDocuments())if(!Boolean.TRUE.equals(doc.getBoolean("deleted")))values.add(parse(doc));values.sort(Comparator.comparingLong((Expense e)->e.expenseDate).thenComparing(e->e.id));watch.values=Collections.unmodifiableList(values);watch.expensesReady=true;watch.emit();}catch(Exception invalid){watch.fail(invalid);}});
   if(!watch.active.get())watch.release();
  }catch(Exception invalid){watch.fail(invalid);}return watch;
 };}
 @Override public void close(){closed=true;sessions.close();epoch.incrementAndGet();initialized.complete(null);for(Watch watch:watches)watch.fail(new CloudExpenseException(CANCELLED));timer.shutdown();}
}
