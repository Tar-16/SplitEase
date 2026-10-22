package com.example.splitease.model;

/** Public local-directory identity. Deliberately excludes email, UPI and credentials. */
public final class MemberIdentity {
    public final String id, username;
    public MemberIdentity(String id, String username) { this.id=id; this.username=username; }
}
