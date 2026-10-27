package com.example.splitease.model;

/** Private cloud profile. UID is Firebase's opaque identity, never a SQLite UUID. */
public final class CloudProfile {
    public final String uid, normalizedUsername, displayUsername, email, upiId;
    public final long createdAtMillis, updatedAtMillis;
    public CloudProfile(String uid,String normalizedUsername,String displayUsername,String email,String upiId,long createdAtMillis,long updatedAtMillis){
        this.uid=uid;this.normalizedUsername=normalizedUsername;this.displayUsername=displayUsername;this.email=email;this.upiId=upiId;this.createdAtMillis=createdAtMillis;this.updatedAtMillis=updatedAtMillis;
    }
    public User asUser(){return new User(uid,displayUsername,email,upiId);}
}
