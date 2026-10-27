package com.example.splitease.auth;

import com.example.splitease.domain.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Deterministic cross-service failures; separate from real SDK/emulator evidence. */
public class ProfiledFirebaseAuthBackendTest {
 private final ExecutorService callback=Executors.newSingleThreadExecutor();
 private final Identity identity=new Identity();private final Profiles profiles=new Profiles();private FirebaseAuthService service;
 private static final User USER=new User("opaque-cloud-uid","","cloud@example.test",null);
 private static final class Identity implements FirebaseAuthBackend{
  volatile User user;final List<Runnable> listeners=new CopyOnWriteArrayList<>();
  public User current(){return user;}public ObservableQuery.Subscription observe(Runnable changed){listeners.add(changed);return ()->listeners.remove(changed);}
  void changed(User next){user=next;for(Runnable l:listeners)l.run();}
  public void register(String email,char[] p,Executor e,RepositoryCallback<User> cb){changed(USER);e.execute(()->cb.onSuccess(USER));}
  public void login(String email,char[] p,Executor e,RepositoryCallback<User> cb){register(email,p,e,cb);}
  public void validate(Executor e,RepositoryCallback<User> cb){e.execute(()->cb.onSuccess(user));}
  public void reset(String email,Executor e,RepositoryCallback<Boolean> cb){e.execute(()->cb.onSuccess(true));}
  public void signOut(){changed(null);}
 }
 private static final class Profiles implements CloudProfileRepository{
  volatile CloudProfile stored;volatile boolean failBefore,failAfter,duplicate,hold,holdRead;final BlockingQueue<Runnable> held=new LinkedBlockingQueue<>();
  public void createOwnProfile(String username,Executor e,RepositoryCallback<CloudProfile> cb){Runnable work=()->{
   if(stored!=null){cb.onSuccess(stored);return;}if(duplicate){cb.onError(new AuthException(AuthException.Code.DUPLICATE_USERNAME));return;}if(failBefore){cb.onError(new AuthException(AuthException.Code.NETWORK));return;}
   stored=new CloudProfile(USER.id,CloudProfileValidation.normalized(username),CloudProfileValidation.display(username),USER.email,null,1,1);
   if(failAfter)cb.onError(new AuthException(AuthException.Code.NETWORK));else cb.onSuccess(stored);
  };if(hold)held.add(()->e.execute(work));else e.execute(work);}
  public void getOwnProfile(Executor e,RepositoryCallback<CloudProfile> cb){Runnable read=()->{if(stored==null)cb.onError(new AuthException(AuthException.Code.PROFILE_REQUIRED));else cb.onSuccess(stored);};if(holdRead)held.add(()->e.execute(read));else e.execute(read);}
  public void findByUsername(String u,Executor e,RepositoryCallback<MemberIdentity> cb){throw new UnsupportedOperationException();}
  public void updateOwnProfile(String u,String upi,Executor e,RepositoryCallback<CloudProfile> cb){e.execute(()->{stored=new CloudProfile(stored.uid,stored.normalizedUsername,u,stored.email,upi,stored.createdAtMillis,2);cb.onSuccess(stored);});}
  public ObservableQuery<CloudProfile> observeOwnProfile(){throw new UnsupportedOperationException();}
 }
 private interface Call<T>{void run(RepositoryCallback<T> cb);}
 private <T>CompletableFuture<T> call(Call<T> work){CompletableFuture<T> result=new CompletableFuture<>();work.run(new RepositoryCallback<T>(){public void onSuccess(T value){result.complete(value);}public void onError(Exception error){result.completeExceptionally(error);}});return result;}
 private void error(AuthException.Code code,CompletableFuture<?> value)throws Exception{try{value.get(3,TimeUnit.SECONDS);fail("Expected error");}catch(ExecutionException e){assertEquals(code,((AuthException)e.getCause()).code);}}
 private SessionState state(SessionState.Status status)throws Exception{CompletableFuture<SessionState> result=new CompletableFuture<>();ObservableQuery.Subscription sub=service.sessions().subscribe(callback,new ObservableQuery.Observer<SessionState>(){public void onChanged(SessionState s){if(s.status==status)result.complete(s);}public void onError(Exception e){result.completeExceptionally(e);}});try{return result.get(3,TimeUnit.SECONDS);}finally{sub.close();}}
 private void open(){service=new FirebaseAuthService(new ProfiledFirebaseAuthBackend(identity,profiles));}
 private CompletableFuture<User> signup(String name){return call((RepositoryCallback<User> cb)->service.register(name,USER.email,"Strong1!".toCharArray(),callback,cb));}
 @Before public void start()throws Exception{open();state(SessionState.Status.SIGNED_OUT);}
 @After public void close(){service.close();callback.shutdown();}
 @Test public void signupReadyOnlyAfterPairedProfileAndUidRemainsOpaque()throws Exception{User user=signup("  Priya_One  ").get(3,TimeUnit.SECONDS);assertEquals(USER.id,user.id);assertEquals("Priya_One",user.username);assertEquals("priya_one",profiles.stored.normalizedUsername);state(SessionState.Status.SIGNED_IN);}
 @Test public void invalidUsernameDoesNotCreateAuthIdentity()throws Exception{error(AuthException.Code.INVALID_USERNAME,signup("bad/name"));assertNull(identity.current());assertNull(profiles.stored);}
 @Test public void failureBeforeCommitSurvivesRestartAndCanCompleteWithoutNewAccount()throws Exception{
  profiles.failBefore=true;error(AuthException.Code.NETWORK,signup("Original_Name"));state(SessionState.Status.PROFILE_REQUIRED);assertNotNull(identity.current());assertNull(profiles.stored);
  service.close();open();state(SessionState.Status.PROFILE_REQUIRED);profiles.failBefore=false;User ready=call((RepositoryCallback<User> cb)->service.completeProfile("Recovered_Name",callback,cb)).get(3,TimeUnit.SECONDS);assertEquals(USER.id,ready.id);state(SessionState.Status.SIGNED_IN);
 }
 @Test public void ambiguousAcknowledgementRestoresCommittedOriginalInsteadOfDeletingAccount()throws Exception{
  profiles.failAfter=true;error(AuthException.Code.NETWORK,signup("Original_Name"));state(SessionState.Status.PROFILE_REQUIRED);assertNotNull(profiles.stored);assertNotNull(identity.current());
  User ready=call((RepositoryCallback<User> cb)->service.completeProfile("Other_Name",callback,cb)).get(3,TimeUnit.SECONDS);assertEquals("Original_Name",ready.username);assertEquals("original_name",profiles.stored.normalizedUsername);
  service.close();open();assertEquals(USER.id,state(SessionState.Status.SIGNED_IN).user.id);
 }
 @Test public void duplicateCanChooseAnotherUsernameAndProfileNeverBecomesReadyEarly()throws Exception{
  profiles.duplicate=true;error(AuthException.Code.DUPLICATE_USERNAME,signup("Taken_Name"));state(SessionState.Status.PROFILE_REQUIRED);assertNotNull(identity.current());profiles.duplicate=false;
  assertEquals("Available_Name",call((RepositoryCallback<User> cb)->service.completeProfile("Available_Name",callback,cb)).get(3,TimeUnit.SECONDS).username);
 }
 @Test public void logoutSuppressesLateProfileCompletion()throws Exception{
  profiles.hold=true;CompletableFuture<User> result=signup("Late_Name");Runnable finish=profiles.held.poll(3,TimeUnit.SECONDS);assertNotNull(finish);assertTrue(call((RepositoryCallback<Boolean> cb)->service.logout(callback,cb)).get(3,TimeUnit.SECONDS));finish.run();error(AuthException.Code.CANCELLED,result);state(SessionState.Status.SIGNED_OUT);assertNull(identity.current());
 }
 @Test public void externalSignoutDuringRestoredProfileReadNeverLeavesReadyIdentity()throws Exception{
  signup("Existing_Name").get(3,TimeUnit.SECONDS);service.close();profiles.holdRead=true;open();Runnable read=profiles.held.poll(3,TimeUnit.SECONDS);assertNotNull(read);identity.signOut();read.run();assertNull(state(SessionState.Status.SIGNED_OUT).user);
 }
 @Test public void normalizationIsBoundedAndUpiIsOptionalUnverifiedMetadata(){
  assertEquals("priya_one",CloudProfileValidation.normalized(" PRIYA_One "));assertEquals("cloud@example.test",AccountValidation.normalize(" CLOUD@EXAMPLE.TEST "));assertNull(CloudProfileValidation.upi(" "));assertEquals("priya@upi",CloudProfileValidation.upi(" priya@upi "));
  for(String bad:Arrays.asList("ab","../name","user name","İuser","a/b","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))try{CloudProfileValidation.normalized(bad);fail("Invalid registry key");}catch(IllegalArgumentException expected){/* bounded ASCII */}
 }
}
