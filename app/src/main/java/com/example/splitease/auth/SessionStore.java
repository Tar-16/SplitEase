package com.example.splitease.auth;

import java.io.IOException;

/** Blocking private-store adapter. LocalAuthService invokes these only on its worker. */
public interface SessionStore {
    String readUserId() throws IOException;
    void writeUserId(String id) throws IOException;
    void clear() throws IOException;
}
