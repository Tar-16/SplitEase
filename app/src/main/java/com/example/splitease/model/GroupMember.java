package com.example.splitease.model;

import java.util.*;

/** Immutable data snapshot. Monetary values are integer paise; dates are UTC epoch millis. */
public final class GroupMember {
    public enum Status { ACTIVE, LEFT }
    public final String groupId;
    public final String userId;
    public final Status status;
    public final long joinedAt;
    public final Long leftAt;

    public GroupMember(String groupId, String userId, Status status, long joinedAt, Long leftAt) {
        this.groupId = groupId;
        this.userId = userId;
        this.status = status;
        this.joinedAt = joinedAt;
        this.leftAt = leftAt;
    }
}
