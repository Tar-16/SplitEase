package com.example.splitease.auth;

/** Safe error codes only: internal exceptions never reach authentication screens. */
public final class AuthException extends Exception {
    public enum Code { USERNAME_REQUIRED, INVALID_EMAIL, WEAK_PASSWORD, PASSWORD_MISMATCH,
        DUPLICATE_EMAIL, DUPLICATE_USERNAME, INVALID_CREDENTIALS, STORAGE, BUSY, SIGNED_OUT, CANCELLED, NETWORK, RATE_LIMITED, SESSION_EXPIRED, AUTH_UNAVAILABLE, UNSUPPORTED, PROFILE_REQUIRED, INVALID_USERNAME, INVALID_UPI, USER_NOT_FOUND }
    public final Code code;
    public AuthException(Code code) { super(code.name()); this.code = code; }
}
