package com.example.splitease.model;

import java.util.*;

/** Immutable data snapshot. Monetary values are integer paise; dates are UTC epoch millis. */
public final class Group {
    public enum Type { FLAT, TRIP }
    public final String id;
    public final String name;
    public final Type type;
    public final String adminId;
    public final String creatorId;
    public final long createdAt;

    public Group(String id, String name, Type type, String adminId, long createdAt) {
        this(id, name, type, adminId, adminId, createdAt);
    }

    public Group(String id, String name, Type type, String adminId, String creatorId, long createdAt) {
        this.creatorId = creatorId;
        this.id = id;
        this.name = name;
        this.type = type;
        this.adminId = adminId;
        this.createdAt = createdAt;
    }
}
