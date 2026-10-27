package com.example.splitease.debug;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.navigation.NavController;
import androidx.navigation.NavGraph;
import androidx.navigation.fragment.FragmentNavigator;
import com.example.splitease.R;
import com.example.splitease.auth.FirebaseAuthService;
import com.example.splitease.auth.ProfiledFirebaseAuthBackend;
import com.example.splitease.data.firebase.FirestoreProfileRepository;
import com.google.firebase.firestore.*;
import com.example.splitease.data.firebase.FirebaseSdkAuthBackend;
import com.example.splitease.databinding.ViewOpenAuthSandboxBinding;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;

/** Explicit debug/demo scope. Never configures the real/default FirebaseAuth or local AuthService. */
public final class FirebaseAuthSandbox {
    public static final String PROJECT="demo-splitease-auth",APP_NAME="SplitEaseAuthEmulator";
    private static FirebaseAuthService service;
    private static FirebaseAuth emulator;
    private static FirestoreProfileRepository profiles;
    private FirebaseAuthSandbox(){}
    public static synchronized FirebaseAuth sdk(Context context){
        if(emulator!=null)return emulator;
        FirebaseApp app=null;for(FirebaseApp candidate:FirebaseApp.getApps(context))if(APP_NAME.equals(candidate.getName()))app=candidate;
        if(app==null){
            // Supported demo-only options, not a replacement for google-services.json or a live project.
            FirebaseOptions options=new FirebaseOptions.Builder().setProjectId(PROJECT).setApplicationId("1:1234567890:android:0000000000000000").setApiKey("demo-api-key").build();
            app=FirebaseApp.initializeApp(context.getApplicationContext(),options,APP_NAME);
        }
        FirebaseAuth auth=FirebaseAuth.getInstance(app);auth.useEmulator("127.0.0.1",9099);emulator=auth;return auth;
    }
    public static synchronized FirestoreProfileRepository profiles(Context context){
        if(profiles==null){FirebaseAuth auth=sdk(context);FirebaseFirestore db=FirebaseFirestore.getInstance(auth.getApp());db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build());db.useEmulator("127.0.0.1",8080);profiles=new FirestoreProfileRepository(db,auth);}return profiles;
    }
    public static synchronized FirebaseAuthService service(Context context){if(service==null)service=new FirebaseAuthService(new ProfiledFirebaseAuthBackend(new FirebaseSdkAuthBackend(sdk(context)),profiles(context)));return service;}
    public static void install(NavController navigation,NavGraph graph){
        if(graph.findNode(R.id.firebase_auth_sandbox)!=null)return;
        FragmentNavigator navigator=navigation.getNavigatorProvider().getNavigator(FragmentNavigator.class);
        FragmentNavigator.Destination destination=navigator.createDestination();destination.setId(R.id.firebase_auth_sandbox);destination.setClassName(FirebaseAuthSandboxFragment.class.getName());graph.addDestination(destination);
    }
    public static boolean acceptsDestination(int id){return id==R.id.firebase_auth_sandbox;}
    public static void attach(ViewGroup parent,NavController navigation){ViewOpenAuthSandboxBinding binding=ViewOpenAuthSandboxBinding.inflate(LayoutInflater.from(parent.getContext()),parent,true);binding.openAuthSandbox.setOnClickListener(v->navigation.navigate(R.id.firebase_auth_sandbox));}
}
