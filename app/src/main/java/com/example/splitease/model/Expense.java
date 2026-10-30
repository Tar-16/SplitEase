package com.example.splitease.model;

import java.util.*;

/** Immutable data snapshot. Monetary values are integer paise; dates are UTC epoch millis. */
public final class Expense {
    /** Optimistic concurrency token for cloud snapshots; local snapshots retain zero. */
    public final long revision;
    /** Optional cloud description; the existing local schema is unchanged. */
    public final String description;
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
        this(id,groupId,title,amountPaise,payerId,creatorId,category,expenseDate,splits,0,"");
    }
    public Expense(String id,String groupId,String title,long amountPaise,String payerId,String creatorId,String category,long expenseDate,List<ExpenseSplit> splits,long revision,String description) {
        this.revision=revision;this.description=description;
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
