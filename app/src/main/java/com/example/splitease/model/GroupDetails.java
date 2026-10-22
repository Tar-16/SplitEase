package com.example.splitease.model;

import java.util.*;

/** Authorized snapshot; members are current members only. Departures remain in storage. */
public final class GroupDetails {
    public final Group group;
    public final List<MemberIdentity> members;
    public final boolean canLeave;
    public GroupDetails(Group group, List<MemberIdentity> members, boolean canLeave) {
        this.group=group;
        this.members=Collections.unmodifiableList(new ArrayList<>(members));
        this.canLeave=canLeave;
    }
}
