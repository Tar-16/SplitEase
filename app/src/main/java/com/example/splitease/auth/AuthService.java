package com.example.splitease.auth;

import com.example.splitease.model.User;
import com.example.splitease.repository.*;
import java.util.concurrent.Executor;

/** Replaceable local/Firebase authentication boundary. UI never supplies a profile owner ID. */
public interface AuthService {
    ObservableQuery<SessionState> sessions();
    void restore();
    void register(String username, String email, char[] password, Executor executor, RepositoryCallback<User> callback);
    void login(String email, char[] password, Executor executor, RepositoryCallback<User> callback);
    void logout(Executor executor, RepositoryCallback<Boolean> callback);
    default void resetPassword(String email, Executor executor, RepositoryCallback<Boolean> callback) {
        executor.execute(() -> callback.onError(new AuthException(AuthException.Code.UNSUPPORTED)));
    }
    default void completeProfile(String username,Executor executor,RepositoryCallback<User> callback){executor.execute(()->callback.onError(new AuthException(AuthException.Code.UNSUPPORTED)));}
    void updateUpi(String upiId, Executor executor, RepositoryCallback<User> callback);
}
