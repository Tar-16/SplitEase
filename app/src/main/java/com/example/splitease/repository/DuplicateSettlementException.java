package com.example.splitease.repository;
/** Caller must explicitly acknowledge a same-parties/amount/UTC-date record before retrying. */
public final class DuplicateSettlementException extends IllegalArgumentException {
    public DuplicateSettlementException() { super("Possible duplicate settlement"); }
}
