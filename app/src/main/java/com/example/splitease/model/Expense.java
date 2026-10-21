package com.example.splitease.model;

import java.util.*;

/** Immutable data snapshot. Monetary values are integer paise; dates are UTC epoch millis. */
public final class Expense {
    public final String id;
    public final String groupId;
    public final String title;
    public final long amountPaise;
    public final String payerId;
    public final String creatorId;
    public final String category;
    public final long expenseDate;
    public final List<ExpenseSplit> splits;

    public Expense(String id, String groupId, String title, long amountPaise, String payerId, String creatorId, String category, long expenseDate, List<ExpenseSplit> splits) {
        this.id = id;
        this.groupId = groupId;
        this.title = title;
        this.amountPaise = amountPaise;
        this.payerId = payerId;
        this.creatorId = creatorId;
        this.category = category;
        this.expenseDate = expenseDate;
        this.splits = Collections.unmodifiableList(new ArrayList<>(splits));
    }
}
