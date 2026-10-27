package com.example.splitease.debug;

import android.view.ViewGroup;
import androidx.lifecycle.LifecycleOwner;

/** Release builds contain no diagnostic UI or endpoint probe. */
public final class FirebaseDiagnostics {
    private FirebaseDiagnostics() {}
    public static void attach(ViewGroup parent, LifecycleOwner owner) {}
}
