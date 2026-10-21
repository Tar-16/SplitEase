package com.example.splitease.model;

import java.util.*;

/** Immutable data snapshot. Monetary values are integer paise; dates are UTC epoch millis. */
public final class RecurringBillCycle {
    public final String id;
    public final String billId;
    public final int year;
    public final int month;
    public final long dueDate;
    public final long amountPaise;
    public final String handlerId;
    public final List<RecurringBillContribution> contributions;

    public RecurringBillCycle(String id, String billId, int year, int month, long dueDate, long amountPaise, String handlerId, List<RecurringBillContribution> contributions) {
        this.id = id;
        this.billId = billId;
        this.year = year;
        this.month = month;
        this.dueDate = dueDate;
        this.amountPaise = amountPaise;
        this.handlerId = handlerId;
        this.contributions = Collections.unmodifiableList(new ArrayList<>(contributions));
    }
}
