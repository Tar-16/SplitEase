package com.example.splitease.auth;

import com.example.splitease.domain.CloudProfileValidation;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import java.util.concurrent.Executor;

/** Coordinates Auth with private profiles without pretending the two services share a transaction.
 * Never deletes Auth accounts on ambiguous failures. Login/restore recover incomplete onboarding.
 */
public final class ProfiledFirebaseAuthBackend implements FirebaseAuthBackend {
    private final FirebaseAuthBackend auth;
    private final CloudProfileRepository profiles;
    private volatile User profile;
    public ProfiledFirebaseAuthBackend(FirebaseAuthBackend auth,CloudProfileRepository profiles){this.auth=auth;this.profiles=profiles;}
    @Override public User current(){User identity=auth.current(),cached=profile;return identity!=null&&cached!=null&&identity.id.equals(cached.id)?cached:identity;}
    @Override public ObservableQuery.Subscription observe(Runnable listener){return auth.observe(listener);}
    private boolean matches(User identity){User current=auth.current();return identity!=null&&current!=null&&identity.id.equals(current.id);}
    private RepositoryCallback<CloudProfile> result(RepositoryCallback<User> callback){return new RepositoryCallback<CloudProfile>(){
        public void onSuccess(CloudProfile value){User identity=auth.current();if(identity==null||!identity.id.equals(value.uid)){callback.onError(new AuthException(AuthException.Code.CANCELLED));return;}profile=value.asUser();callback.onSuccess(profile);}
        public void onError(Exception error){callback.onError(error);}
    };}
    @Override public void register(String email,char[] password,Executor executor,RepositoryCallback<User> callback){executor.execute(()->callback.onError(new AuthException(AuthException.Code.PROFILE_REQUIRED)));}
    @Override public void registerProfile(String username,String email,char[] password,Executor executor,RepositoryCallback<User> callback){
        try{CloudProfileValidation.display(username);}catch(IllegalArgumentException invalid){executor.execute(()->callback.onError(new AuthException(AuthException.Code.INVALID_USERNAME)));return;}
        auth.register(email,password,executor,new RepositoryCallback<User>(){
            public void onSuccess(User identity){if(!matches(identity)){callback.onError(new AuthException(AuthException.Code.CANCELLED));return;}profiles.createOwnProfile(username,executor,result(callback));}
            public void onError(Exception error){callback.onError(error);}
        });
    }
    private RepositoryCallback<User> load(Executor executor,RepositoryCallback<User> callback){return new RepositoryCallback<User>(){
        public void onSuccess(User identity){if(!matches(identity)){callback.onError(new AuthException(AuthException.Code.CANCELLED));return;}profiles.getOwnProfile(executor,result(callback));}
        public void onError(Exception error){callback.onError(error);}
    };}
    @Override public void login(String email,char[] password,Executor executor,RepositoryCallback<User> callback){profile=null;auth.login(email,password,executor,load(executor,callback));}
    @Override public void validate(Executor executor,RepositoryCallback<User> callback){profile=null;auth.validate(executor,load(executor,callback));}
    @Override public void completeProfile(String username,Executor executor,RepositoryCallback<User> callback){profiles.createOwnProfile(username,executor,result(callback));}
    @Override public void updateUpi(String upi,Executor executor,RepositoryCallback<User> callback){
        profiles.getOwnProfile(executor,new RepositoryCallback<CloudProfile>(){
            public void onSuccess(CloudProfile original){profiles.updateOwnProfile(original.displayUsername,upi,executor,result(callback));}
            public void onError(Exception error){callback.onError(error);}
        });
    }
    @Override public void reset(String email,Executor executor,RepositoryCallback<Boolean> callback){auth.reset(email,executor,callback);}
    @Override public void signOut(){profile=null;auth.signOut();}
}
