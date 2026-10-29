package com.example.splitease.repository;

/** Safe cloud group failures; never relay SDK details/document contents to presentation. */
public final class CloudGroupException extends Exception {
    public enum Code { NOT_READY, FORBIDDEN, UNAVAILABLE, INVALID, DUPLICATE, UNSUPPORTED, CANCELLED }
    public final Code code;
    public CloudGroupException(Code code){super(code.name());this.code=code;}
}
