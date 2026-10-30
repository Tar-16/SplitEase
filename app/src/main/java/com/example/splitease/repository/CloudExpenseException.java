package com.example.splitease.repository;
/** Sanitized cloud expense failures. Reload before retrying a conflicting edit. */
public final class CloudExpenseException extends Exception {
 public enum Code { INVALID, FORBIDDEN, CONFLICT, UNAVAILABLE, CANCELLED, UNSUPPORTED }
 public final Code code;
 public CloudExpenseException(Code code){super(code.name());this.code=code;}
}
