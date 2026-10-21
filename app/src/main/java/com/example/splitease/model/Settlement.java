package com.example.splitease.model;

import java.util.*;

/** Immutable data snapshot. Monetary values are integer paise; dates are UTC epoch millis. */
public final class Settlement {
    public final String id;
    public final String groupId;
    public final String payerId;
    public final String payeeId;
    public final long amountPaise;
    public final long settlementDate;

    public Settlement(String id, String groupId, String payerId, String payeeId, long amountPaise, long settlementDate) {
        this.id = id;
        this.groupId = groupId;
        this.payerId = payerId;
        this.payeeId = payeeId;
        this.amountPaise = amountPaise;
        this.settlementDate = settlementDate;
    }
}
