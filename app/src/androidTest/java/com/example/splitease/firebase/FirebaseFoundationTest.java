package com.example.splitease.firebase;

import android.view.View;
import android.widget.TextView;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.auth.LocalAuthService;
import com.example.splitease.ui.MainActivity;
import com.example.splitease.debug.FirebaseDiagnostics;
import com.example.splitease.model.User;
import com.example.splitease.repository.RepositoryCallback;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** No Firebase account creation/sign-in or Firestore document operations in these tests. */
@RunWith(AndroidJUnit4.class)
public class FirebaseFoundationTest {
    @Test public void defaultSdkAutoInitializationMatchesConfigAndLocalAdaptersRemainActive() {
        SplitEaseApplication app=ApplicationProvider.getApplicationContext();
        FirebaseApp firebase=FirebaseApp.getInstance(); // Must exist before diagnostic getters run.
        assertEquals(app.getString(R.string.google_app_id),firebase.getOptions().getApplicationId());
        assertEquals(app.getString(R.string.project_id),firebase.getOptions().getProjectId());
        assertEquals("com.example.splitease",app.getPackageName());
        assertSame(firebase,FirebaseAuth.getInstance().getApp());
        assertSame(firebase,FirebaseFirestore.getInstance().getApp());
        assertNull("No cloud account was created",FirebaseAuth.getInstance().getCurrentUser());
        assertTrue(app.auth() instanceof LocalAuthService);
        assertTrue(app.users() instanceof com.example.splitease.data.SqliteRepositories);
        FirebaseDiagnostics.Result result=FirebaseDiagnostics.inspect(app);
        assertTrue(result.configured);assertTrue(result.sdkReady);assertEquals(0,result.httpStatus);
    }
    private interface Call<T>{void run(RepositoryCallback<T> callback);}
    private <T>T await(Call<T> call)throws Exception {
        CompletableFuture<T> future=new CompletableFuture<>();call.run(new RepositoryCallback<T>(){
            public void onSuccess(T value){future.complete(value);}public void onError(Exception error){future.completeExceptionally(error);}
        });return future.get(30,TimeUnit.SECONDS);
    }
    @Test public void debugProfileProbePreservesLocalIdentityAndDoesNotAuthenticateCloud()throws Exception {
        SplitEaseApplication app=ApplicationProvider.getApplicationContext();
        await((RepositoryCallback<Boolean> cb)->app.auth().logout(Runnable::run,cb));
        String name="foundation"+UUID.randomUUID().toString().substring(0,8);
        User user=await((RepositoryCallback<User> cb)->app.auth().register(name,name+"@example.com","Strong1!".toCharArray(),Runnable::run,cb));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            waitFor(scenario,activity->activity.findViewById(R.id.main_content).getVisibility()==View.VISIBLE&&activity.findViewById(R.id.bottom_navigation).getVisibility()==View.VISIBLE);
            scenario.onActivity(activity->((BottomNavigationView)activity.findViewById(R.id.bottom_navigation)).setSelectedItemId(R.id.profile));
            waitFor(scenario,activity->activity.findViewById(R.id.firebase_check)!=null&&((TextView)activity.findViewById(R.id.username)).getText().toString().equals(user.username));
            scenario.onActivity(activity->{activity.findViewById(R.id.firebase_check).performClick();assertFalse(activity.findViewById(R.id.firebase_check).isEnabled());});
            waitFor(scenario,activity->activity.findViewById(R.id.firebase_check).isEnabled());
            scenario.onActivity(activity->{
                String status=((TextView)activity.findViewById(R.id.firebase_status)).getText().toString();
                assertTrue(status,status.contains("SDKs initialized"));
                assertTrue(status,status.contains("not verified"));
                assertEquals(user.email,((TextView)activity.findViewById(R.id.email)).getText().toString());
                android.os.Bundle evidence=new android.os.Bundle();evidence.putString("firebase_diagnostic",status);InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
                View view=activity.findViewById(R.id.firebase_check);view.requestRectangleOnScreen(new android.graphics.Rect(0,0,view.getWidth(),view.getHeight()),true);
            });
            screenshot("firebase-debug-light");
            if("true".equals(InstrumentationRegistry.getArguments().getString("responsiveFirebase"))){
                scenario.onActivity(activity->activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                waitFor(scenario,activity->activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE&&activity.findViewById(R.id.firebase_check)!=null);
                scenario.onActivity(activity->androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES));
                waitFor(scenario,activity->(activity.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES&&activity.findViewById(R.id.firebase_check)!=null);
                scenario.onActivity(activity->{View status=activity.findViewById(R.id.firebase_status);status.requestRectangleOnScreen(new android.graphics.Rect(0,0,status.getWidth(),status.getHeight()),true);});
                screenshot("firebase-debug-landscape-dark");
            }
            assertNull(FirebaseAuth.getInstance().getCurrentUser());
            scenario.recreate();waitFor(scenario,activity->activity.findViewById(R.id.firebase_check)!=null);
            scenario.onActivity(activity->{NavHostFragment host=(NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);assertEquals(R.id.profile,host.getNavController().getCurrentDestination().getId());assertEquals(user.email,((TextView)activity.findViewById(R.id.email)).getText().toString());});
        } finally {InstrumentationRegistry.getInstrumentation().runOnMainSync(()->androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));await((RepositoryCallback<Boolean> cb)->app.auth().logout(Runnable::run,cb));}
    }
    private void screenshot(String name)throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(200);
        android.graphics.Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);
        android.content.Context app=ApplicationProvider.getApplicationContext();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(app.getExternalFilesDir("verification9a"),name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
    private interface Condition{boolean ready(MainActivity activity);}
    private void waitFor(ActivityScenario<MainActivity> scenario,Condition condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline){AtomicBoolean ready=new AtomicBoolean();scenario.onActivity(activity->ready.set(condition.ready(activity)));if(ready.get())return;Thread.sleep(50);}fail("Timed out waiting for Firebase diagnostic/local navigation");
    }
}
