package com.example.splitease.auth;

import com.example.splitease.model.User;
import com.example.splitease.repository.*;
import org.junit.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Tests session failure/cancellation boundaries without Android or a database. */
public class LocalAuthServiceTest {
    private final MemoryStore store=new MemoryStore();
    private final FakeUsers users=new FakeUsers();
    private LocalAuthService auth;
    private interface Call<T> { void start(RepositoryCallback<T> callback); }
    private <T> CompletableFuture<T> request(Call<T> call) {
        CompletableFuture<T> future=new CompletableFuture<>();
        call.start(new RepositoryCallback<T>() {
            @Override public void onSuccess(T value) { future.complete(value); }
            @Override public void onError(Exception error) { future.completeExceptionally(error); }
        }); return future;
    }
    private SessionState state() throws Exception {
        CompletableFuture<SessionState> future=new CompletableFuture<>();
        ObservableQuery.Subscription sub=auth.sessions().subscribe(Runnable::run,new ObservableQuery.Observer<SessionState>() {
            @Override public void onChanged(SessionState value) { if (value.status != SessionState.Status.RESTORING) future.complete(value); }
            @Override public void onError(Exception error) { future.completeExceptionally(error); }
        });
        try { return future.get(5,TimeUnit.SECONDS); } finally { sub.close(); }
    }
    @After public void teardown() { if (auth != null) auth.close(); }
    @Test public void missingAndMalformedIdsAreClearedButStorageFailuresStayGated() throws Exception {
        store.id="not-a-uuid"; auth=new LocalAuthService(users,store);
        assertEquals(SessionState.Status.SIGNED_OUT,state().status); assertNull(store.id); auth.close();
        store.id=UUID.randomUUID().toString(); auth=new LocalAuthService(users,store);
        assertEquals(SessionState.Status.SIGNED_OUT,state().status); assertNull(store.id); auth.close();
        store.fail=true; auth=new LocalAuthService(users,store);
        assertEquals(SessionState.Status.UNAVAILABLE,state().status);
    }
    @Test public void logoutCancelsInFlightRegistrationAndCannotBeUndoneByItsCallback() throws Exception {
        auth=new LocalAuthService(users,store); assertEquals(SessionState.Status.SIGNED_OUT,state().status);
        CompletableFuture<User> registration=request(c -> auth.register("alice","alice@example.com","Strong1!".toCharArray(),Runnable::run,c));
        assertTrue(users.pending.await(5,TimeUnit.SECONDS));
        assertTrue(this.<Boolean>request(c -> auth.logout(Runnable::run,c)).get(5,TimeUnit.SECONDS));
        users.completeRegistration();
        ExecutionException error=assertThrows(ExecutionException.class, () -> registration.get(5,TimeUnit.SECONDS));
        assertEquals(AuthException.Code.CANCELLED,((AuthException)error.getCause()).code);
        assertEquals(SessionState.Status.SIGNED_OUT,state().status); assertNull(store.id);
    }
    @Test public void failedSessionCommitNeverPublishesAuthenticatedUser() throws Exception {
        auth=new LocalAuthService(users,store); state();
        CompletableFuture<User> registration=request(c -> auth.register("alice","alice@example.com","Strong1!".toCharArray(),Runnable::run,c));
        assertTrue(users.pending.await(5,TimeUnit.SECONDS)); store.fail=true; users.completeRegistration();
        ExecutionException error=assertThrows(ExecutionException.class, () -> registration.get(5,TimeUnit.SECONDS));
        assertEquals(AuthException.Code.STORAGE,((AuthException)error.getCause()).code);
        assertEquals(SessionState.Status.SIGNED_OUT,state().status);
    }
    @Test public void concurrentSubmissionIsRejectedAndOnlyOneAccountIsCreated() throws Exception {
        auth=new LocalAuthService(users,store); state();
        CompletableFuture<User> first=request(c -> auth.register("alice","alice@example.com","Strong1!".toCharArray(),Runnable::run,c));
        assertTrue(users.pending.await(5,TimeUnit.SECONDS));
        CompletableFuture<User> second=request(c -> auth.register("other","other@example.com","Strong1!".toCharArray(),Runnable::run,c));
        ExecutionException error=assertThrows(ExecutionException.class, () -> second.get(5,TimeUnit.SECONDS));
        assertEquals(AuthException.Code.BUSY,((AuthException)error.getCause()).code);
        users.completeRegistration(); User user=first.get(5,TimeUnit.SECONDS);
        assertEquals(user.id,store.id); assertEquals(user.id,state().user.id); assertEquals(1,users.created);
    }
    private static final class MemoryStore implements SessionStore {
        volatile String id; volatile boolean fail;
        @Override public String readUserId() throws IOException { if (fail) throw new IOException(); return id; }
        @Override public void writeUserId(String value) throws IOException { if (fail) throw new IOException(); id=value; }
        @Override public void clear() throws IOException { if (fail) throw new IOException(); id=null; }
    }
    private static final class FakeUsers implements UserRepository {
        final CountDownLatch pending=new CountDownLatch(1);
        User pendingUser; RepositoryCallback<User> completion; Executor executor; int created;
        void completeRegistration() { executor.execute(() -> completion.onSuccess(pendingUser)); }
        @Override public void createUser(User user,char[] password,Executor executor,RepositoryCallback<User> callback) { created++; pendingUser=user; this.executor=executor; completion=callback; pending.countDown(); }
        @Override public void getUser(String id,Executor e,RepositoryCallback<User> c) { e.execute(() -> c.onError(new NoSuchElementException())); }
        @Override public void updateUser(User u,Executor e,RepositoryCallback<User> c) { throw new UnsupportedOperationException(); }
        @Override public void deleteUser(String id,Executor e,RepositoryCallback<Boolean> c) { throw new UnsupportedOperationException(); }
        @Override public void findUserByEmail(String email,Executor e,RepositoryCallback<User> c) { throw new UnsupportedOperationException(); }
        @Override public void verifyPassword(String id,char[] password,Executor e,RepositoryCallback<Boolean> c) { throw new UnsupportedOperationException(); }
    }
}
