package com.example.splitease.model;

import java.util.*;

/** Immutable data snapshot. Monetary values are integer paise; dates are UTC epoch millis. */
public final class ExpenseSplit {
    public final String expenseId;
    public final String userId;
    public final long sharePaise;

    public ExpenseSplit(String expenseId, String userId, long sharePaise) {
        this.expenseId = expenseId;
        this.userId = userId;
        this.sharePaise = sharePaise;
    }
}
