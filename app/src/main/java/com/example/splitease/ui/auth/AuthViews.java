package com.example.splitease.ui.auth;

import android.content.Context;
import android.text.Editable;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import com.example.splitease.R;
import com.example.splitease.auth.AuthException;

/** Small view-only helpers; no persistence or authentication decisions. */
public final class AuthViews {
    private AuthViews() {}
    public static char[] password(Editable text) {
        if (text == null) return new char[0];
        char[] result=new char[text.length()]; text.getChars(0,text.length(),result,0); return result;
    }
    public static String text(Editable text) { return text == null ? "" : text.toString(); }
    public static void hideKeyboard(View view) {
        InputMethodManager input=(InputMethodManager)view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(view.getWindowToken(),0);
        view.clearFocus();
    }
    public static int error(AuthException.Code code) {
        if (code == null) return R.string.auth_storage;
        switch (code) {
            case USERNAME_REQUIRED: return R.string.auth_username_required;
            case INVALID_EMAIL: return R.string.auth_invalid_email;
            case WEAK_PASSWORD: return R.string.auth_weak_password;
            case PASSWORD_MISMATCH: return R.string.auth_mismatch;
            case DUPLICATE_EMAIL: return R.string.auth_duplicate_email;
            case DUPLICATE_USERNAME: return R.string.auth_duplicate_username;
            case INVALID_CREDENTIALS: return R.string.auth_invalid_credentials;
            case BUSY: return R.string.auth_busy;
            case SIGNED_OUT: return R.string.auth_signed_out;
            case NETWORK: return R.string.auth_network;
            case RATE_LIMITED: return R.string.auth_rate_limited;
            case SESSION_EXPIRED: return R.string.auth_session_expired;
            case AUTH_UNAVAILABLE: return R.string.auth_cloud_unavailable;
            case UNSUPPORTED: return R.string.auth_unsupported;
            case CANCELLED: return R.string.auth_cancelled;
            default: return R.string.auth_storage;
        }
    }
}
