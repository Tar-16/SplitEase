package com.example.splitease.auth;

import com.example.splitease.model.User;

public final class SessionState {
    public enum Status { RESTORING, SIGNED_OUT, SIGNED_IN, UNAVAILABLE }
    public final Status status;
    public final User user;
    private SessionState(Status status, User user) { this.status = status; this.user = user; }
    public static SessionState restoring() { return new SessionState(Status.RESTORING, null); }
    public static SessionState signedOut() { return new SessionState(Status.SIGNED_OUT, null); }
    public static SessionState signedIn(User user) { return new SessionState(Status.SIGNED_IN, user); }
    public static SessionState unavailable() { return new SessionState(Status.UNAVAILABLE, null); }
}
