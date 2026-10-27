package com.example.splitease.debug;
import android.view.ViewGroup;
import androidx.navigation.NavController;
import androidx.navigation.NavGraph;
/** No selectable cloud mode or emulator endpoint in release. Local mode remains active. */
public final class FirebaseAuthSandbox {
    private FirebaseAuthSandbox(){}
    public static void install(NavController navigation,NavGraph graph){}
    public static boolean acceptsDestination(int id){return false;}
    public static void attach(ViewGroup parent,NavController navigation){}
}
