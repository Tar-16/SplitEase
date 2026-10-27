package com.example.splitease.auth;

import com.example.splitease.model.User;
import com.example.splitease.repository.ObservableQuery;
import com.example.splitease.repository.RepositoryCallback;
import java.util.concurrent.Executor;

/** Internal transport seam for SDK integration and deterministic coordinator tests. No SDK/token types. */
public interface FirebaseAuthBackend {
    User current();
    ObservableQuery.Subscription observe(Runnable changed);
    void register(String email, char[] password, Executor executor, RepositoryCallback<User> callback);
    void login(String email, char[] password, Executor executor, RepositoryCallback<User> callback);
    void validate(Executor executor, RepositoryCallback<User> callback);
    void reset(String email, Executor executor, RepositoryCallback<Boolean> callback);
    void signOut();
}
