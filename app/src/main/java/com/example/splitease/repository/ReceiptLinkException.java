package com.example.splitease.repository;

/** Expense save was confirmed, but the subsequent device receipt update could not be confirmed. */
public final class ReceiptLinkException extends Exception {
    public ReceiptLinkException() { super("Device receipt update could not be confirmed"); }
}
