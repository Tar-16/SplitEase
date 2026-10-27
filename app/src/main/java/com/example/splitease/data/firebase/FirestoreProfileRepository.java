package com.example.splitease.data.firebase;

import com.example.splitease.auth.AuthException;
import com.example.splitease.domain.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.*;
import static com.example.splitease.auth.AuthException.Code.*;

/** Private owner reads plus minimal exact registry lookup. Transactions and rules both enforce pairing. */
public final class FirestoreProfileRepository implements CloudProfileRepository {
    private final FirebaseFirestore db;
    private final FirebaseAuth auth;
    public FirestoreProfileRepository(FirebaseFirestore db,FirebaseAuth auth){
        if(!db.getApp().getName().equals(auth.getApp().getName()))throw new IllegalArgumentException("Auth/profile scope mismatch");
        this.db=db;this.auth=auth;
    }
    private String actor(){FirebaseUser user=auth.getCurrentUser();return user==null?null:user.getUid();}
    private boolean same(String uid){return uid!=null&&uid.equals(actor());}
    private AuthException safe(Exception error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof AuthException)return (AuthException)cause;
        return new AuthException(error instanceof FirebaseFirestoreException&&(((FirebaseFirestoreException)error).getCode()==FirebaseFirestoreException.Code.UNAVAILABLE||((FirebaseFirestoreException)error).getCode()==FirebaseFirestoreException.Code.DEADLINE_EXCEEDED)?NETWORK:AUTH_UNAVAILABLE);
    }
    private <T>void fail(Executor executor,RepositoryCallback<T> cb,AuthException.Code code){executor.execute(()->cb.onError(new AuthException(code)));}
    private <T>void deliver(String uid,RepositoryCallback<T> cb,T value){if(same(uid))cb.onSuccess(value);else cb.onError(new AuthException(CANCELLED));}
    private Map<String,Object> registry(String uid,String display){Map<String,Object> value=new HashMap<>();value.put("uid",uid);value.put("displayUsername",display);return value;}
    private CloudProfile parse(DocumentSnapshot document)throws AuthException{
        if(!document.exists())throw new AuthException(PROFILE_REQUIRED);
        try{
            Timestamp created=document.getTimestamp("createdAt"),updated=document.getTimestamp("updatedAt");
            String uid=document.getString("uid"),display=document.getString("displayUsername"),normalized=document.getString("normalizedUsername"),email=document.getString("email"),upi=document.getString("upiId");
            if(!document.getId().equals(uid)||!CloudProfileValidation.normalized(display).equals(normalized)||!AccountValidation.validEmail(email)||created==null||updated==null)throw new IllegalArgumentException();
            return new CloudProfile(uid,normalized,display,email,CloudProfileValidation.upi(upi),created.toDate().getTime(),updated.toDate().getTime());
        }catch(RuntimeException invalid){throw new AuthException(AUTH_UNAVAILABLE);}
    }
    @Override public void getOwnProfile(Executor executor,RepositoryCallback<CloudProfile> cb){
        String uid=actor();if(uid==null){fail(executor,cb,SIGNED_OUT);return;}
        db.collection("profiles").document(uid).get(Source.SERVER).addOnCompleteListener(executor,task->{
            if(!same(uid)){cb.onError(new AuthException(CANCELLED));return;}
            if(!task.isSuccessful()){cb.onError(safe(task.getException()));return;}
            try{deliver(uid,cb,parse(task.getResult()));}catch(AuthException invalid){cb.onError(invalid);}
        });
    }
    @Override public void createOwnProfile(String username,Executor executor,RepositoryCallback<CloudProfile> cb){
        String uid=actor();if(uid==null){fail(executor,cb,SIGNED_OUT);return;}
        String display;try{display=CloudProfileValidation.display(username);}catch(IllegalArgumentException invalid){fail(executor,cb,INVALID_USERNAME);return;}
        String normalized=CloudProfileValidation.normalized(display),email=AccountValidation.normalize(auth.getCurrentUser().getEmail());
        DocumentReference profile=db.collection("profiles").document(uid),registry=db.collection("usernames").document(normalized);
        db.runTransaction(transaction->{
            if(!same(uid))throw new IllegalStateException(new AuthException(CANCELLED));
            DocumentSnapshot existing=transaction.get(profile);
            // Retry after ambiguous acknowledgement returns the already committed owner; never changes its username.
            if(existing.exists())return null;
            DocumentSnapshot reservation=transaction.get(registry);
            if(reservation.exists()&&!uid.equals(reservation.getString("uid")))throw new IllegalStateException(new AuthException(DUPLICATE_USERNAME));
            Map<String,Object> p=new HashMap<>();p.put("uid",uid);p.put("normalizedUsername",normalized);p.put("displayUsername",display);p.put("email",email);p.put("upiId",null);p.put("createdAt",FieldValue.serverTimestamp());p.put("updatedAt",FieldValue.serverTimestamp());
            transaction.set(profile,p);transaction.set(registry,registry(uid,display));return null;
        }).addOnCompleteListener(executor,task->{if(!same(uid)){cb.onError(new AuthException(CANCELLED));return;}if(task.isSuccessful())getOwnProfile(executor,cb);else {
            Exception original=task.getException();
            // Competing commits can be rejected by rules before a transaction retry. Confirm the
            // actual public reservation, rather than misreporting every permission failure as a collision.
            registry.get(Source.SERVER).addOnCompleteListener(executor,checked->{
                if(!same(uid)){cb.onError(new AuthException(CANCELLED));return;}
                if(checked.isSuccessful()&&checked.getResult().exists()&&!uid.equals(checked.getResult().getString("uid")))cb.onError(new AuthException(DUPLICATE_USERNAME));
                else cb.onError(safe(original));
            });
        }});
    }
    @Override public void updateOwnProfile(String username,String upiId,Executor executor,RepositoryCallback<CloudProfile> cb){
        String uid=actor();if(uid==null){fail(executor,cb,SIGNED_OUT);return;}
        String display,upi;try{display=CloudProfileValidation.display(username);}catch(IllegalArgumentException invalid){fail(executor,cb,INVALID_USERNAME);return;}
        try{upi=CloudProfileValidation.upi(upiId);}catch(IllegalArgumentException invalid){fail(executor,cb,INVALID_UPI);return;}
        DocumentReference profile=db.collection("profiles").document(uid);
        db.runTransaction(transaction->{
            if(!same(uid))throw new IllegalStateException(new AuthException(CANCELLED));
            CloudProfile original;try{original=parse(transaction.get(profile));}catch(AuthException invalid){throw new IllegalStateException(invalid);}
            if(!original.normalizedUsername.equals(CloudProfileValidation.normalized(display)))throw new IllegalStateException(new AuthException(INVALID_USERNAME));
            transaction.update(profile,"displayUsername",display,"upiId",upi,"updatedAt",FieldValue.serverTimestamp());
            transaction.set(db.collection("usernames").document(original.normalizedUsername),registry(uid,display));return null;
        }).addOnCompleteListener(executor,task->{if(!same(uid)){cb.onError(new AuthException(CANCELLED));return;}if(task.isSuccessful())getOwnProfile(executor,cb);else cb.onError(safe(task.getException()));});
    }
    @Override public void findByUsername(String username,Executor executor,RepositoryCallback<MemberIdentity> cb){
        String uid=actor();if(uid==null){fail(executor,cb,SIGNED_OUT);return;}
        String normalized;try{normalized=CloudProfileValidation.normalized(username);}catch(IllegalArgumentException invalid){fail(executor,cb,INVALID_USERNAME);return;}
        db.collection("usernames").document(normalized).get(Source.SERVER).addOnCompleteListener(executor,task->{
            if(!same(uid)){cb.onError(new AuthException(CANCELLED));return;}
            if(!task.isSuccessful()){cb.onError(safe(task.getException()));return;}
            DocumentSnapshot record=task.getResult();if(!record.exists()){cb.onError(new AuthException(USER_NOT_FOUND));return;}
            deliver(uid,cb,new MemberIdentity(record.getString("uid"),record.getString("displayUsername")));
        });
    }
    @Override public ObservableQuery<CloudProfile> observeOwnProfile(){return (executor,observer)->{
        String uid=actor();AtomicBoolean closed=new AtomicBoolean();AtomicReference<ListenerRegistration> registration=new AtomicReference<>();AtomicReference<FirebaseAuth.AuthStateListener> authListener=new AtomicReference<>();
        ObservableQuery.Subscription close=()->{if(closed.compareAndSet(false,true)){ListenerRegistration listener=registration.get();if(listener!=null)listener.remove();FirebaseAuth.AuthStateListener authChanged=authListener.get();if(authChanged!=null)auth.removeAuthStateListener(authChanged);}};
        java.util.function.Consumer<Exception> terminate=error->{if(!closed.get()){close.close();observer.onError(error);}};
        if(uid==null){executor.execute(()->terminate.accept(new AuthException(SIGNED_OUT)));return close;}
        FirebaseAuth.AuthStateListener changed=value->{if(!same(uid))executor.execute(()->terminate.accept(new AuthException(CANCELLED)));};authListener.set(changed);auth.addAuthStateListener(changed);
        ListenerRegistration listener=db.collection("profiles").document(uid).addSnapshotListener(executor,MetadataChanges.INCLUDE,(snapshot,error)->{
            if(closed.get())return;if(!same(uid)){terminate.accept(new AuthException(CANCELLED));return;}
            if(error!=null){terminate.accept(safe(error));return;}
            // Server-authoritative data only; never treat an unacknowledged local write as ready.
            if(snapshot==null||snapshot.getMetadata().isFromCache()||snapshot.getMetadata().hasPendingWrites())return;
            try{observer.onChanged(parse(snapshot));}catch(AuthException invalid){terminate.accept(invalid);}
        });registration.set(listener);if(closed.get())listener.remove();return close;
    };}
}
