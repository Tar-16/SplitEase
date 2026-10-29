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
public class FirestoreGroupsEmulatorTest {
 private static final Map<String,Client> clients=new HashMap<>();
 private final ExecutorService callbacks=Executors.newSingleThreadExecutor(r->new Thread(r,"group-test-callbacks"));
 private Client a,b,c;
 private static final class Client {
  final FirebaseAuth sdk;final FirebaseFirestore db;final FirestoreProfileRepository profiles;FirebaseAuthService auth;FirestoreGroupRepository groups;String uid;
  Client(String name){FirebaseOptions options=new FirebaseOptions.Builder().setProjectId(PROJECT).setApplicationId("1:1234567890:android:0000000000000000").setApiKey("demo-api-key").build();FirebaseApp app=FirebaseApp.initializeApp(ApplicationProvider.getApplicationContext(),options,"GroupTest"+name);sdk=FirebaseAuth.getInstance(app);sdk.useEmulator("127.0.0.1",9099);db=FirebaseFirestore.getInstance(app);db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build());db.useEmulator("127.0.0.1",8080);profiles=new FirestoreProfileRepository(db,sdk);}
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
  value.auth=new FirebaseAuthService(new ProfiledFirebaseAuthBackend(new FirebaseSdkAuthBackend(value.sdk),value.profiles));state(value.auth,SessionState.Status.SIGNED_IN);value.groups=new FirestoreGroupRepository(value.db,value.auth,value.profiles);state(value.auth,SessionState.Status.SIGNED_IN);return value;
 }
 @Before public void start()throws Exception{Assume.assumeTrue("Explicit group emulators required","true".equals(InstrumentationRegistry.getArguments().getString("groupsEmulator")));requireEmulator();admin("DELETE","/emulator/v1/projects/"+PROJECT+"/databases/(default)/documents",null);a=client("A");b=client("B");c=client("C");}
 @After public void stop(){for(Client value:clients.values()){if(value.groups!=null)value.groups.close();if(value.auth!=null)value.auth.close();}callbacks.shutdown();}
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
 @Test public void atomicCreationUsernameLookupAndTwoClientsReceiveRealtimeChanges()throws Exception{
  MemberIdentity found=await((RepositoryCallback<MemberIdentity> cb)->a.groups.findMemberByUsername(a.uid," GROUP_b ",callbacks,cb));assertEquals(b.uid,found.id);assertEquals("Group_B",found.username);
  try(Stream<List<GroupDetails>> homeA=new Stream<>(a.groups.observeGroupSummaries(a.uid));Stream<List<GroupDetails>> homeB=new Stream<>(b.groups.observeGroupSummaries(b.uid))){assertTrue(homeA.next().isEmpty());assertTrue(homeB.next().isEmpty());Group group=create(a,Group.Type.FLAT,b.uid);GroupDetails da=homeA.next().get(0),db=homeB.next().get(0);assertEquals(group.id,db.group.id);assertEquals(2,da.members.size());assertFalse(da.accountingAvailable);assertFalse(da.canLeave);assertTrue(da.balances.isEmpty());assertTrue(group.createdAt>0);
   try(Stream<GroupDetails> liveB=new Stream<>(b.groups.observeGroupDetails(b.uid,group.id))){liveB.next();await((RepositoryCallback<Boolean> cb)->a.groups.addMember(a.uid,group.id,c.uid,1,callbacks,cb));assertEquals(3,liveB.next().members.size());}
  }
  Group trip=create(a,Group.Type.TRIP);assertEquals(Group.Type.TRIP,detail(a,trip.id).group.type);
 }
 @Test public void duplicateAndConcurrentAddsRetainAllMembersAndServerRevision()throws Exception{
  Group group=create(a,Group.Type.FLAT);CompletableFuture<Boolean> first=new CompletableFuture<>(),second=new CompletableFuture<>();a.groups.addMember(a.uid,group.id,b.uid,1,callbacks,future(first));a.groups.addMember(a.uid,group.id,c.uid,2,callbacks,future(second));assertTrue(first.get(30,TimeUnit.SECONDS));assertTrue(second.get(30,TimeUnit.SECONDS));assertEquals(3,detail(a,group.id).members.size());assertEquals(Long.valueOf(3),task(a.db.collection("groups").document(group.id).get(Source.SERVER)).getLong("revision"));groupError(CloudGroupException.Code.DUPLICATE,(RepositoryCallback<Boolean> cb)->a.groups.addMember(a.uid,group.id,b.uid,0,callbacks,cb));
  Group secondGroup=create(a,Group.Type.TRIP);first=new CompletableFuture<>();second=new CompletableFuture<>();a.groups.addMember(a.uid,secondGroup.id,b.uid,0,callbacks,future(first));a.groups.addMember(a.uid,secondGroup.id,b.uid,0,callbacks,future(second));int wins=0,losses=0;for(CompletableFuture<Boolean> result:Arrays.asList(first,second))try{assertTrue(result.get(30,TimeUnit.SECONDS));wins++;}catch(ExecutionException failure){assertEquals(CloudGroupException.Code.DUPLICATE,((CloudGroupException)failure.getCause()).code);losses++;}assertEquals(1,wins);assertEquals(1,losses);assertEquals(2,detail(a,secondGroup.id).members.size());
 }
 private static <T>RepositoryCallback<T> future(CompletableFuture<T> result){return new RepositoryCallback<T>(){public void onSuccess(T value){result.complete(value);}public void onError(Exception error){result.completeExceptionally(error);}};}
 @Test public void transferIsAtomicCreatorImmutableAndLeavingAlwaysUnavailable()throws Exception{
  Group group=create(a,Group.Type.TRIP,b.uid);groupError(CloudGroupException.Code.FORBIDDEN,(RepositoryCallback<Boolean> cb)->b.groups.addMember(b.uid,group.id,c.uid,0,callbacks,cb));groupError(CloudGroupException.Code.INVALID,(RepositoryCallback<Boolean> cb)->a.groups.transferAdmin(a.uid,group.id,c.uid,callbacks,cb));
  await((RepositoryCallback<Boolean> cb)->a.groups.transferAdmin(a.uid,group.id,b.uid,callbacks,cb));GroupDetails value=detail(b,group.id);assertEquals(b.uid,value.group.adminId);assertEquals(a.uid,value.group.creatorId);assertEquals(2,value.members.size());groupError(CloudGroupException.Code.FORBIDDEN,(RepositoryCallback<Boolean> cb)->a.groups.addMember(a.uid,group.id,c.uid,0,callbacks,cb));await((RepositoryCallback<Boolean> cb)->b.groups.addMember(b.uid,group.id,c.uid,0,callbacks,cb));
  groupError(CloudGroupException.Code.UNSUPPORTED,(RepositoryCallback<Boolean> cb)->a.groups.leaveGroup(a.uid,group.id,0,callbacks,cb));try(Stream<Map<String,Long>> balances=new Stream<>(a.groups.observeBalances(group.id))){assertEquals(CloudGroupException.Code.UNSUPPORTED,((CloudGroupException)balances.error.get(5,TimeUnit.SECONDS)).code);}
  assertFalse(detail(a,group.id).canLeave);
 }
 @Test public void maliciousDirectWritesCannotAssignPrivilegesEraseHistoryOrAccessPrivateGroups()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid);DocumentReference ref=a.db.collection("groups").document(group.id);
  denied(c.db.collection("groups").document(group.id).get(Source.SERVER));denied(c.db.collection("groups").get(Source.SERVER));denied(b.db.collection("profiles").document(a.uid).get(Source.SERVER));denied(a.db.collection("groups").get(Source.SERVER));
  denied(b.db.collection("groups").document(group.id).update("adminId",b.uid,"updatedAt",FieldValue.serverTimestamp(),"revision",2L));denied(c.db.collection("groups").document(group.id).update("memberIds",FieldValue.arrayUnion(c.uid),"updatedAt",FieldValue.serverTimestamp(),"revision",2L));
  for(Map<String,Object> change:Arrays.asList(Collections.<String,Object>singletonMap("creatorId",b.uid),Collections.<String,Object>singletonMap("adminId",c.uid),Collections.<String,Object>singletonMap("memberIds",Collections.singletonList(a.uid)),Collections.<String,Object>singletonMap("members",Collections.emptyMap()),Collections.<String,Object>singletonMap("balance",0L),Collections.<String,Object>singletonMap("createdAt",new Timestamp(1,0)))){Map<String,Object> edit=new HashMap<>(change);edit.put("updatedAt",FieldValue.serverTimestamp());edit.put("revision",2L);denied(ref.update(edit));}
  denied(b.db.collection("groups").document(group.id).collection("members").document(b.uid).set(Collections.singletonMap("role","ADMIN")));
  denied(ref.delete());denied(a.db.collection("groups").document(group.id).collection("expenses").document("blocked").set(Collections.singletonMap("amount",1L)));denied(a.db.collection("settlements").document("blocked").set(Collections.singletonMap("amount",1L)));denied(a.db.collection("recurringBills").document("blocked").set(Collections.singletonMap("amount",1L)));
  groupError(CloudGroupException.Code.FORBIDDEN,(RepositoryCallback<MemberIdentity> cb)->a.groups.findMemberByUsername(b.uid,"Group_B",callbacks,cb));assertEquals(2,detail(a,group.id).members.size());
 }
 @Test public void invalidInitialMembershipRollsBackAndEightMemberBoundaryIsVerified()throws Exception{
  Group invalid=new Group(UUID.randomUUID().toString(),"Invalid",Group.Type.FLAT,a.uid,0);groupError(CloudGroupException.Code.INVALID,(RepositoryCallback<Group> cb)->a.groups.createGroup(a.uid,invalid,Arrays.asList(b.uid,b.uid),callbacks,cb));assertFalse(task(a.db.collection("groups").document(invalid.id).get(Source.SERVER)).exists());
  groupError(CloudGroupException.Code.INVALID,(RepositoryCallback<Group> cb)->a.groups.createGroup(a.uid,invalid,Arrays.asList(b.uid,"unregistered-cloud-uid"),callbacks,cb));assertFalse(task(a.db.collection("groups").document(invalid.id).get(Source.SERVER)).exists());
  List<String> ids=new ArrayList<>(Arrays.asList(b.uid,c.uid));for(String name:Arrays.asList("D","E","F","G","H"))ids.add(client(name).uid);Group eight=create(a,Group.Type.TRIP,ids.toArray(new String[0]));assertEquals(8,detail(a,eight.id).members.size());Client ninth=client("I");await((RepositoryCallback<Boolean> cb)->a.groups.addMember(a.uid,eight.id,ninth.uid,0,callbacks,cb));assertEquals(9,detail(a,eight.id).members.size());
 }
 @Test public void historicalRejoinPreservesJoinAndDepartureMetadata()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid);String path="/v1/projects/"+PROJECT+"/databases/(default)/documents/groups/"+group.id;JSONObject stored=admin("GET",path,null);JSONObject fields=stored.getJSONObject("fields");JSONArray active=new JSONArray().put(new JSONObject().put("stringValue",a.uid));fields.getJSONObject("memberIds").put("arrayValue",new JSONObject().put("values",active));JSONObject member=fields.getJSONObject("members").getJSONObject("mapValue").getJSONObject("fields").getJSONObject(b.uid).getJSONObject("mapValue").getJSONObject("fields");String joined="2025-12-01T00:00:00Z";member.put("joinedAt",new JSONObject().put("timestampValue",joined));fields.put("createdAt",new JSONObject().put("timestampValue",joined));fields.getJSONObject("members").getJSONObject("mapValue").getJSONObject("fields").getJSONObject(a.uid).getJSONObject("mapValue").getJSONObject("fields").put("joinedAt",new JSONObject().put("timestampValue",joined));member.put("status",new JSONObject().put("stringValue","LEFT"));member.put("leftAt",new JSONObject().put("timestampValue","2026-01-01T00:00:00Z"));admin("PATCH",path,stored);
  // A trusted legacy fixture, not a client leave loophole: all client leave paths remain denied.
  denied(b.db.collection("groups").document(group.id).get(Source.SERVER));Map<String,Object> rewrite=new HashMap<>();rewrite.put("members."+b.uid+".joinedAt",FieldValue.serverTimestamp());rewrite.put("members."+b.uid+".status","ACTIVE");rewrite.put("memberIds",FieldValue.arrayUnion(b.uid));rewrite.put("updatedAt",FieldValue.serverTimestamp());rewrite.put("revision",2L);denied(a.db.collection("groups").document(group.id).update(rewrite));await((RepositoryCallback<Boolean> cb)->a.groups.addMember(a.uid,group.id,b.uid,0,callbacks,cb));try(Stream<List<GroupMember>> stream=new Stream<>(a.groups.observeMembers(group.id))){List<GroupMember> history=stream.next();GroupMember returned=null;for(GroupMember m:history)if(m.userId.equals(b.uid))returned=m;assertNotNull(returned);assertEquals(GroupMember.Status.ACTIVE,returned.status);assertEquals(Long.valueOf(1767225600000L),returned.leftAt);assertEquals(task(a.db.collection("groups").document(group.id).get(Source.SERVER)).getTimestamp("members."+b.uid+".joinedAt").toDate().getTime(),returned.joinedAt);}
  JSONObject after=admin("GET",path,null);assertEquals(joined,after.getJSONObject("fields").getJSONObject("members").getJSONObject("mapValue").getJSONObject("fields").getJSONObject(b.uid).getJSONObject("mapValue").getJSONObject("fields").getJSONObject("joinedAt").getString("timestampValue"));
 }
 @Test public void offlineFailureReconnectionAndCancellationPreserveAuthorization()throws Exception{
  Group group=create(a,Group.Type.FLAT,b.uid);try(Stream<GroupDetails> live=new Stream<>(b.groups.observeGroupDetails(b.uid,group.id))){live.next();task(b.db.disableNetwork());assertEquals(CloudGroupException.Code.UNAVAILABLE,((CloudGroupException)live.error.get(20,TimeUnit.SECONDS)).code);await((RepositoryCallback<Boolean> cb)->a.groups.addMember(a.uid,group.id,c.uid,0,callbacks,cb));task(b.db.enableNetwork());assertEquals(3,detail(b,group.id).members.size());}
  BlockingQueue<Runnable> queued=new LinkedBlockingQueue<>();List<GroupDetails> received=new ArrayList<>();ObservableQuery.Subscription subscription=a.groups.observeGroupDetails(a.uid,group.id).subscribe(queued::add,new ObservableQuery.Observer<GroupDetails>(){public void onChanged(GroupDetails value){received.add(value);}public void onError(Exception error){fail();}});Runnable callback=queued.poll(10,TimeUnit.SECONDS);assertNotNull(callback);subscription.close();callback.run();assertTrue(received.isEmpty());
 }
 @Test public void accountSwitchAndServiceRestorationNeverReturnOtherAccountsGroups()throws Exception{
  Group privateA=create(a,Group.Type.FLAT),shared=create(a,Group.Type.TRIP,b.uid);try(Stream<GroupDetails> old=new Stream<>(a.groups.observeGroupDetails(a.uid,privateA.id))){old.next();task(a.sdk.signInWithEmailAndPassword("group-b@example.test",PASSWORD));awaitIdentity(a.auth,b.uid);assertEquals(CloudGroupException.Code.CANCELLED,((CloudGroupException)old.error.get(10,TimeUnit.SECONDS)).code);try(Stream<List<GroupDetails>> home=new Stream<>(a.groups.observeGroupSummaries(b.uid))){List<GroupDetails> list=home.next();assertEquals(1,list.size());assertEquals(shared.id,list.get(0).group.id);}}
  a.groups.close();a.auth.close();a.auth=new FirebaseAuthService(new ProfiledFirebaseAuthBackend(new FirebaseSdkAuthBackend(a.sdk),a.profiles));awaitIdentity(a.auth,b.uid);a.groups=new FirestoreGroupRepository(a.db,a.auth,a.profiles);state(a.auth,SessionState.Status.SIGNED_IN);assertEquals(shared.id,detail(newClientIdentity(a,b.uid),shared.id).group.id);
 }
 private Client newClientIdentity(Client client,String uid){client.uid=uid;return client;}
 private void awaitIdentity(AuthService auth,String uid)throws Exception{CompletableFuture<Boolean> ready=new CompletableFuture<>();ObservableQuery.Subscription sub=auth.sessions().subscribe(callbacks,new ObservableQuery.Observer<SessionState>(){public void onChanged(SessionState value){if(value.status==SessionState.Status.SIGNED_IN&&value.user.id.equals(uid))ready.complete(true);}public void onError(Exception error){ready.completeExceptionally(error);}});try{ready.get(20,TimeUnit.SECONDS);}finally{sub.close();}}
}
