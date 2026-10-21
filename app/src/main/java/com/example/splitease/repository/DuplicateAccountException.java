package com.example.splitease.repository;

/** Storage-independent uniqueness error; no SQL or credential details. */
public final class DuplicateAccountException extends Exception {
    public enum Field { EMAIL, USERNAME }
    public final Field field;
    public DuplicateAccountException(Field field) { super("Account identifier already in use"); this.field = field; }
}
