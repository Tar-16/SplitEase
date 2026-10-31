package com.example.splitease.repository;
/** Sanitized cloud accounting failures. Conflicts require a fresh snapshot and explicit retry. */
public final class CloudFinancialException extends Exception {
 public enum Code { INVALID, FORBIDDEN, CONFLICT, UNAVAILABLE, CANCELLED, UNSUPPORTED }
 public final Code code;
 public CloudFinancialException(Code code){super(code.name());this.code=code;}
}
