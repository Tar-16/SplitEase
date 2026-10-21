package com.example.splitease.model;

import java.util.*;

/** Immutable data snapshot. Monetary values are integer paise; dates are UTC epoch millis. */
public final class RecurringBillContribution {
    public final String cycleId;
    public final String userId;
    public final long sharePaise;
    public final boolean paid;

    public RecurringBillContribution(String cycleId, String userId, long sharePaise, boolean paid) {
        this.cycleId = cycleId;
        this.userId = userId;
        this.sharePaise = sharePaise;
        this.paid = paid;
    }
}
