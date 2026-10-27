package com.example.splitease.debug;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import com.example.splitease.R;
import com.example.splitease.databinding.ViewFirebaseDiagnosticsBinding;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Debug-only setup/reachability check. Never signs in or issues a Firestore document/query RPC. */
public final class FirebaseDiagnostics {
    public static final class Result {
        public final boolean configured, sdkReady;
        public final String projectId;
        /** An HTTPS response proves endpoint reachability only, not project/auth/rules access. */
        public final int httpStatus;
        private Result(boolean configured, boolean sdkReady, String projectId, int httpStatus) {
            this.configured=configured; this.sdkReady=sdkReady; this.projectId=projectId; this.httpStatus=httpStatus;
        }
    }
    private FirebaseDiagnostics() {}

    /** Local getters only. FirebaseInitProvider must already have initialized the default app. */
    public static Result inspect(Context context) {
        try {
            FirebaseApp app=FirebaseApp.getInstance();
            FirebaseOptions expected=FirebaseOptions.fromResource(context);
            boolean configured="com.example.splitease".equals(context.getPackageName())
                    && expected!=null && expected.equals(app.getOptions())
                    && app.getOptions().getProjectId()!=null;
            boolean ready=FirebaseAuth.getInstance(app).getApp()==app
                    && FirebaseFirestore.getInstance(app).getApp()==app;
            return new Result(configured,ready,app.getOptions().getProjectId(),0);
        } catch (RuntimeException unavailable) {
            return new Result(false,false,null,0);
        }
    }

    /** Worker thread only. One unauthenticated HEAD to a fixed host; no API key or project/user data. */
    public static Result check(Context context) {
        Result local=inspect(context);
        if(!local.configured || !local.sdkReady) return local;
        HttpsURLConnection connection=null;
        int status=0;
        try {
            connection=(HttpsURLConnection)new URL("https://firestore.googleapis.com/").openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            status=connection.getResponseCode();
        } catch (IOException | RuntimeException unavailable) {
            // No raw exceptions, credentials, options or response bodies in logs/UI.
        } finally {
            if(connection!=null) connection.disconnect();
        }
        return new Result(local.configured,local.sdkReady,local.projectId,status);
    }

    public static void attach(ViewGroup parent, LifecycleOwner owner) {
        ViewFirebaseDiagnosticsBinding binding=ViewFirebaseDiagnosticsBinding.inflate(LayoutInflater.from(parent.getContext()),parent,true);
        Context appContext=parent.getContext().getApplicationContext();
        ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"firebase-diagnostic"));
        MutableLiveData<Result> result=new MutableLiveData<>();
        result.observe(owner,value->{
            binding.firebaseCheck.setEnabled(true);
            if(!value.configured || !value.sdkReady) binding.firebaseStatus.setText(R.string.firebase_diagnostic_unavailable);
            else if(value.httpStatus>0) binding.firebaseStatus.setText(parent.getContext().getString(R.string.firebase_diagnostic_reachable,value.projectId,value.httpStatus));
            else binding.firebaseStatus.setText(parent.getContext().getString(R.string.firebase_diagnostic_offline,value.projectId));
        });
        binding.firebaseCheck.setOnClickListener(v->{
            binding.firebaseCheck.setEnabled(false); binding.firebaseStatus.setText(R.string.firebase_diagnostic_running);
            worker.execute(()->result.postValue(check(appContext)));
        });
        owner.getLifecycle().addObserver(new DefaultLifecycleObserver(){
            @Override public void onDestroy(LifecycleOwner ignored){worker.shutdownNow();}
        });
    }
}
