package com.example.splitease.model;

import java.util.*;

/** Immutable user snapshot. Local records use UUIDs; a FIREBASE-scoped auth snapshot uses the opaque SDK UID and must never be inserted into SQLite. */
public final class User {
    public final String id;
    public final String username;
    public final String email;
    public final String upiId;

    public User(String id, String username, String email, String upiId) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.upiId = upiId;
    }
}
