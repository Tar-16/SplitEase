package com.example.splitease.auth;

import com.example.splitease.domain.AccountValidation;
import com.example.splitease.model.User;
import com.example.splitease.repository.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.example.splitease.auth.AuthException.Code.*;
import static com.example.splitease.auth.SessionState.Backend.FIREBASE;

/** Serial Firebase session coordinator. Cloud UIDs never become SQLite identities. */
public final class FirebaseAuthService implements AuthService, AutoCloseable {
    private final FirebaseAuthBackend backend;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"splitease-firebase-auth"));
    private final List<Listener> listeners=new CopyOnWriteArrayList<>();
    private final AtomicBoolean closing=new AtomicBoolean();
    private final ObservableQuery.Subscription authChanges;
    private SessionState state=SessionState.restoring(FIREBASE);
    private boolean busy,mutationPending;
    private int requests;
    private volatile long generation;
    private long lastLogoutGeneration;
    private String validatingUid;

    public FirebaseAuthService(FirebaseAuthBackend backend){
        this.backend=backend;
        authChanges=backend.observe(()->{
            if(!closing.get())try{worker.execute(this::changed);}catch(RejectedExecutionException ignored){/* Owner closed. */}
        });
        restore();
    }
    private final class Listener implements ObservableQuery.Subscription {
        final Executor executor;final ObservableQuery.Observer<SessionState> observer;final AtomicBoolean cancelled=new AtomicBoolean();
        Listener(Executor executor,ObservableQuery.Observer<SessionState> observer){this.executor=executor;this.observer=observer;}
        void send(SessionState value){executor.execute(()->{if(!cancelled.get())observer.onChanged(value);});}
        public void close(){cancelled.set(true);listeners.remove(this);}
    }
    @Override public ObservableQuery<SessionState> sessions(){return (executor,observer)->{
        Listener listener=new Listener(executor,observer);
        if(closing.get()){listener.send(SessionState.unavailable(FIREBASE));return listener;}
        try{worker.execute(()->{if(!listener.cancelled.get()){listeners.add(listener);listener.send(state);}});}catch(RejectedExecutionException closed){listener.send(SessionState.unavailable(FIREBASE));}
        return listener;
    };}
    private void publish(SessionState next){state=next;for(Listener listener:listeners)listener.send(next);}
    private void changed(){
        if(closing.get()||mutationPending)return;
        User current=backend.current();
        if(current==null){if(state.status==SessionState.Status.SIGNED_OUT)return;generation++;busy=false;validatingUid=null;publish(SessionState.signedOut(FIREBASE));return;}
        if(busy&&Objects.equals(validatingUid,current.id))return;
        if(!busy&&state.status==SessionState.Status.SIGNED_IN&&state.user.id.equals(current.id))return;
        generation++;busy=false;beginRestore();
    }
    @Override public void restore(){if(!closing.get())try{worker.execute(()->{if(!busy&&!mutationPending)beginRestore();});}catch(RejectedExecutionException ignored){}}
    private boolean valid(User user){return user!=null&&user.id!=null&&!user.id.trim().isEmpty()&&AccountValidation.validEmail(user.email);}
    private void beginRestore(){
        User cached=backend.current();
        if(cached==null){publish(SessionState.signedOut(FIREBASE));return;}
        if(!valid(cached)){backend.signOut();publish(SessionState.signedOut(FIREBASE));return;}
        busy=true;mutationPending=true;validatingUid=cached.id;long token=++generation;requests++;publish(SessionState.restoring(FIREBASE));
        backend.validate(worker,new RepositoryCallback<User>(){
            public void onSuccess(User user){try{
                mutationPending=false;
                if(token!=generation||closing.get()){if(token<lastLogoutGeneration){backend.signOut();if(!closing.get())publish(SessionState.signedOut(FIREBASE));}return;}
                busy=false;validatingUid=null;
                if(valid(user)&&backend.current()!=null&&user.id.equals(backend.current().id))publish(SessionState.cloudSignedIn(user));else beginRestore();
            }finally{finished();}}
            public void onError(Exception error){try{
                mutationPending=false;
                if(token!=generation||closing.get()){if(token<lastLogoutGeneration){backend.signOut();if(!closing.get())publish(SessionState.signedOut(FIREBASE));}return;}
                busy=false;validatingUid=null;
                if(code(error)==SESSION_EXPIRED||code(error)==INVALID_CREDENTIALS){backend.signOut();publish(SessionState.signedOut(FIREBASE));}
                else publish(SessionState.unavailable(FIREBASE));
            }finally{finished();}}
        });
    }
    private AuthException.Code code(Exception error){return error instanceof AuthException?((AuthException)error).code:AUTH_UNAVAILABLE;}
    private <T> void fail(Executor executor,RepositoryCallback<T> callback,AuthException.Code code){executor.execute(()->callback.onError(new AuthException(code)));}
    private boolean enqueue(Runnable work,Executor executor,RepositoryCallback<?> callback){
        if(closing.get()){fail(executor,callback,CANCELLED);return false;}
        try{worker.execute(work);return true;}catch(RejectedExecutionException closed){fail(executor,callback,CANCELLED);return false;}
    }
    private boolean start(Executor executor,RepositoryCallback<?> callback){
        if(busy||mutationPending){fail(executor,callback,BUSY);return false;}
        if(state.status!=SessionState.Status.SIGNED_OUT){fail(executor,callback,state.status==SessionState.Status.UNAVAILABLE?NETWORK:BUSY);return false;}
        busy=true;generation++;return true;
    }
    @Override public void register(String username,String email,char[] password,Executor executor,RepositoryCallback<User> callback){authenticate(true,username,email,password,executor,callback);}
    @Override public void login(String email,char[] password,Executor executor,RepositoryCallback<User> callback){authenticate(false,null,email,password,executor,callback);}
    private void authenticate(boolean signup,String username,String email,char[] password,Executor executor,RepositoryCallback<User> callback){
        char[] copy=password==null?new char[0]:password.clone();
        if(closing.get()){Arrays.fill(copy,'\0');fail(executor,callback,CANCELLED);return;}
        boolean accepted=enqueue(()->{
            try{
                if(signup&&AccountValidation.normalize(username).isEmpty()){fail(executor,callback,USERNAME_REQUIRED);return;}
                if(!AccountValidation.validEmail(email)){fail(executor,callback,INVALID_EMAIL);return;}
                if(signup&&!AccountValidation.strongPassword(copy)){fail(executor,callback,WEAK_PASSWORD);return;}
                if(copy.length==0){fail(executor,callback,INVALID_CREDENTIALS);return;}
                if(!start(executor,callback))return;
                long token=generation;mutationPending=true;requests++;
                RepositoryCallback<User> result=new RepositoryCallback<User>(){
                    public void onSuccess(User user){complete(user,null);}
                    public void onError(Exception error){complete(null,error);}
                    private void complete(User user,Exception error){try{
                        mutationPending=false;
                        if(token!=generation||closing.get()){backend.signOut();if(!closing.get())publish(SessionState.signedOut(FIREBASE));fail(executor,callback,CANCELLED);return;}
                        busy=false;
                        if(error!=null){if(code(error)==SESSION_EXPIRED)backend.signOut();publish(SessionState.signedOut(FIREBASE));fail(executor,callback,code(error));return;}
                        if(!valid(user)||backend.current()==null||!user.id.equals(backend.current().id)){backend.signOut();publish(SessionState.signedOut(FIREBASE));fail(executor,callback,SESSION_EXPIRED);return;}
                        publish(SessionState.cloudSignedIn(user));executor.execute(()->{if(token==generation&&!closing.get())callback.onSuccess(user);else callback.onError(new AuthException(CANCELLED));});
                    }finally{finished();}}
                };
                if(signup)backend.register(AccountValidation.normalize(email),copy,worker,result);else backend.login(AccountValidation.normalize(email),copy,worker,result);
            }finally{Arrays.fill(copy,'\0');}
        },executor,callback);
        if(!accepted)Arrays.fill(copy,'\0');
    }
    @Override public void logout(Executor executor,RepositoryCallback<Boolean> callback){enqueue(()->{
        generation++;lastLogoutGeneration=generation;busy=false;validatingUid=null;backend.signOut();publish(mutationPending?SessionState.restoring(FIREBASE):SessionState.signedOut(FIREBASE));executor.execute(()->callback.onSuccess(true));
        // Keep mutationPending until an un-cancellable SDK sign-in finishes, then sign out again.
    },executor,callback);}
    @Override public void resetPassword(String email,Executor executor,RepositoryCallback<Boolean> callback){enqueue(()->{
        if(!AccountValidation.validEmail(email)){fail(executor,callback,INVALID_EMAIL);return;}
        if(!start(executor,callback))return;
        long token=generation;requests++;
        backend.reset(AccountValidation.normalize(email),worker,new RepositoryCallback<Boolean>(){
            public void onSuccess(Boolean value){try{if(token!=generation||closing.get()){fail(executor,callback,CANCELLED);return;}busy=false;executor.execute(()->callback.onSuccess(true));}finally{finished();}}
            public void onError(Exception error){try{if(token!=generation||closing.get()){fail(executor,callback,CANCELLED);return;}busy=false;fail(executor,callback,code(error));}finally{finished();}}
        });
    },executor,callback);}
    @Override public void updateUpi(String upi,Executor executor,RepositoryCallback<User> callback){fail(executor,callback,UNSUPPORTED);}
    private void finished(){requests--;if(closing.get()&&requests==0)worker.shutdown();}
    @Override public void close(){if(!closing.compareAndSet(false,true))return;authChanges.close();for(Listener listener:listeners)listener.close();try{worker.execute(()->{generation++;if(requests==0)worker.shutdown();});}catch(RejectedExecutionException ignored){/* Last accepted callback already closed the worker. */}}
}
