package com.example.splitease.model;

import java.util.*;

/** Immutable data snapshot. Monetary values are integer paise; dates are UTC epoch millis. */
public final class RecurringBill {
    public final String id;
    public final String groupId;
    public final String title;
    public final long amountPaise;
    public final int dueDay;
    public final List<String> participantIds;
    public final String handlerId;
    public final int reminderLeadDays;
    public final boolean active;

    public RecurringBill(String id, String groupId, String title, long amountPaise, int dueDay, List<String> participantIds, String handlerId, int reminderLeadDays, boolean active) {
        this.id = id;
        this.groupId = groupId;
        this.title = title;
        this.amountPaise = amountPaise;
        this.dueDay = dueDay;
        this.participantIds = Collections.unmodifiableList(new ArrayList<>(participantIds));
        this.handlerId = handlerId;
        this.reminderLeadDays = reminderLeadDays;
        this.active = active;
    }
}
