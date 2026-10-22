package com.example.splitease.model;

/** Derived suggestion, not a completed payment or persisted entity. */
public final class Transfer {
    public final String payerId,payeeId;
    public final long amountPaise;
    public Transfer(String payerId,String payeeId,long amountPaise) { this.payerId=payerId; this.payeeId=payeeId; this.amountPaise=amountPaise; }
}
