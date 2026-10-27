package com.example.splitease.data.firebase;

import com.example.splitease.auth.*;
import com.example.splitease.model.User;
import com.example.splitease.repository.*;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.*;
import java.util.concurrent.Executor;

/** Firebase owns credentials and persistence. No SQLite/preferences or Firestore operations. */
public final class FirebaseSdkAuthBackend implements FirebaseAuthBackend {
    private final FirebaseAuth auth;
    public FirebaseSdkAuthBackend(FirebaseAuth auth){this.auth=auth;}
    private User snapshot(FirebaseUser user){return user==null?null:new User(user.getUid(),"",user.getEmail(),null);}
    @Override public User current(){return snapshot(auth.getCurrentUser());}
    @Override public ObservableQuery.Subscription observe(Runnable changed){
        FirebaseAuth.AuthStateListener listener=value->changed.run();auth.addAuthStateListener(listener);
        return ()->auth.removeAuthStateListener(listener);
    }
    private AuthException error(Exception error,boolean restoring){
        AuthException.Code code;
        if(error instanceof FirebaseNetworkException)code=AuthException.Code.NETWORK;
        else if(error instanceof FirebaseTooManyRequestsException)code=AuthException.Code.RATE_LIMITED;
        else code=FirebaseAuthErrors.code(error instanceof FirebaseAuthException?((FirebaseAuthException)error).getErrorCode():null);
        if(restoring&&error instanceof FirebaseAuthInvalidUserException)code=AuthException.Code.SESSION_EXPIRED;
        return new AuthException(code);
    }
    @Override public void register(String email,char[] password,Executor executor,RepositoryCallback<User> callback){
        // Firebase's SDK requires an immutable String; it is transient, never retained by this adapter.
        try{auth.createUserWithEmailAndPassword(email,new String(password)).addOnCompleteListener(executor,task->{
            if(task.isSuccessful())callback.onSuccess(snapshot(task.getResult().getUser()));else callback.onError(error(task.getException(),false));
        });}catch(RuntimeException unavailable){executor.execute(()->callback.onError(new AuthException(AuthException.Code.AUTH_UNAVAILABLE)));}
    }
    @Override public void login(String email,char[] password,Executor executor,RepositoryCallback<User> callback){
        try{auth.signInWithEmailAndPassword(email,new String(password)).addOnCompleteListener(executor,task->{
            if(task.isSuccessful())callback.onSuccess(snapshot(task.getResult().getUser()));else callback.onError(error(task.getException(),false));
        });}catch(RuntimeException unavailable){executor.execute(()->callback.onError(new AuthException(AuthException.Code.AUTH_UNAVAILABLE)));}
    }
    @Override public void validate(Executor executor,RepositoryCallback<User> callback){
        FirebaseUser user=auth.getCurrentUser();
        if(user==null){executor.execute(()->callback.onError(new AuthException(AuthException.Code.SESSION_EXPIRED)));return;}
        try{user.reload().addOnCompleteListener(executor,reloaded->{
            if(!reloaded.isSuccessful()){callback.onError(error(reloaded.getException(),true));return;}
            // Force SDK validation of revoked/expired refresh credentials; never expose or retain the token.
            try{user.getIdToken(true).addOnCompleteListener(executor,token->{
                if(token.isSuccessful())callback.onSuccess(snapshot(user));else callback.onError(error(token.getException(),true));
            });}catch(RuntimeException unavailable){callback.onError(new AuthException(AuthException.Code.AUTH_UNAVAILABLE));}
        });}catch(RuntimeException unavailable){executor.execute(()->callback.onError(new AuthException(AuthException.Code.AUTH_UNAVAILABLE)));}
    }
    @Override public void reset(String email,Executor executor,RepositoryCallback<Boolean> callback){
        try{auth.sendPasswordResetEmail(email).addOnCompleteListener(executor,task->{
            if(task.isSuccessful())callback.onSuccess(true);
            else if(task.getException() instanceof FirebaseAuthInvalidUserException&&"ERROR_USER_NOT_FOUND".equals(((FirebaseAuthException)task.getException()).getErrorCode()))callback.onSuccess(true);
            else callback.onError(error(task.getException(),false));
        });}catch(RuntimeException unavailable){executor.execute(()->callback.onError(new AuthException(AuthException.Code.AUTH_UNAVAILABLE)));}
    }
    @Override public void signOut(){auth.signOut();}
}
