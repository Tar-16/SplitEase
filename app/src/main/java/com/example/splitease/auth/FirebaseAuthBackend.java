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
    default void registerProfile(String username,String email,char[] password,Executor executor,RepositoryCallback<User> callback){register(email,password,executor,callback);}
    default void completeProfile(String username,Executor executor,RepositoryCallback<User> callback){executor.execute(()->callback.onError(new AuthException(AuthException.Code.UNSUPPORTED)));}
    default void updateUpi(String upi,Executor executor,RepositoryCallback<User> callback){executor.execute(()->callback.onError(new AuthException(AuthException.Code.UNSUPPORTED)));}
    void login(String email, char[] password, Executor executor, RepositoryCallback<User> callback);
    void validate(Executor executor, RepositoryCallback<User> callback);
    void reset(String email, Executor executor, RepositoryCallback<Boolean> callback);
    void signOut();
}
