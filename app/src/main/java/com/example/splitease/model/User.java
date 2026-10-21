package com.example.splitease.model;

import java.util.*;

/** Immutable data snapshot. Monetary values are integer paise; dates are UTC epoch millis. */
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
