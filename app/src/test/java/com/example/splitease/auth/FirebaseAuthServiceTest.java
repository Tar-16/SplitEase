package com.example.splitease.auth;

import com.example.splitease.model.User;
import com.example.splitease.repository.*;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class FirebaseAuthServiceTest {
    private final ExecutorService callbacks=Executors.newSingleThreadExecutor(r->new Thread(r,"test-callbacks"));
    private Fake backend;private FirebaseAuthService auth;
    private static final User CLOUD=new User("firebase_uid_not_a_uuid","","cloud@example.com",null);
    private static final class Pending<T> {final Executor executor;final RepositoryCallback<T> callback;Pending(Executor e,RepositoryCallback<T> c){executor=e;callback=c;}void ok(T value){executor.execute(()->callback.onSuccess(value));}void fail(AuthException.Code code){executor.execute(()->callback.onError(new AuthException(code)));}}
    private static final class Fake implements FirebaseAuthBackend {
        volatile User user;volatile char[] ownedPassword;volatile String email;
        final BlockingQueue<Pending<User>> login=new LinkedBlockingQueue<>(),register=new LinkedBlockingQueue<>(),validate=new LinkedBlockingQueue<>();
        final BlockingQueue<Pending<Boolean>> reset=new LinkedBlockingQueue<>();final List<Runnable> listeners=new CopyOnWriteArrayList<>();
        public User current(){return user;}public ObservableQuery.Subscription observe(Runnable listener){listeners.add(listener);return ()->listeners.remove(listener);}
        public void register(String e,char[] p,Executor x,RepositoryCallback<User> c){email=e;ownedPassword=p;register.add(new Pending<>(x,c));}
        public void login(String e,char[] p,Executor x,RepositoryCallback<User> c){email=e;ownedPassword=p;login.add(new Pending<>(x,c));}
        public void validate(Executor x,RepositoryCallback<User> c){validate.add(new Pending<>(x,c));}
        public void reset(String e,Executor x,RepositoryCallback<Boolean> c){email=e;reset.add(new Pending<>(x,c));}
        void external(User next){user=next;for(Runnable listener:listeners)listener.run();}
        public void signOut(){external(null);}
    }
    private interface Call<T>{void run(RepositoryCallback<T> callback);}
    private <T>CompletableFuture<T> call(Call<T> call){CompletableFuture<T> result=new CompletableFuture<>();call.run(new RepositoryCallback<T>(){public void onSuccess(T value){assertEquals("test-callbacks",Thread.currentThread().getName());result.complete(value);}public void onError(Exception error){result.completeExceptionally(error);}});return result;}
    private <T>Pending<T> pending(BlockingQueue<Pending<T>> queue)throws Exception{Pending<T> value=queue.poll(3,TimeUnit.SECONDS);assertNotNull(value);return value;}
    private SessionState state(SessionState.Status status)throws Exception{CompletableFuture<SessionState> result=new CompletableFuture<>();ObservableQuery.Subscription subscription=auth.sessions().subscribe(callbacks,new ObservableQuery.Observer<SessionState>(){public void onChanged(SessionState value){if(value.status==status)result.complete(value);}public void onError(Exception error){result.completeExceptionally(error);}});try{return result.get(3,TimeUnit.SECONDS);}finally{subscription.close();}}
    private void error(AuthException.Code code,CompletableFuture<?> future)throws Exception{try{future.get(3,TimeUnit.SECONDS);fail("Expected "+code);}catch(ExecutionException failure){assertTrue(failure.getCause() instanceof AuthException);assertEquals(code,((AuthException)failure.getCause()).code);}}
    @Before public void setup()throws Exception{backend=new Fake();auth=new FirebaseAuthService(backend);state(SessionState.Status.SIGNED_OUT);}
    @After public void close(){auth.close();callbacks.shutdown();}
    private void login()throws Exception{CompletableFuture<User> result=call((RepositoryCallback<User> c)->auth.login("CLOUD@example.com","Strong1!".toCharArray(),callbacks,c));Pending<User> request=pending(backend.login);backend.external(CLOUD);request.ok(CLOUD);assertEquals(CLOUD.id,result.get(3,TimeUnit.SECONDS).id);}
    @Test public void validatesSignupAndRetainsOpaqueCloudUidWithoutLocalPersistence()throws Exception{
        error(AuthException.Code.USERNAME_REQUIRED,call((RepositoryCallback<User> c)->auth.register("","valid@example.com","Strong1!".toCharArray(),callbacks,c)));
        error(AuthException.Code.INVALID_EMAIL,call((RepositoryCallback<User> c)->auth.register("user","bad","Strong1!".toCharArray(),callbacks,c)));
        error(AuthException.Code.WEAK_PASSWORD,call((RepositoryCallback<User> c)->auth.register("user","valid@example.com","weak".toCharArray(),callbacks,c)));
        CompletableFuture<User> result=call((RepositoryCallback<User> c)->auth.register("Some User"," CLOUD@EXAMPLE.COM ","Strong1!".toCharArray(),callbacks,c));Pending<User> request=pending(backend.register);
        assertEquals("cloud@example.com",backend.email);backend.external(CLOUD);request.ok(CLOUD);assertEquals(CLOUD.id,result.get(3,TimeUnit.SECONDS).id);assertEquals(SessionState.Backend.FIREBASE,state(SessionState.Status.SIGNED_IN).backend);for(char value:backend.ownedPassword)assertEquals('\0',value);
    }
    @Test public void wrongPasswordDuplicateEmailAndResetAreSafe()throws Exception{
        CompletableFuture<User> result=call((RepositoryCallback<User> c)->auth.login(CLOUD.email,"wrong".toCharArray(),callbacks,c));pending(backend.login).fail(AuthException.Code.INVALID_CREDENTIALS);error(AuthException.Code.INVALID_CREDENTIALS,result);
        result=call((RepositoryCallback<User> c)->auth.register("user",CLOUD.email,"Strong1!".toCharArray(),callbacks,c));pending(backend.register).fail(AuthException.Code.DUPLICATE_EMAIL);error(AuthException.Code.DUPLICATE_EMAIL,result);
        error(AuthException.Code.INVALID_EMAIL,call((RepositoryCallback<Boolean> c)->auth.resetPassword("bad",callbacks,c)));
        CompletableFuture<Boolean> reset=call((RepositoryCallback<Boolean> c)->auth.resetPassword(" CLOUD@EXAMPLE.COM ",callbacks,c));pending(backend.reset).ok(true);assertTrue(reset.get(3,TimeUnit.SECONDS));assertEquals("cloud@example.com",backend.email);assertNull(backend.current());
    }
    @Test public void logoutDrainsLateSignInAndBlocksOverlappingMutations()throws Exception{
        CompletableFuture<User> result=call((RepositoryCallback<User> c)->auth.login(CLOUD.email,"Strong1!".toCharArray(),callbacks,c));Pending<User> request=pending(backend.login);
        assertTrue(call((RepositoryCallback<Boolean> c)->auth.logout(callbacks,c)).get(3,TimeUnit.SECONDS));
        assertNull(state(SessionState.Status.RESTORING).user);
        error(AuthException.Code.BUSY,call((RepositoryCallback<User> c)->auth.login(CLOUD.email,"Strong1!".toCharArray(),callbacks,c)));
        backend.external(CLOUD);request.ok(CLOUD);error(AuthException.Code.CANCELLED,result);assertNull(backend.current());state(SessionState.Status.SIGNED_OUT);
    }
    @Test public void cachedSessionIsValidatedAndInvalidOrOfflineStatesStayHidden()throws Exception{
        backend.external(CLOUD);Pending<User> validation=pending(backend.validate);assertNull(state(SessionState.Status.RESTORING).user);validation.fail(AuthException.Code.NETWORK);assertNull(state(SessionState.Status.UNAVAILABLE).user);assertNotNull(backend.current());
        auth.restore();pending(backend.validate).fail(AuthException.Code.SESSION_EXPIRED);state(SessionState.Status.SIGNED_OUT);assertNull(backend.current());
    }
    @Test public void sdkStateChangesAndServiceRecreationRestoreIdentity()throws Exception{
        login();auth.close();auth=new FirebaseAuthService(backend);pending(backend.validate).ok(CLOUD);assertEquals(CLOUD.id,state(SessionState.Status.SIGNED_IN).user.id);
        backend.external(null);state(SessionState.Status.SIGNED_OUT);
    }
    @Test public void closedOwnerCancelsLateAuthentication()throws Exception{
        CompletableFuture<User> result=call((RepositoryCallback<User> c)->auth.login(CLOUD.email,"Strong1!".toCharArray(),callbacks,c));Pending<User> request=pending(backend.login);auth.close();backend.external(CLOUD);request.ok(CLOUD);error(AuthException.Code.CANCELLED,result);assertNull(backend.current());
    }
    @Test public void cancelledObserversDropQueuedCallbacksAndUpiIsUnsupported()throws Exception{
        List<Runnable> queued=new CopyOnWriteArrayList<>();AtomicInteger delivered=new AtomicInteger();ObservableQuery.Subscription subscription=auth.sessions().subscribe(queued::add,new ObservableQuery.Observer<SessionState>(){public void onChanged(SessionState value){delivered.incrementAndGet();}public void onError(Exception error){fail();}});
        error(AuthException.Code.UNSUPPORTED,call((RepositoryCallback<User> c)->auth.updateUpi("unused@upi",callbacks,c)));subscription.close();for(Runnable callback:queued)callback.run();assertEquals(0,delivered.get());
    }
    @Test public void logoutAlsoDrainsAnUncancellableSessionValidation()throws Exception{
        backend.external(CLOUD);Pending<User> validation=pending(backend.validate);
        assertTrue(call((RepositoryCallback<Boolean> c)->auth.logout(callbacks,c)).get(3,TimeUnit.SECONDS));
        backend.external(CLOUD);validation.ok(CLOUD);state(SessionState.Status.SIGNED_OUT);
        // Serialize a later request after validation completes, not merely the earlier signed-out event.
        error(AuthException.Code.INVALID_EMAIL,call((RepositoryCallback<User> c)->auth.login("bad",new char[0],callbacks,c)));assertNull(backend.current());
    }
    @Test public void successQueuedBeforeLogoutCannotDeliverAnAuthenticatedResultAfterIt()throws Exception{
        BlockingQueue<Runnable> queued=new LinkedBlockingQueue<>();CompletableFuture<User> result=new CompletableFuture<>();
        auth.login(CLOUD.email,"Strong1!".toCharArray(),queued::add,new RepositoryCallback<User>(){public void onSuccess(User user){result.complete(user);}public void onError(Exception error){result.completeExceptionally(error);}});
        Pending<User> request=pending(backend.login);backend.external(CLOUD);request.ok(CLOUD);state(SessionState.Status.SIGNED_IN);
        call((RepositoryCallback<Boolean> c)->auth.logout(callbacks,c)).get(3,TimeUnit.SECONDS);queued.poll(3,TimeUnit.SECONDS).run();error(AuthException.Code.CANCELLED,result);
    }
    @Test public void repeatedSignedOutEventsCannotCancelPasswordReset()throws Exception{
        CompletableFuture<Boolean> result=call((RepositoryCallback<Boolean> c)->auth.resetPassword(CLOUD.email,callbacks,c));
        Pending<Boolean> reset=pending(backend.reset);backend.external(null);reset.ok(true);
        assertTrue(result.get(3,TimeUnit.SECONDS));assertNull(backend.current());state(SessionState.Status.SIGNED_OUT);
    }
    @Test public void mapsSdkErrorsWithoutRemoteMessages(){
        assertEquals(AuthException.Code.INVALID_CREDENTIALS,FirebaseAuthErrors.code("ERROR_INVALID_LOGIN_CREDENTIALS"));assertEquals(AuthException.Code.SESSION_EXPIRED,FirebaseAuthErrors.code("ERROR_USER_DISABLED"));assertEquals(AuthException.Code.RATE_LIMITED,FirebaseAuthErrors.code("ERROR_TOO_MANY_REQUESTS"));assertEquals(AuthException.Code.AUTH_UNAVAILABLE,FirebaseAuthErrors.code("ERROR_OPERATION_NOT_ALLOWED"));
    }
}
