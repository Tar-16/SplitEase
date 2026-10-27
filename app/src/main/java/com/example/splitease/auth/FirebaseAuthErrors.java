package com.example.splitease.auth;

/** Safe Firebase code mapping, never messages/causes from remote exceptions. */
public final class FirebaseAuthErrors {
    private FirebaseAuthErrors() {}
    public static AuthException.Code code(String code) {
        if(code==null)return AuthException.Code.AUTH_UNAVAILABLE;
        switch(code){
            case "ERROR_INVALID_EMAIL": return AuthException.Code.INVALID_EMAIL;
            case "ERROR_WEAK_PASSWORD": return AuthException.Code.WEAK_PASSWORD;
            case "ERROR_EMAIL_ALREADY_IN_USE": return AuthException.Code.DUPLICATE_EMAIL;
            case "ERROR_WRONG_PASSWORD": case "ERROR_USER_NOT_FOUND": case "ERROR_INVALID_CREDENTIAL":
            case "ERROR_INVALID_LOGIN_CREDENTIALS": return AuthException.Code.INVALID_CREDENTIALS;
            case "ERROR_USER_DISABLED": case "ERROR_USER_TOKEN_EXPIRED": case "ERROR_INVALID_USER_TOKEN":
            case "ERROR_TOKEN_EXPIRED": return AuthException.Code.SESSION_EXPIRED;
            case "ERROR_NETWORK_REQUEST_FAILED": return AuthException.Code.NETWORK;
            case "ERROR_TOO_MANY_REQUESTS": case "ERROR_QUOTA_EXCEEDED": return AuthException.Code.RATE_LIMITED;
            default: return AuthException.Code.AUTH_UNAVAILABLE;
        }
    }
}
