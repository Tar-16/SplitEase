package com.example.splitease.auth;

import com.example.splitease.model.User;

public final class SessionState {
    public enum Status { RESTORING, SIGNED_OUT, SIGNED_IN, UNAVAILABLE, PROFILE_REQUIRED }
    public enum Backend { LOCAL, FIREBASE }
    public final Status status;
    public final User user;
    public final Backend backend;
    private SessionState(Status status, User user, Backend backend) { this.status=status;this.user=user;this.backend=backend; }
    public static SessionState restoring() { return restoring(Backend.LOCAL); }
    public static SessionState signedOut() { return signedOut(Backend.LOCAL); }
    public static SessionState signedIn(User user) { return new SessionState(Status.SIGNED_IN,user,Backend.LOCAL); }
    public static SessionState unavailable() { return unavailable(Backend.LOCAL); }
    public static SessionState restoring(Backend backend) { return new SessionState(Status.RESTORING,null,backend); }
    public static SessionState signedOut(Backend backend) { return new SessionState(Status.SIGNED_OUT,null,backend); }
    public static SessionState cloudSignedIn(User identity) { return new SessionState(Status.SIGNED_IN,identity,Backend.FIREBASE); }
    public static SessionState profileRequired(User identity) { return new SessionState(Status.PROFILE_REQUIRED,identity,Backend.FIREBASE); }
    public static SessionState unavailable(Backend backend) { return new SessionState(Status.UNAVAILABLE,null,backend); }
}
