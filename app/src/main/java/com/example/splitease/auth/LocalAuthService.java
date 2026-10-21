package com.example.splitease.auth;

import com.example.splitease.domain.AccountValidation;
import com.example.splitease.domain.Rules;
import com.example.splitease.model.User;
import com.example.splitease.repository.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.example.splitease.auth.AuthException.Code.*;

/** Serial session coordination over the existing asynchronous user repository. No Android/SQL. */
public final class LocalAuthService implements AuthService, AutoCloseable {
    private final UserRepository users;
    private final SessionStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "splitease-auth"));
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private SessionState state = SessionState.restoring();
    private boolean busy;
    private long generation;

    public LocalAuthService(UserRepository users, SessionStore store) {
        this.users = users; this.store = store; restore();
    }
    private final class Listener implements ObservableQuery.Subscription {
        final Executor executor;
        final ObservableQuery.Observer<SessionState> observer;
        final AtomicBoolean cancelled = new AtomicBoolean();
        Listener(Executor executor, ObservableQuery.Observer<SessionState> observer) { this.executor=executor; this.observer=observer; }
        void deliver(SessionState snapshot) { executor.execute(() -> { if (!cancelled.get()) observer.onChanged(snapshot); }); }
        @Override public void close() { cancelled.set(true); listeners.remove(this); }
    }
    @Override public ObservableQuery<SessionState> sessions() {
        return (executor, observer) -> {
            Listener listener = new Listener(executor, observer);
            worker.execute(() -> { if (!listener.cancelled.get()) { listeners.add(listener); listener.deliver(state); } });
            return listener;
        };
    }
    private void publish(SessionState next) { state=next; for (Listener listener : listeners) listener.deliver(next); }
    @Override public void restore() {
        worker.execute(() -> {
            if (busy) return;
            busy=true; long token=++generation; publish(SessionState.restoring());
            final String id;
            try {
                id=store.readUserId();
                if (id == null) { busy=false; publish(SessionState.signedOut()); return; }
                try { Rules.id(id); }
                catch (IllegalArgumentException error) { clearInvalidSession(); return; }
            } catch (Exception error) { busy=false; publish(SessionState.unavailable()); return; }
            users.getUser(id,worker,new RepositoryCallback<User>() {
                @Override public void onSuccess(User user) {
                    if (token != generation) return;
                    busy=false; publish(SessionState.signedIn(user));
                }
                @Override public void onError(Exception error) {
                    if (token != generation) return;
                    if (error instanceof NoSuchElementException) clearInvalidSession();
                    else { busy=false; publish(SessionState.unavailable()); }
                }
            });
        });
    }
    private void clearInvalidSession() {
        busy=false;
        try { store.clear(); publish(SessionState.signedOut()); }
        catch (Exception error) { publish(SessionState.unavailable()); }
    }
    private <T> void fail(Executor executor, RepositoryCallback<T> callback, AuthException.Code code) {
        executor.execute(() -> callback.onError(new AuthException(code)));
    }
    private AuthException.Code safeCode(Exception error) {
        if (error instanceof DuplicateAccountException) return ((DuplicateAccountException)error).field == DuplicateAccountException.Field.EMAIL ? DUPLICATE_EMAIL : DUPLICATE_USERNAME;
        return STORAGE;
    }
    private boolean start(Executor executor, RepositoryCallback<?> callback) {
        if (busy || state.status == SessionState.Status.RESTORING) { fail(executor,callback,BUSY); return false; }
        if (state.status != SessionState.Status.SIGNED_OUT) { fail(executor,callback,STORAGE); return false; }
        busy=true; ++generation; return true;
    }
    private void authenticated(long token, User user, Executor executor, RepositoryCallback<User> callback) {
        if (token != generation) { fail(executor,callback,CANCELLED); return; }
        busy=false;
        try { store.writeUserId(user.id); }
        catch (Exception error) { fail(executor,callback,STORAGE); return; }
        publish(SessionState.signedIn(user));
        executor.execute(() -> callback.onSuccess(user));
    }
    private void failed(long token, Executor executor, RepositoryCallback<?> callback, AuthException.Code code) {
        if (token != generation) { fail(executor,callback,CANCELLED); return; }
        busy=false; fail(executor,callback,code);
    }
    @Override public void register(String username, String email, char[] password, Executor executor, RepositoryCallback<User> callback) {
        char[] copy=password == null ? new char[0] : password.clone();
        worker.execute(() -> {
            try {
                if (AccountValidation.normalize(username).isEmpty()) { fail(executor,callback,USERNAME_REQUIRED); return; }
                if (!AccountValidation.validEmail(email)) { fail(executor,callback,INVALID_EMAIL); return; }
                if (!AccountValidation.strongPassword(copy)) { fail(executor,callback,WEAK_PASSWORD); return; }
                if (!start(executor,callback)) return;
                long token=generation;
                User user=new User(UUID.randomUUID().toString(),AccountValidation.normalize(username),AccountValidation.normalize(email),null);
                users.createUser(user,copy,worker,new RepositoryCallback<User>() {
                    @Override public void onSuccess(User value) { authenticated(token,value,executor,callback); }
                    @Override public void onError(Exception error) { failed(token,executor,callback,safeCode(error)); }
                });
            } finally { Arrays.fill(copy,'\0'); }
        });
    }
    @Override public void login(String email, char[] password, Executor executor, RepositoryCallback<User> callback) {
        char[] copy=password == null ? new char[0] : password.clone();
        worker.execute(() -> {
            if (!AccountValidation.validEmail(email)) { Arrays.fill(copy,'\0'); fail(executor,callback,INVALID_EMAIL); return; }
            if (copy.length == 0) { fail(executor,callback,INVALID_CREDENTIALS); return; }
            if (!start(executor,callback)) { Arrays.fill(copy,'\0'); return; }
            long token=generation;
            users.findUserByEmail(AccountValidation.normalize(email),worker,new RepositoryCallback<User>() {
                @Override public void onSuccess(User user) {
                    try {
                        if (token != generation) { fail(executor,callback,CANCELLED); return; }
                        users.verifyPassword(user.id,copy,worker,new RepositoryCallback<Boolean>() {
                            @Override public void onSuccess(Boolean valid) {
                                if (valid) authenticated(token,user,executor,callback);
                                else failed(token,executor,callback,INVALID_CREDENTIALS);
                            }
                            @Override public void onError(Exception error) { failed(token,executor,callback,STORAGE); }
                        });
                    } finally { Arrays.fill(copy,'\0'); }
                }
                @Override public void onError(Exception error) {
                    Arrays.fill(copy,'\0');
                    failed(token,executor,callback,error instanceof NoSuchElementException ? INVALID_CREDENTIALS : STORAGE);
                }
            });
        });
    }
    @Override public void logout(Executor executor, RepositoryCallback<Boolean> callback) {
        worker.execute(() -> {
            ++generation; busy=false;
            try { store.clear(); }
            catch (Exception error) {
                // Hide protected content on an uncertain durable-store outcome; retry restoration/logout.
                publish(SessionState.unavailable()); fail(executor,callback,STORAGE); return;
            }
            publish(SessionState.signedOut()); executor.execute(() -> callback.onSuccess(true));
        });
    }
    @Override public void updateUpi(String upiId, Executor executor, RepositoryCallback<User> callback) {
        worker.execute(() -> {
            if (busy) { fail(executor,callback,BUSY); return; }
            if (state.status != SessionState.Status.SIGNED_IN) { fail(executor,callback,SIGNED_OUT); return; }
            busy=true; long token=++generation;
            User current=state.user;
            String normalized=upiId == null || upiId.trim().isEmpty() ? null : upiId.trim();
            users.updateUser(new User(current.id,current.username,current.email,normalized),worker,new RepositoryCallback<User>() {
                @Override public void onSuccess(User updated) {
                    if (token != generation) { fail(executor,callback,CANCELLED); return; }
                    busy=false; publish(SessionState.signedIn(updated)); executor.execute(() -> callback.onSuccess(updated));
                }
                @Override public void onError(Exception error) { failed(token,executor,callback,STORAGE); }
            });
        });
    }
    /** For test/owner teardown after pending operations finish. Production owner is application scoped. */
    @Override public void close() { for (Listener listener : listeners) listener.close(); worker.shutdown(); }
}
