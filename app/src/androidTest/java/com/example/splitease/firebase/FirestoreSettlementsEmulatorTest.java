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
public class FirestoreSettlementsEmulatorTest {
 private static final Map<String,Client> clients=new HashMap<>();
 private final ExecutorService callbacks=Executors.newSingleThreadExecutor(r->new Thread(r,"group-test-callbacks"));
 private Client a,b,c;
 private static final class Client {
  final FirebaseAuth sdk;final FirebaseFirestore db;final FirestoreProfileRepository profiles;FirebaseAuthService auth;FirestoreGroupRepository groups;FirestoreExpenseRepository expenses;FirestoreSettlementRepository settlements;String uid;
  Client(String name){FirebaseOptions options=new FirebaseOptions.Builder().setProjectId(PROJECT).setApplicationId("1:1234567890:android:0000000000000000").setApiKey("demo-api-key").build();FirebaseApp app=FirebaseApp.initializeApp(ApplicationProvider.getApplicationContext(),options,"SettlementTest"+name);sdk=FirebaseAuth.getInstance(app);sdk.useEmulator("127.0.0.1",9099);db=FirebaseFirestore.getInstance(app);db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build());db.useEmulator("127.0.0.1",8080);profiles=new FirestoreProfileRepository(db,sdk);}
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
  value.auth=new FirebaseAuthService(new ProfiledFirebaseAuthBackend(new FirebaseSdkAuthBackend(value.sdk),value.profiles));state(value.auth,SessionState.Status.SIGNED_IN);value.groups=new FirestoreGroupRepository(value.db,value.auth,value.profiles);value.expenses=new FirestoreExpenseRepository(value.db,value.auth);value.groups.attachExpenses(value.expenses);value.settlements=new FirestoreSettlementRepository(value.db,value.auth);value.groups.attachAccounting(value.settlements);state(value.auth,SessionState.Status.SIGNED_IN);return value;
 }
 @Before public void start()throws Exception{Assume.assumeTrue("Explicit group emulators required","true".equals(InstrumentationRegistry.getArguments().getString("groupsEmulator")));requireEmulator();admin("DELETE","/emulator/v1/projects/"+PROJECT+"/databases/(default)/documents",null);a=client("A");b=client("B");c=client("C");}
 @After public void stop(){for(Client value:clients.values()){if(value.groups!=null)value.groups.close();if(value.expenses!=null)value.expenses.close();if(value.settlements!=null)value.settlements.close();if(value.auth!=null)value.auth.close();}callbacks.shutdown();}
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

 private Settlement payment(Group group,long amount){return new Settlement(UUID.randomUUID().toString(),group.id,b.uid,a.uid,amount,0,0);}
 private Settlement pay(Client client,Settlement value,boolean ack)throws Exception{return await((RepositoryCallback<Settlement> cb)->client.settlements.saveSettlement(client.uid,value,ack,callbacks,cb));}
 private Settlement edit(Settlement old,long amount,long date){return new Settlement(old.id,old.groupId,old.payerId,old.payeeId,amount,date,old.revision);}
 private SettlementDetails financial(Client client,String group)throws Exception{try(Stream<SettlementDetails> live=new Stream<>(client.settlements.observeSettlementDetails(client.uid,group))){return live.next();}}
 private void financialError(CloudFinancialException.Code expected,Call<?> call)throws Exception{try{await(call);fail("Expected "+expected);}catch(ExecutionException error){assertTrue(error.getCause() instanceof CloudFinancialException);assertEquals(expected,((CloudFinancialException)error.getCause()).code);}}
 @Test public void partialFullPaymentsAndExpenseChangesRefreshDerivedBalances()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid,c.uid);Expense spending=save(a,expense(group,101,a.uid,b.uid));
  try(Stream<SettlementDetails> live=new Stream<>(b.settlements.observeSettlementDetails(b.uid,group.id))){
   assertEquals(Long.valueOf(101),live.next().group.balances.get(a.uid));Settlement partial=pay(b,payment(group,40),false);
   SettlementDetails changed=live.next();while(changed.group.balances.get(a.uid)!=61)changed=live.next();assertEquals(Long.valueOf(-61),changed.group.balances.get(b.uid));assertFalse(changed.group.canLeave);
   pay(a,payment(group,61),false);SettlementDetails done=financial(c,group.id);assertEquals(Long.valueOf(0),done.group.balances.get(a.uid));assertTrue(done.suggestions.isEmpty());assertEquals(101,done.group.totalExpenses);
   Expense updated=save(a,changed(spending,201));assertEquals(Long.valueOf(100),financial(b,group.id).group.balances.get(a.uid));
   await((RepositoryCallback<Boolean> cb)->a.expenses.deleteExpense(a.uid,updated,callbacks,cb));assertEquals(Long.valueOf(-101),financial(a,group.id).group.balances.get(a.uid));pay(c,edit(partial,30,86400000L),false);assertEquals(Long.valueOf(-91),financial(a,group.id).group.balances.get(a.uid));
  }
 }
 @Test public void duplicateAcknowledgementAndAnyMemberCorrectionPreserveIdentities()throws Exception{
  Group group=create(a,Group.Type.TRIP,b.uid,c.uid);save(a,expense(group,100,a.uid,b.uid));Settlement first=pay(b,payment(group,20),false),duplicate=payment(group,20);
  try{pay(b,duplicate,false);fail();}catch(ExecutionException e){assertTrue(e.getCause() instanceof DuplicateSettlementException);}
  pay(a,duplicate,true);Settlement corrected=pay(c,edit(first,25,86400000L),false);assertEquals(2,corrected.revision);assertEquals(86400000L,corrected.settlementDate);assertEquals(Long.valueOf(55),financial(c,group.id).group.balances.get(a.uid));
  financialError(CloudFinancialException.Code.CONFLICT,(RepositoryCallback<Settlement> cb)->b.settlements.saveSettlement(b.uid,edit(first,30,0),callbacks,cb));
  Settlement forged=new Settlement(corrected.id,group.id,a.uid,b.uid,10,0,corrected.revision);financialError(CloudFinancialException.Code.INVALID,(RepositoryCallback<Settlement> cb)->a.settlements.saveSettlement(a.uid,forged,callbacks,cb));
 }
 @Test public void concurrentPaymentsAndEditsNeverSilentlyOverwrite()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid,c.uid);save(a,expense(group,100,a.uid,b.uid));CompletableFuture<Settlement> one=new CompletableFuture<>(),two=new CompletableFuture<>();a.settlements.saveSettlement(a.uid,payment(group,80),true,callbacks,future(one));b.settlements.saveSettlement(b.uid,payment(group,80),true,callbacks,future(two));int winners=0;Settlement winner=null;
  for(CompletableFuture<Settlement> result:Arrays.asList(one,two))try{winner=result.get(35,TimeUnit.SECONDS);winners++;}catch(ExecutionException error){assertTrue(error.getCause() instanceof CloudFinancialException);CloudFinancialException.Code code=((CloudFinancialException)error.getCause()).code;assertTrue(code==CloudFinancialException.Code.CONFLICT||code==CloudFinancialException.Code.INVALID);}
  assertEquals(1,winners);assertEquals(Long.valueOf(20),financial(a,group.id).group.balances.get(a.uid));Settlement original=winner;one=new CompletableFuture<>();two=new CompletableFuture<>();a.settlements.saveSettlement(a.uid,edit(original,70,0),true,callbacks,future(one));c.settlements.saveSettlement(c.uid,edit(original,60,0),true,callbacks,future(two));winners=0;
  for(CompletableFuture<Settlement> result:Arrays.asList(one,two))try{result.get(35,TimeUnit.SECONDS);winners++;}catch(ExecutionException error){assertEquals(CloudFinancialException.Code.CONFLICT,((CloudFinancialException)error.getCause()).code);}assertEquals(1,winners);
 }
 private Map<String,Object> paymentData(Group group,Object amount,String payer,String payee){Map<String,Object> data=new HashMap<>();data.put("groupId",group.id);data.put("payerId",payer);data.put("payeeId",payee);data.put("amountPaise",amount);data.put("settlementDate",0L);data.put("revision",1L);data.put("recordedBy",a.uid);data.put("createdAt",FieldValue.serverTimestamp());data.put("updatedAt",FieldValue.serverTimestamp());return data;}
 private Task<Void> rawPayment(Client client,Group group,String id,Map<String,Object> data)throws Exception{
  DocumentReference ref=client.db.collection("groups").document(group.id);DocumentSnapshot before=task(ref.get(Source.SERVER));long version=before.contains("financialRevision")?before.getLong("financialRevision")+1:1;
  Map<String,Object> record=new HashMap<>();record.put("collection","settlements");record.put("id",id);Map<String,Object> value=new HashMap<>(data);value.put("financialVersion",version);
  WriteBatch batch=client.db.batch();batch.update(ref,"financialRevision",version,"financialRecord",record);batch.set(client.db.collection("settlements").document(id),value);return batch.commit();
 }
 @Test public void adversarialRulesRejectBadAmountsForgedPartiesAndUnfencedWrites()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid,c.uid);Expense expense=save(a,expense(group,100,a.uid,b.uid));Settlement valid=pay(b,payment(group,20),false);DocumentReference ref=a.db.collection("settlements").document(valid.id);
  denied(c.db.collection("settlements").document(valid.id).update("amountPaise",10L));denied(ref.delete());denied(a.db.collection("settlements").get(Source.SERVER));Client outsider=client("D");denied(outsider.db.collection("settlements").document(valid.id).get(Source.SERVER));
  for(Object bad:Arrays.asList(0L,-1L,1.5,Long.MAX_VALUE))denied(rawPayment(a,group,UUID.randomUUID().toString(),paymentData(group,bad,b.uid,a.uid)));
  denied(rawPayment(a,group,UUID.randomUUID().toString(),paymentData(group,1L,b.uid,b.uid)));denied(rawPayment(c,group,UUID.randomUUID().toString(),paymentData(group,1L,b.uid,a.uid)));denied(rawPayment(a,group,UUID.randomUUID().toString(),paymentData(group,1L,outsider.uid,a.uid)));
  Map<String,Object> old=new HashMap<>(task(ref.get(Source.SERVER)).getData());old.put("updatedAt",FieldValue.serverTimestamp());old.put("revision",2L);old.put("payerId",c.uid);denied(rawPayment(a,group,valid.id,old));
  financialError(CloudFinancialException.Code.INVALID,(RepositoryCallback<Settlement> cb)->a.settlements.saveSettlement(a.uid,payment(group,81),callbacks,cb));financialError(CloudFinancialException.Code.FORBIDDEN,(RepositoryCallback<Settlement> cb)->c.settlements.saveSettlement(c.uid,payment(group,1),callbacks,cb));
  DocumentReference g=a.db.collection("groups").document(group.id);DocumentSnapshot base=task(g.get(Source.SERVER));long next=base.getLong("financialRevision")+1;Map<String,Object> record=new HashMap<>();record.put("collection","expenses");record.put("id",expense.id);WriteBatch invalid=a.db.batch();invalid.update(g,"financialRevision",next,"financialRecord",record);invalid.update(a.db.collection("expenses").document(expense.id),"shares",Collections.singletonMap(b.uid,99L),"revision",2L,"financialVersion",next,"updatedAt",FieldValue.serverTimestamp());denied(invalid.commit());assertEquals(base.getLong("financialRevision"),task(g.get(Source.SERVER)).getLong("financialRevision"));
 }
 @Test public void historicalRecordsPersistAndDepartedActorsCannotMutate()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid,c.uid);save(a,expense(group,100,a.uid,b.uid));String path="/v1/projects/"+PROJECT+"/databases/(default)/documents/groups/"+group.id;JSONObject stored=admin("GET",path,null),fields=stored.getJSONObject("fields");fields.getJSONObject("memberIds").put("arrayValue",new JSONObject().put("values",new JSONArray().put(new JSONObject().put("stringValue",a.uid)).put(new JSONObject().put("stringValue",c.uid))));JSONObject row=fields.getJSONObject("members").getJSONObject("mapValue").getJSONObject("fields").getJSONObject(b.uid).getJSONObject("mapValue").getJSONObject("fields");row.put("status",new JSONObject().put("stringValue","LEFT"));row.put("leftAt",new JSONObject().put("timestampValue","2026-01-01T00:00:00Z"));admin("PATCH",path,stored);
  Settlement first=pay(a,payment(group,30),false),corrected=pay(c,edit(first,20,0),false);assertEquals(b.uid,corrected.payerId);assertEquals(Long.valueOf(-80),financial(a,group.id).group.balances.get(b.uid));financialError(CloudFinancialException.Code.FORBIDDEN,(RepositoryCallback<Settlement> cb)->b.settlements.saveSettlement(b.uid,edit(corrected,10,0),callbacks,cb));
  FirestoreSettlementRepository reopened=new FirestoreSettlementRepository(a.db,a.auth);try(Stream<SettlementDetails> live=new Stream<>(reopened.observeSettlementDetails(a.uid,group.id))){assertEquals(1,live.next().records.size());}finally{reopened.close();}
 }
 /** Demonstrates the client trust boundary, not an acceptable production financial policy. */
 @Test public void directDemoClaimsBypassHistoryCeilingsAndDuplicateWarnings()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid,c.uid);save(a,expense(group,100,a.uid,b.uid));
  financialError(CloudFinancialException.Code.INVALID,(RepositoryCallback<Settlement> cb)->a.settlements.saveSettlement(a.uid,payment(group,150),callbacks,cb));
  String id=UUID.randomUUID().toString();task(rawPayment(a,group,id,paymentData(group,150L,b.uid,a.uid)));
  assertEquals(Long.valueOf(-50),financial(a,group.id).group.balances.get(a.uid));
  Settlement original=new Settlement(id,group.id,b.uid,a.uid,150,0,1);
  financialError(CloudFinancialException.Code.INVALID,(RepositoryCallback<Settlement> cb)->c.settlements.saveSettlement(c.uid,edit(original,250,0),callbacks,cb));
  Map<String,Object> correction=new HashMap<>(task(a.db.collection("settlements").document(id).get(Source.SERVER)).getData());correction.put("amountPaise",250L);correction.put("revision",2L);correction.put("updatedAt",FieldValue.serverTimestamp());
  task(rawPayment(c,group,id,correction));assertEquals(Long.valueOf(-150),financial(a,group.id).group.balances.get(a.uid));
  denied(rawPayment(c,group,id,correction)); // stale document revision, even with a fresh group fence
  Map<String,Object> badDate=paymentData(group,1L,b.uid,a.uid);badDate.put("settlementDate",-1L);denied(rawPayment(a,group,UUID.randomUUID().toString(),badDate));
  badDate.put("settlementDate",1.5);denied(rawPayment(a,group,UUID.randomUUID().toString(),badDate));
  // No acknowledgement is carried in a raw write; identical-looking claims are admitted.
  task(rawPayment(a,group,UUID.randomUUID().toString(),paymentData(group,250L,b.uid,a.uid)));
  SettlementDetails result=financial(a,group.id);assertEquals(2,result.records.size());assertEquals(Long.valueOf(-400),result.group.balances.get(a.uid));assertEquals(Long.valueOf(400),result.group.balances.get(b.uid));
  Group noDebt=create(a,Group.Type.TRIP,b.uid);task(rawPayment(a,noDebt,UUID.randomUUID().toString(),paymentData(noDebt,1L,b.uid,a.uid)));assertEquals(Long.valueOf(-1),financial(a,noDebt.id).group.balances.get(a.uid));
 }
 @Test public void financialFenceCannotBeRemovedByAdmin()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid);save(a,expense(group,100,a.uid,b.uid));DocumentReference ref=a.db.collection("groups").document(group.id);DocumentSnapshot before=task(ref.get(Source.SERVER));
  Map<String,Object> reset=new HashMap<>(before.getData());reset.remove("financialRevision");reset.remove("financialRecord");reset.put("revision",before.getLong("revision")+1);reset.put("updatedAt",FieldValue.serverTimestamp());
  denied(ref.set(reset));
  await((RepositoryCallback<Boolean> cb)->a.groups.transferAdmin(a.uid,group.id,b.uid,callbacks,cb));
  DocumentSnapshot after=task(b.db.collection("groups").document(group.id).get(Source.SERVER));assertEquals(before.getLong("financialRevision"),after.getLong("financialRevision"));assertEquals(before.get("financialRecord"),after.get("financialRecord"));assertEquals(b.uid,after.getString("adminId"));
 }
 /** Unsigned tokens are accepted ONLY by the local emulator; never send this fixture to a live host. */
 @Test public void nonDemoAudienceCannotWriteFinancialRecordsOrAdvanceFence()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid);save(a,expense(group,100,a.uid,b.uid));
  String token=task(a.sdk.getCurrentUser().getIdToken(false)).getToken();
  JSONObject claims=new JSONObject(new String(android.util.Base64.decode(token.split("\\.")[1],android.util.Base64.URL_SAFE),java.nio.charset.StandardCharsets.UTF_8));
  assertEquals(PROJECT,claims.getString("aud"));
  // Matching-audience positive control uses the actual Auth Emulator SDK token.
  assertEquals(200,rawRestClaim(group,token));
  DocumentSnapshot before=task(a.db.collection("groups").document(group.id).get(Source.SERVER));
  claims.put("aud","non-demo-security-review");
  int flags=android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING;
  String other=android.util.Base64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),flags)+"."+android.util.Base64.encodeToString(claims.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),flags)+".";
  assertEquals(403,rawRestClaim(group,other));
  assertEquals(before.getLong("financialRevision"),task(a.db.collection("groups").document(group.id).get(Source.SERVER)).getLong("financialRevision"));
  assertEquals(1,financial(a,group.id).records.size());
 }
 private int rawRestClaim(Group group,String token)throws Exception{
  DocumentSnapshot before=task(a.db.collection("groups").document(group.id).get(Source.SERVER));long version=before.getLong("financialRevision")+1;
  String prefix="projects/"+PROJECT+"/databases/(default)/documents/",id=UUID.randomUUID().toString();
  JSONObject pointer=new JSONObject().put("collection",new JSONObject().put("stringValue","settlements")).put("id",new JSONObject().put("stringValue",id));
  JSONObject groupFields=new JSONObject().put("financialRevision",new JSONObject().put("integerValue",Long.toString(version))).put("financialRecord",new JSONObject().put("mapValue",new JSONObject().put("fields",pointer)));
  JSONObject paymentFields=new JSONObject();String[] keys={"groupId","payerId","payeeId","recordedBy"},values={group.id,b.uid,a.uid,a.uid};
  for(int i=0;i<keys.length;i++)paymentFields.put(keys[i],new JSONObject().put("stringValue",values[i]));
  paymentFields.put("amountPaise",new JSONObject().put("integerValue","1")).put("settlementDate",new JSONObject().put("integerValue","0")).put("revision",new JSONObject().put("integerValue","1")).put("financialVersion",new JSONObject().put("integerValue",Long.toString(version)));
  JSONArray transforms=new JSONArray();for(String field:Arrays.asList("createdAt","updatedAt"))transforms.put(new JSONObject().put("fieldPath",field).put("setToServerValue","REQUEST_TIME"));
  JSONArray writes=new JSONArray().put(new JSONObject().put("update",new JSONObject().put("name",prefix+"groups/"+group.id).put("fields",groupFields)).put("updateMask",new JSONObject().put("fieldPaths",new JSONArray().put("financialRevision").put("financialRecord"))))
    .put(new JSONObject().put("update",new JSONObject().put("name",prefix+"settlements/"+id).put("fields",paymentFields)).put("updateTransforms",transforms));
  HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:8080/v1/projects/"+PROJECT+"/databases/(default)/documents:commit").openConnection();
  connection.setRequestMethod("POST");connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer "+token);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
  try{connection.setDoOutput(true);try(OutputStream out=connection.getOutputStream()){out.write(new JSONObject().put("writes",writes).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}return connection.getResponseCode();}finally{connection.disconnect();}
 }
 @Test public void accountSwitchCancelsOldAccountingAndScopesActors()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid,c.uid);save(a,expense(group,100,a.uid,b.uid));try(Stream<SettlementDetails> live=new Stream<>(a.settlements.observeSettlementDetails(a.uid,group.id))){live.next();task(a.sdk.signInWithEmailAndPassword("group-c@example.test",PASSWORD));CompletableFuture<Boolean> ready=new CompletableFuture<>();ObservableQuery.Subscription session=a.auth.sessions().subscribe(callbacks,new ObservableQuery.Observer<SessionState>(){public void onChanged(SessionState value){if(value.status==SessionState.Status.SIGNED_IN&&c.uid.equals(value.user.id))ready.complete(true);}public void onError(Exception error){ready.completeExceptionally(error);}});try{ready.get(20,TimeUnit.SECONDS);}finally{session.close();}assertEquals(CloudFinancialException.Code.CANCELLED,((CloudFinancialException)live.error.get(20,TimeUnit.SECONDS)).code);financialError(CloudFinancialException.Code.FORBIDDEN,(RepositoryCallback<Settlement> cb)->a.settlements.saveSettlement(a.uid,payment(group,1),callbacks,cb));}
 }
}
