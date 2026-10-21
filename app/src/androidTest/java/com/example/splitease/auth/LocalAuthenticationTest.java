package com.example.splitease.auth;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.splitease.data.*;
import com.example.splitease.model.User;
import com.example.splitease.repository.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalAuthenticationTest {
    private Context context;
    private SqliteRepositories users;
    private PreferencesSessionStore store;
    private LocalAuthService auth;
    private String database,preferences;
    private interface Call<T> { void start(RepositoryCallback<T> callback); }
    private <T> T await(Call<T> call) throws Exception {
        CompletableFuture<T> future=new CompletableFuture<>();
        call.start(new RepositoryCallback<T>() {
            @Override public void onSuccess(T value) { future.complete(value); }
            @Override public void onError(Exception error) { future.completeExceptionally(error); }
        }); return future.get(20,TimeUnit.SECONDS);
    }
    private SessionState restored() throws Exception {
        CompletableFuture<SessionState> future=new CompletableFuture<>();
        ObservableQuery.Subscription sub=auth.sessions().subscribe(Runnable::run,new ObservableQuery.Observer<SessionState>() {
            @Override public void onChanged(SessionState value) { if (value.status != SessionState.Status.RESTORING) future.complete(value); }
            @Override public void onError(Exception error) { future.completeExceptionally(error); }
        });
        try { return future.get(20,TimeUnit.SECONDS); } finally { sub.close(); }
    }
    @Before public void setup() throws Exception {
        context=ApplicationProvider.getApplicationContext(); String id=UUID.randomUUID().toString();
        database="auth-"+id+".db"; preferences="auth-"+id;
        users=new SqliteRepositories(context,database); store=new PreferencesSessionStore(context,preferences);
        auth=new LocalAuthService(users,store); assertEquals(SessionState.Status.SIGNED_OUT,restored().status);
    }
    @After public void close() { auth.close(); users.close(); }
    private User register(String name,String email) throws Exception {
        return await((RepositoryCallback<User> c) -> auth.register(name,email,"Strong1!".toCharArray(),Runnable::run,c));
    }
    private void logout() throws Exception { await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c)); }
    private void rejects(AuthException.Code code,Call<User> call) {
        ExecutionException error=assertThrows(ExecutionException.class, () -> await(call));
        assertEquals(code,((AuthException)error.getCause()).code);
    }
    @Test public void registrationLoginLogoutAndTwoAccountSwitchingPersistIdentity() throws Exception {
        User alice=register(" Alice "," ALICE@EXAMPLE.COM ");
        assertEquals("alice",alice.username); assertEquals("alice@example.com",alice.email);
        assertEquals(alice.id,store.readUserId()); assertEquals(alice.id,restored().user.id);
        logout(); assertNull(store.readUserId());
        User bob=register("bob","bob@example.com"); assertNotEquals(alice.id,bob.id);
        logout();
        rejects(AuthException.Code.INVALID_CREDENTIALS,c -> auth.login("alice@example.com","Wrong1!".toCharArray(),Runnable::run,c));
        rejects(AuthException.Code.INVALID_CREDENTIALS,c -> auth.login("absent@example.com","Wrong1!".toCharArray(),Runnable::run,c));
        // Repeated failures never lock the account.
        for (int i=0;i<6;i++) rejects(AuthException.Code.INVALID_CREDENTIALS,c -> auth.login("alice@example.com","wrong".toCharArray(),Runnable::run,c));
        User signedIn=await((RepositoryCallback<User> c) -> auth.login(" ALICE@EXAMPLE.COM ","Strong1!".toCharArray(),Runnable::run,c));
        assertEquals(alice.id,signedIn.id);
        await((RepositoryCallback<User> c) -> auth.updateUpi(" alice@upi ",Runnable::run,c));
        auth.close(); users.close();
        // Recreate service, preferences adapter and database owner, as a process restart would.
        users=new SqliteRepositories(context,database); store=new PreferencesSessionStore(context,preferences); auth=new LocalAuthService(users,store);
        assertEquals(alice.id,restored().user.id); assertEquals("alice@upi",restored().user.upiId);
        logout(); await((RepositoryCallback<User> c) -> auth.login("bob@example.com","Strong1!".toCharArray(),Runnable::run,c));
        assertEquals(bob.id,restored().user.id); assertNull(restored().user.upiId);
        logout(); auth.close(); auth=new LocalAuthService(users,new PreferencesSessionStore(context,preferences));
        assertEquals(SessionState.Status.SIGNED_OUT,restored().status);
    }
    @Test public void duplicateAndInvalidSignupAreRejectedWithoutReplacingSession() throws Exception {
        register("alice","alice@example.com"); logout();
        rejects(AuthException.Code.DUPLICATE_EMAIL,c -> auth.register("different","ALICE@EXAMPLE.COM","Strong1!".toCharArray(),Runnable::run,c));
        rejects(AuthException.Code.DUPLICATE_USERNAME,c -> auth.register("ALICE","different@example.com","Strong1!".toCharArray(),Runnable::run,c));
        rejects(AuthException.Code.INVALID_EMAIL,c -> auth.register("valid","not-email","Strong1!".toCharArray(),Runnable::run,c));
        rejects(AuthException.Code.WEAK_PASSWORD,c -> auth.register("valid","valid@example.com","weak".toCharArray(),Runnable::run,c));
        rejects(AuthException.Code.USERNAME_REQUIRED,c -> auth.register(" ","valid@example.com","Strong1!".toCharArray(),Runnable::run,c));
        assertNull(store.readUserId()); assertEquals(SessionState.Status.SIGNED_OUT,restored().status);
    }
    @Test public void removedAndInvalidSessionRecordsCannotAuthenticate() throws Exception {
        User alice=register("alice","alice@example.com");
        await((RepositoryCallback<Boolean> c) -> users.deleteUser(alice.id,Runnable::run,c));
        auth.close(); auth=new LocalAuthService(users,store);
        assertEquals(SessionState.Status.SIGNED_OUT,restored().status); assertNull(store.readUserId());
        auth.close(); store.writeUserId("invalid"); auth=new LocalAuthService(users,store);
        assertEquals(SessionState.Status.SIGNED_OUT,restored().status); assertNull(store.readUserId());
    }
}
