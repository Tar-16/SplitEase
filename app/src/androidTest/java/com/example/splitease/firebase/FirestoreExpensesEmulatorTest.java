package com.example.splitease.firebase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.auth.*;
import com.example.splitease.data.firebase.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.google.android.gms.tasks.*;
import com.google.firebase.*;
import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static com.example.splitease.firebase.AuthEmulatorSupport.*;

/** Actual SDK clients + rules. Setup/legacy seed is demo-emulator-only; assertions never use admin bypass. */
@RunWith(AndroidJUnit4.class)
public class FirestoreExpensesEmulatorTest {
 private static final Map<String,Client> clients=new HashMap<>();
 private final ExecutorService callbacks=Executors.newSingleThreadExecutor(r->new Thread(r,"group-test-callbacks"));
 private Client a,b,c;
 private static final class Client {
  final FirebaseAuth sdk;final FirebaseFirestore db;final FirestoreProfileRepository profiles;FirebaseAuthService auth;FirestoreGroupRepository groups;FirestoreExpenseRepository expenses;String uid;
  Client(String name){FirebaseOptions options=new FirebaseOptions.Builder().setProjectId(PROJECT).setApplicationId("1:1234567890:android:0000000000000000").setApiKey("demo-api-key").build();FirebaseApp app=FirebaseApp.initializeApp(ApplicationProvider.getApplicationContext(),options,"ExpenseTest"+name);sdk=FirebaseAuth.getInstance(app);sdk.useEmulator("127.0.0.1",9099);db=FirebaseFirestore.getInstance(app);db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build());db.useEmulator("127.0.0.1",8080);profiles=new FirestoreProfileRepository(db,sdk);}
 }
 private static <T>T task(Task<T> task)throws Exception{return Tasks.await(task,25,TimeUnit.SECONDS);}
 private static JSONObject admin(String method,String path,JSONObject body)throws Exception{
  if(!path.startsWith("/emulator/v1/projects/"+PROJECT+"/")&&!path.startsWith("/v1/projects/"+PROJECT+"/databases/(default)/documents/groups/"))throw new IllegalArgumentException("Demo fixture only");
  HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:8080"+path).openConnection();connection.setRequestMethod(method);connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer owner");connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
  try{if(body!=null){connection.setDoOutput(true);try(OutputStream output=connection.getOutputStream()){output.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}assertEquals(200,connection.getResponseCode());try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] data=new byte[4096];int n;while((n=input.read(data))!=-1)output.write(data,0,n);return new JSONObject(output.toString("UTF-8"));}}finally{connection.disconnect();}
 }
 private Client client(String name)throws Exception{
  Client value=clients.get(name);if(value==null){value=new Client(name);clients.put(name,value);}String email="group-"+name.toLowerCase(Locale.ROOT)+"@example.test";
  try{task(value.sdk.signInWithEmailAndPassword(email,PASSWORD));}catch(ExecutionException missing){task(value.sdk.createUserWithEmailAndPassword(email,PASSWORD));}
  final Client owner=value;await((RepositoryCallback<CloudProfile> cb)->owner.profiles.createOwnProfile("Group_"+name,callbacks,cb));value.uid=value.sdk.getUid();
  value.auth=new FirebaseAuthService(new ProfiledFirebaseAuthBackend(new FirebaseSdkAuthBackend(value.sdk),value.profiles));state(value.auth,SessionState.Status.SIGNED_IN);value.groups=new FirestoreGroupRepository(value.db,value.auth,value.profiles);value.expenses=new FirestoreExpenseRepository(value.db,value.auth);value.groups.attachExpenses(value.expenses);state(value.auth,SessionState.Status.SIGNED_IN);return value;
 }
 @Before public void start()throws Exception{Assume.assumeTrue("Explicit group emulators required","true".equals(InstrumentationRegistry.getArguments().getString("groupsEmulator")));requireEmulator();admin("DELETE","/emulator/v1/projects/"+PROJECT+"/databases/(default)/documents",null);a=client("A");b=client("B");c=client("C");}
 @After public void stop(){for(Client value:clients.values()){if(value.groups!=null)value.groups.close();if(value.expenses!=null)value.expenses.close();if(value.auth!=null)value.auth.close();}callbacks.shutdown();}
 private Group create(Client owner,Group.Type type,String...ids)throws Exception{Group input=new Group(UUID.randomUUID().toString(),"Test "+type,type,owner.uid,0);return await((RepositoryCallback<Group> cb)->owner.groups.createGroup(owner.uid,input,Arrays.asList(ids),callbacks,cb));}
 private final class Stream<T> implements AutoCloseable {
  final BlockingQueue<T> values=new LinkedBlockingQueue<>();final CompletableFuture<Exception> error=new CompletableFuture<>();final ObservableQuery.Subscription sub;
  Stream(ObservableQuery<T> source){sub=source.subscribe(callbacks,new ObservableQuery.Observer<T>(){public void onChanged(T value){assertEquals("group-test-callbacks",Thread.currentThread().getName());values.add(value);}public void onError(Exception failure){error.complete(failure);}});}
  T next()throws Exception{T value=values.poll(20,TimeUnit.SECONDS);if(value==null&&error.isDone())throw new AssertionError("Stream error",error.get());assertNotNull(value);return value;}
  public void close(){sub.close();}
 }
 private GroupDetails detail(Client client,String id)throws Exception{try(Stream<GroupDetails> stream=new Stream<>(client.groups.observeGroupDetails(client.uid,id))){return stream.next();}}
 private void denied(Task<?> operation)throws Exception{try{task(operation);fail("Rules must deny");}catch(ExecutionException error){assertTrue(error.getCause() instanceof FirebaseFirestoreException);assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED,((FirebaseFirestoreException)error.getCause()).getCode());}}
 private void groupError(CloudGroupException.Code expected,Call<?> operation)throws Exception{try{await(operation);fail("Expected "+expected);}catch(ExecutionException error){assertTrue(error.getCause() instanceof CloudGroupException);assertEquals(expected,((CloudGroupException)error.getCause()).code);}}

 private Expense expense(Group group,long amount,String payer,String...ids){String id=UUID.randomUUID().toString();List<ExpenseSplit> splits=new ArrayList<>();for(Map.Entry<String,Long> share:com.example.splitease.domain.Rules.equalSplitIdentities(amount,Arrays.asList(ids)).entrySet())splits.add(new ExpenseSplit(id,share.getKey(),share.getValue()));return new Expense(id,group.id,"Dinner",amount,payer,a.uid,"Food",0,splits,0,"shared meal");}
 private Expense changed(Expense old,long amount){List<ExpenseSplit> shares=new ArrayList<>();List<String> ids=new ArrayList<>();for(ExpenseSplit split:old.splits)ids.add(split.userId);for(Map.Entry<String,Long> share:com.example.splitease.domain.Rules.equalSplitIdentities(amount,ids).entrySet())shares.add(new ExpenseSplit(old.id,share.getKey(),share.getValue()));return new Expense(old.id,old.groupId,old.title,amount,old.payerId,old.creatorId,old.category,old.expenseDate,shares,old.revision,old.description);}
 private Expense save(Client client,Expense value)throws Exception{return await((RepositoryCallback<Expense> cb)->client.expenses.saveExpense(client.uid,value,callbacks,cb));}
 private void expenseError(CloudExpenseException.Code expected,Call<?> operation)throws Exception{try{await(operation);fail("Expected "+expected);}catch(ExecutionException error){assertTrue(error.getCause() instanceof CloudExpenseException);assertEquals(expected,((CloudExpenseException)error.getCause()).code);}}
 private static <T>RepositoryCallback<T> future(CompletableFuture<T> result){return new RepositoryCallback<T>(){public void onSuccess(T value){result.complete(value);}public void onError(Exception error){result.completeExceptionally(error);}};}
 @Test public void createEditDeleteAndTwoClientRealtimeRemainSeparateFromBalances()throws Exception{
  Group group=create(a,Group.Type.TRIP,b.uid);
  try(Stream<List<Expense>> live=new Stream<>(b.expenses.observeExpenses(group.id))){assertTrue(live.next().isEmpty());Expense first=save(a,expense(group,101,a.uid,a.uid,b.uid));assertEquals(1,first.revision);assertEquals(101,first.splits.stream().mapToLong(x->x.sharePaise).sum());assertEquals(first.id,live.next().get(0).id);assertEquals("shared meal",first.description);
   Expense edited=save(b,changed(first,999));assertEquals(2,edited.revision);assertEquals(999,live.next().get(0).amountPaise);GroupDetails detail=detail(b,group.id);assertTrue(detail.expensesAvailable);assertFalse(detail.accountingAvailable);assertFalse(detail.canLeave);assertEquals(1,detail.expenses.size());
   await((RepositoryCallback<Boolean> cb)->b.expenses.deleteExpense(b.uid,edited,callbacks,cb));assertTrue(live.next().isEmpty());assertTrue(task(a.db.collection("expenses").document(first.id).get(Source.SERVER)).getBoolean("deleted"));expenseError(CloudExpenseException.Code.CONFLICT,(RepositoryCallback<Expense> cb)->a.expenses.saveExpense(a.uid,first,callbacks,cb));
  }
 }
 @Test public void concurrentEditsAndStaleDeleteHaveOneWinner()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid);Expense original=save(a,expense(group,100,a.uid,a.uid,b.uid));CompletableFuture<Expense> one=new CompletableFuture<>(),two=new CompletableFuture<>();a.expenses.saveExpense(a.uid,changed(original,200),callbacks,future(one));b.expenses.saveExpense(b.uid,changed(original,300),callbacks,future(two));int wins=0,conflicts=0;for(CompletableFuture<Expense> attempt:Arrays.asList(one,two))try{attempt.get(30,TimeUnit.SECONDS);wins++;}catch(ExecutionException failure){assertEquals(CloudExpenseException.Code.CONFLICT,((CloudExpenseException)failure.getCause()).code);conflicts++;}assertEquals(1,wins);assertEquals(1,conflicts);expenseError(CloudExpenseException.Code.CONFLICT,(RepositoryCallback<Boolean> cb)->a.expenses.deleteExpense(a.uid,original,callbacks,cb));
 }
 @Test public void maliciousRawWritesAndUnauthorizedAccountsAreDenied()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid);Expense expense=save(a,expense(group,101,a.uid,a.uid,b.uid));DocumentReference ref=a.db.collection("expenses").document(expense.id);denied(c.db.collection("expenses").document(expense.id).get(Source.SERVER));denied(a.db.collection("expenses").get(Source.SERVER));denied(ref.delete());
  for(Map<String,Object> change:Arrays.asList(Collections.<String,Object>singletonMap("amountPaise",0L),Collections.<String,Object>singletonMap("amountPaise",-1L),Collections.<String,Object>singletonMap("amountPaise",101.5),Collections.<String,Object>singletonMap("amountPaise",Long.MAX_VALUE),Collections.<String,Object>singletonMap("shares",Collections.singletonMap(a.uid,101L)),Collections.<String,Object>singletonMap("participants",Arrays.asList(a.uid,a.uid)),Collections.<String,Object>singletonMap("payerId",c.uid),Collections.<String,Object>singletonMap("creatorId",b.uid),Collections.<String,Object>singletonMap("revision",1L),Collections.<String,Object>singletonMap("groupId",UUID.randomUUID().toString()))){Map<String,Object> write=new HashMap<>(change);write.put("updatedAt",FieldValue.serverTimestamp());if(!write.containsKey("revision"))write.put("revision",2L);denied(ref.update(write));}
  List<String> ordered=new ArrayList<>(Arrays.asList(a.uid,b.uid));Collections.sort(ordered);
  for(int kind=0;kind<5;kind++){Map<String,Object> forged=new HashMap<>();for(ExpenseSplit split:expense.splits)forged.put(split.userId,split.sharePaise);if(kind==0)forged.put(ordered.get(0),52L);if(kind==1){forged.put(ordered.get(0),50L);forged.put(ordered.get(1),51L);}if(kind==2)forged.put(ordered.get(0),-1L);if(kind==3)forged.put(ordered.get(0),51.0);if(kind==4)forged.put(c.uid,0L);denied(ref.update("shares",forged,"revision",2L,"updatedAt",FieldValue.serverTimestamp()));}
  denied(ref.update("participants",Collections.emptyList(),"shares",Collections.emptyMap(),"revision",2L,"updatedAt",FieldValue.serverTimestamp()));
  List<String> reversed=new ArrayList<>(Arrays.asList(a.uid,b.uid));Collections.sort(reversed,Collections.reverseOrder());denied(ref.update("participants",reversed,"revision",2L,"updatedAt",FieldValue.serverTimestamp()));
  expenseError(CloudExpenseException.Code.FORBIDDEN,(RepositoryCallback<Expense> cb)->c.expenses.saveExpense(c.uid,changed(expense,200),callbacks,cb));expenseError(CloudExpenseException.Code.FORBIDDEN,(RepositoryCallback<Expense> cb)->a.expenses.saveExpense(b.uid,changed(expense,200),callbacks,cb));
 }
 @Test public void eightSharesMaximumAmountSingleParticipantAndPersistence()throws Exception{
  List<String> members=new ArrayList<>(Arrays.asList(b.uid,c.uid));for(String name:Arrays.asList("D","E","F","G","H"))members.add(client(name).uid);Group group=create(a,Group.Type.FLAT,members.toArray(new String[0]));members.add(a.uid);Expense saved=save(a,expense(group,com.example.splitease.domain.CloudExpenseValidation.MAX_PAISE,a.uid,members.toArray(new String[0])));assertEquals(8,saved.splits.size());assertEquals(com.example.splitease.domain.CloudExpenseValidation.MAX_PAISE,saved.splits.stream().mapToLong(x->x.sharePaise).sum());Expense eightEdited=save(b,changed(saved,com.example.splitease.domain.CloudExpenseValidation.MAX_PAISE-1));assertEquals(2,eightEdited.revision);assertEquals(com.example.splitease.domain.CloudExpenseValidation.MAX_PAISE-1,eightEdited.splits.stream().mapToLong(x->x.sharePaise).sum());Expense single=save(a,expense(group,1,b.uid,b.uid));assertEquals(1,single.splits.get(0).sharePaise);FirestoreExpenseRepository reopened=new FirestoreExpenseRepository(a.db,a.auth);try(Stream<List<Expense>> stream=new Stream<>(reopened.observeExpenses(group.id))){assertEquals(2,stream.next().size());}finally{reopened.close();}
 }
 @Test public void membershipLossKeepsHistoryButDeniesDepartedMutations()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid);Expense old=save(a,expense(group,101,b.uid,a.uid,b.uid));String path="/v1/projects/"+PROJECT+"/databases/(default)/documents/groups/"+group.id;JSONObject stored=admin("GET",path,null);JSONObject fields=stored.getJSONObject("fields");fields.getJSONObject("memberIds").put("arrayValue",new JSONObject().put("values",new JSONArray().put(new JSONObject().put("stringValue",a.uid))));JSONObject departed=fields.getJSONObject("members").getJSONObject("mapValue").getJSONObject("fields").getJSONObject(b.uid).getJSONObject("mapValue").getJSONObject("fields");departed.put("status",new JSONObject().put("stringValue","LEFT"));departed.put("leftAt",new JSONObject().put("timestampValue","2026-01-01T00:00:00Z"));
  try(Stream<List<Expense>> watching=new Stream<>(b.expenses.observeExpenses(group.id))){watching.next();admin("PATCH",path,stored);assertNotNull(watching.error.get(20,TimeUnit.SECONDS));}Expense retained=save(a,changed(old,201));assertEquals(b.uid,retained.payerId);assertEquals(2,retained.splits.size());denied(b.db.collection("expenses").document(old.id).get(Source.SERVER));expenseError(CloudExpenseException.Code.FORBIDDEN,(RepositoryCallback<Boolean> cb)->b.expenses.deleteExpense(b.uid,retained,callbacks,cb));expenseError(CloudExpenseException.Code.INVALID,(RepositoryCallback<Expense> cb)->a.expenses.saveExpense(a.uid,expense(group,50,b.uid,b.uid),callbacks,cb));
 }
 @Test public void accountSwitchCancelsOldStreamsAndQueuedSubscriptionsCanClose()throws Exception{
  Group group=create(a,Group.Type.TRIP,b.uid);save(a,expense(group,100,a.uid,a.uid,b.uid));try(Stream<List<Expense>> live=new Stream<>(a.expenses.observeExpenses(group.id))){live.next();await((RepositoryCallback<Boolean> cb)->a.auth.logout(callbacks,cb));assertEquals(CloudExpenseException.Code.CANCELLED,((CloudExpenseException)live.error.get(20,TimeUnit.SECONDS)).code);}state(a.auth,SessionState.Status.SIGNED_OUT);
  BlockingQueue<Runnable> queued=new LinkedBlockingQueue<>();List<List<Expense>> values=new ArrayList<>();ObservableQuery.Subscription sub=b.expenses.observeExpenses(group.id).subscribe(queued::add,new ObservableQuery.Observer<List<Expense>>(){public void onChanged(List<Expense> value){values.add(value);}public void onError(Exception failure){fail();}});Runnable task=queued.poll(10,TimeUnit.SECONDS);assertNotNull(task);sub.close();task.run();assertTrue(values.isEmpty());
 }
}
