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
import org.junit.*;
import org.junit.runner.RunWith;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static com.example.splitease.firebase.AuthEmulatorSupport.*;

/** Real SDK, emulator-enforced candidate rules. Never configures the default/live FirebaseApp. */
@RunWith(AndroidJUnit4.class)
public class FirestoreProfilesEmulatorTest {
 private static final Map<String,Scope> scopes=new HashMap<>();
 private static final ExecutorService callbacks=Executors.newSingleThreadExecutor();
 private static final class Scope {
  final FirebaseAuth auth;final FirebaseFirestore db;final FirestoreProfileRepository profiles;
  Scope(String name){FirebaseOptions options=new FirebaseOptions.Builder().setProjectId(PROJECT).setApplicationId("1:1234567890:android:0000000000000000").setApiKey("demo-api-key").build();FirebaseApp app=FirebaseApp.initializeApp(ApplicationProvider.getApplicationContext(),options,"ProfilesTest"+name);auth=FirebaseAuth.getInstance(app);auth.useEmulator("127.0.0.1",9099);db=FirebaseFirestore.getInstance(app);db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build());db.useEmulator("127.0.0.1",8080);profiles=new FirestoreProfileRepository(db,auth);}
 }
 private Scope a,b,guest;
 private static <T>T task(Task<T> task)throws Exception{return Tasks.await(task,25,TimeUnit.SECONDS);}
 private Scope scope(String name)throws Exception{Scope value=scopes.get(name);if(value==null){value=new Scope(name);scopes.put(name,value);}if(!name.equals("Guest")){String email="profiles-"+name.toLowerCase(Locale.ROOT)+"@example.test";try{task(value.auth.signInWithEmailAndPassword(email,PASSWORD));}catch(ExecutionException missing){task(value.auth.createUserWithEmailAndPassword(email,PASSWORD));}}else value.auth.signOut();return value;}
 @Before public void setup()throws Exception{
  Assume.assumeTrue("Explicit demo Auth + Firestore emulators required","true".equals(InstrumentationRegistry.getArguments().getString("profilesEmulator")));requireEmulator();
  // This demo-only test database contains fixtures, never application/production data.
  HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:8080/emulator/v1/projects/"+PROJECT+"/databases/(default)/documents").openConnection();connection.setRequestMethod("DELETE");connection.setConnectTimeout(5000);connection.setReadTimeout(5000);try{assertEquals(200,connection.getResponseCode());}finally{connection.disconnect();}
  a=scope("A");b=scope("B");guest=scope("Guest");
 }
 private CloudProfile create(Scope scope,String username)throws Exception{return await((RepositoryCallback<CloudProfile> cb)->scope.profiles.createOwnProfile(username,callbacks,cb));}
 private void denied(Task<?> operation)throws Exception{try{task(operation);fail("Rules must deny operation");}catch(ExecutionException failure){assertTrue(failure.getCause() instanceof FirebaseFirestoreException);assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED,((FirebaseFirestoreException)failure.getCause()).getCode());}}
 private DocumentReference own(Scope scope){return scope.db.collection("profiles").document(scope.auth.getUid());}
 @Test public void profilesPersistAndExactLookupExposesOnlyPublicIdentity()throws Exception{
  CloudProfile p=create(a,"  Priya_One  ");assertEquals("priya_one",p.normalizedUsername);assertEquals("Priya_One",p.displayUsername);assertEquals(a.auth.getUid(),p.uid);assertEquals(a.auth.getCurrentUser().getEmail().toLowerCase(Locale.ROOT),p.email);assertTrue(p.createdAtMillis>0);assertEquals(p.createdAtMillis,p.updatedAtMillis);
  CloudProfile retry=create(a,"DifferentName");assertEquals(p.normalizedUsername,retry.normalizedUsername);assertFalse(task(a.db.collection("usernames").document("differentname").get(Source.SERVER)).exists());
  MemberIdentity found=await((RepositoryCallback<MemberIdentity> cb)->b.profiles.findByUsername(" PRIYA_ONE ",callbacks,cb));assertEquals(p.uid,found.id);assertEquals(p.displayUsername,found.username);
  DocumentSnapshot publicData=task(b.db.collection("usernames").document("priya_one").get(Source.SERVER));assertEquals(new HashSet<>(Arrays.asList("uid","displayUsername")),publicData.getData().keySet());
  DocumentSnapshot uidData=task(b.db.collection("publicIdentities").document(p.uid).get(Source.SERVER));assertEquals(new HashSet<>(Arrays.asList("uid","normalizedUsername","displayUsername")),uidData.getData().keySet());
  FirestoreProfileRepository reopened=new FirestoreProfileRepository(a.db,a.auth);assertEquals(p.uid,await((RepositoryCallback<CloudProfile> cb)->reopened.getOwnProfile(callbacks,cb)).uid);
 }
 @Test public void concurrentReservationsHaveExactlyOneWinner()throws Exception{
  CompletableFuture<CloudProfile> first=new CompletableFuture<>(),second=new CompletableFuture<>();
  a.profiles.createOwnProfile("Shared_Name",callbacks,future(first));b.profiles.createOwnProfile("sHaReD_nAmE",callbacks,future(second));int success=0,duplicates=0;
  for(CompletableFuture<CloudProfile> value:Arrays.asList(first,second))try{value.get(30,TimeUnit.SECONDS);success++;}catch(ExecutionException error){assertEquals(AuthException.Code.DUPLICATE_USERNAME,((AuthException)error.getCause()).code);duplicates++;}
  assertEquals(1,success);assertEquals(1,duplicates);
  Scope loser=first.isCompletedExceptionally()?a:b;error(AuthException.Code.PROFILE_REQUIRED,(RepositoryCallback<CloudProfile> cb)->loser.profiles.getOwnProfile(callbacks,cb));CloudProfile recovered=create(loser,"Another_Name");assertEquals(loser.auth.getUid(),recovered.uid);
 }
 private static <T>RepositoryCallback<T> future(CompletableFuture<T> value){return new RepositoryCallback<T>(){public void onSuccess(T result){value.complete(result);}public void onError(Exception error){value.completeExceptionally(error);}};}
 @Test public void rulesDenyUnauthorizedReadsWritesListsAndUnimplementedCollections()throws Exception{
  CloudProfile p=create(a,"Owner_A");create(b,"Owner_B");
  denied(b.db.collection("profiles").document(p.uid).get(Source.SERVER));denied(b.db.collection("profiles").document(p.uid).update("upiId","stolen@upi","updatedAt",FieldValue.serverTimestamp()));
  denied(guest.db.collection("usernames").document("owner_a").get(Source.SERVER));denied(guest.db.collection("profiles").document(p.uid).get(Source.SERVER));
  denied(guest.db.collection("publicIdentities").document(p.uid).get(Source.SERVER));denied(a.db.collection("publicIdentities").get(Source.SERVER));denied(b.db.collection("publicIdentities").document(p.uid).update("displayUsername","Hijack"));
  denied(a.db.collection("profiles").get(Source.SERVER));denied(a.db.collection("usernames").get(Source.SERVER));denied(a.db.collection("groups").document("forbidden").set(Collections.singletonMap("name","test")));
  denied(b.db.collection("usernames").document("owner_a").set(registry(b.auth.getUid(),"Owner_A")));
  denied(own(a).delete());denied(a.db.collection("usernames").document("owner_a").delete());
 }
 private Map<String,Object> registry(String uid,String display){Map<String,Object> value=new HashMap<>();value.put("uid",uid);value.put("displayUsername",display);return value;}
 private Map<String,Object> profile(Scope scope,String display){Map<String,Object> value=new HashMap<>();value.put("uid",scope.auth.getUid());value.put("normalizedUsername",display.toLowerCase(Locale.ROOT));value.put("displayUsername",display);value.put("email",scope.auth.getCurrentUser().getEmail().toLowerCase(Locale.ROOT));value.put("upiId",null);value.put("createdAt",FieldValue.serverTimestamp());value.put("updatedAt",FieldValue.serverTimestamp());return value;}
 @Test public void rulesRequireAtomicPairAndValidateIdentityMetadataAndUpdates()throws Exception{
  denied(own(a).set(profile(a,"Solo_Name")));denied(a.db.collection("usernames").document("solo_name").set(registry(a.auth.getUid(),"Solo_Name")));
  Map<String,Object> invalid=profile(a,"Wrong_Email");invalid.put("email","other@example.test");WriteBatch batch=a.db.batch();batch.set(own(a),invalid);batch.set(a.db.collection("usernames").document("wrong_email"),registry(a.auth.getUid(),"Wrong_Email"));denied(batch.commit());
  CloudProfile p=create(a,"Immutable_A");
  for(String field:Arrays.asList("uid","normalizedUsername","email","createdAt","unexpected"))denied(own(a).update(field,field.equals("createdAt")?new Timestamp(1,0):"changed","updatedAt",FieldValue.serverTimestamp()));
  denied(a.db.collection("usernames").document("immutable_a").update("email","private@example.test"));
  denied(own(a).update("upiId","invalid","updatedAt",FieldValue.serverTimestamp()));denied(own(a).update("updatedAt",new Timestamp(1,0)));
  denied(own(a).update("displayUsername","IMMUTABLE_A","updatedAt",FieldValue.serverTimestamp())); // registry must change atomically too
  CloudProfile updated=await((RepositoryCallback<CloudProfile> cb)->a.profiles.updateOwnProfile("IMMUTABLE_A","priya@upi",callbacks,cb));assertEquals(p.createdAtMillis,updated.createdAtMillis);assertTrue(updated.updatedAtMillis>=p.updatedAtMillis);assertEquals("priya@upi",updated.upiId);
  error(AuthException.Code.INVALID_USERNAME,(RepositoryCallback<CloudProfile> cb)->a.profiles.updateOwnProfile("Different_Name",null,callbacks,cb));
 }
 @Test public void observationsAndAccountSwitchingNeverDeliverAnotherOwnersProfile()throws Exception{
  create(a,"Observer_A");create(b,"Observer_B");BlockingQueue<CloudProfile> values=new LinkedBlockingQueue<>();CompletableFuture<Exception> error=new CompletableFuture<>();
  ObservableQuery.Subscription subscription=a.profiles.observeOwnProfile().subscribe(callbacks,new ObservableQuery.Observer<CloudProfile>(){public void onChanged(CloudProfile p){values.add(p);}public void onError(Exception failure){error.complete(failure);}});
  try{assertEquals(a.auth.getUid(),values.poll(15,TimeUnit.SECONDS).uid);await((RepositoryCallback<CloudProfile> cb)->a.profiles.updateOwnProfile("Observer_A","observer@upi",callbacks,cb));CloudProfile changed;do{changed=values.poll(15,TimeUnit.SECONDS);assertNotNull(changed);}while(changed.upiId==null);assertEquals("observer@upi",changed.upiId);
   a.auth.signOut();assertEquals(AuthException.Code.CANCELLED,((AuthException)error.get(15,TimeUnit.SECONDS)).code);assertTrue(values.isEmpty());
   error(AuthException.Code.SIGNED_OUT,(RepositoryCallback<CloudProfile> cb)->a.profiles.getOwnProfile(callbacks,cb));
   task(a.auth.signInWithEmailAndPassword("profiles-b@example.test",PASSWORD));
   CloudProfile switched=await((RepositoryCallback<CloudProfile> cb)->a.profiles.getOwnProfile(callbacks,cb));assertEquals(b.auth.getUid(),switched.uid);assertEquals("Observer_B",switched.displayUsername);assertTrue(values.isEmpty());
  }finally{subscription.close();}
 }
 @Test public void signupConflictAndPendingSessionRecoverOnLoginAndRestore()throws Exception{
  create(b,"Taken_Name");a.auth.signOut();FirebaseAuthBackend backend=new ProfiledFirebaseAuthBackend(new FirebaseSdkAuthBackend(a.auth),a.profiles);
  try(FirebaseAuthService service=new FirebaseAuthService(backend)){
   state(service,SessionState.Status.SIGNED_OUT);
   // Existing controlled Auth account has no profile: login must not report application readiness.
   error(AuthException.Code.PROFILE_REQUIRED,(RepositoryCallback<User> cb)->service.login("profiles-a@example.test",PASSWORD.toCharArray(),callbacks,cb));state(service,SessionState.Status.PROFILE_REQUIRED);
   error(AuthException.Code.DUPLICATE_USERNAME,(RepositoryCallback<User> cb)->service.completeProfile("Taken_Name",callbacks,cb));assertNotNull(a.auth.getCurrentUser());state(service,SessionState.Status.PROFILE_REQUIRED);
   User ready=await((RepositoryCallback<User> cb)->service.completeProfile("Recovered_A",callbacks,cb));assertEquals("Recovered_A",ready.username);state(service,SessionState.Status.SIGNED_IN);
   User upi=await((RepositoryCallback<User> cb)->service.updateUpi("recovered@upi",callbacks,cb));assertEquals("recovered@upi",upi.upiId);
  }
  try(FirebaseAuthService restored=new FirebaseAuthService(new ProfiledFirebaseAuthBackend(new FirebaseSdkAuthBackend(a.auth),a.profiles))){assertEquals("Recovered_A",state(restored,SessionState.Status.SIGNED_IN).user.username);await((RepositoryCallback<Boolean> cb)->restored.logout(callbacks,cb));}
 }
}
