package com.example.splitease.data;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.splitease.auth.SessionStore;
import java.io.IOException;

/** Stores only the active UUID. Not a password, token or replacement for credential verification. */
public final class PreferencesSessionStore implements SessionStore {
    private final Context context;
    private final String name;
    public PreferencesSessionStore(Context context) { this(context, "local_session"); }
    public PreferencesSessionStore(Context context, String name) {
        this.context = context.getApplicationContext(); this.name = name;
    }
    private SharedPreferences preferences() { return context.getSharedPreferences(name, Context.MODE_PRIVATE); }
    @Override public String readUserId() {
        try { return preferences().getString("user_id", null); }
        catch (ClassCastException malformedValue) { return ""; } // Restore clears malformed identifiers.
    }
    @Override public void writeUserId(String id) throws IOException {
        if (!preferences().edit().putString("user_id", id).commit()) throw new IOException("Session write failed");
    }
    @Override public void clear() throws IOException {
        if (!preferences().edit().remove("user_id").commit()) throw new IOException("Session clear failed");
    }
}
