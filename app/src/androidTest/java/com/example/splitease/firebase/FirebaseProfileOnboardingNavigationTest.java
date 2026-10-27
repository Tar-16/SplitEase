package com.example.splitease.firebase;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.debug.FirebaseAuthSandbox;
import com.example.splitease.repository.RepositoryCallback;
import com.example.splitease.ui.MainActivity;
import com.google.android.gms.tasks.Tasks;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static com.example.splitease.firebase.AuthEmulatorSupport.*;

@RunWith(AndroidJUnit4.class)
public class FirebaseProfileOnboardingNavigationTest {
 private interface Condition{boolean ready(MainActivity activity);}
 private void waitFor(ActivityScenario<MainActivity> scenario,Condition condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(System.nanoTime()<deadline){AtomicBoolean ready=new AtomicBoolean();scenario.onActivity(activity->ready.set(condition.ready(activity)));if(ready.get())return;Thread.sleep(50);}fail("Profile onboarding UI timed out");}
 private void screenshot(SplitEaseApplication app,String name)throws Exception{InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(app.getCacheDir(),name))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}}
 @Test public void incompleteAccountCanCompleteProfileAfterRecreationWithoutAccessingLocalData()throws Exception{
  Assume.assumeTrue("Explicit demo profile emulators required","true".equals(InstrumentationRegistry.getArguments().getString("profilesEmulator")));requireEmulator();
  SplitEaseApplication app=ApplicationProvider.getApplicationContext();await((RepositoryCallback<Boolean> cb)->app.auth().logout(Runnable::run,cb));AuthService cloud=FirebaseAuthSandbox.service(app);await((RepositoryCallback<Boolean> cb)->cloud.logout(Runnable::run,cb));state(cloud,SessionState.Status.SIGNED_OUT);
  String suffix=UUID.randomUUID().toString().substring(0,8),username="Recovered_"+suffix;
  // Deliberately stop onboarding after Auth creation, as if the original process disappeared.
  Tasks.await(FirebaseAuthSandbox.sdk(app).createUserWithEmailAndPassword("pending-"+suffix+"@example.test",PASSWORD),20,TimeUnit.SECONDS);cloud.restore();state(cloud,SessionState.Status.PROFILE_REQUIRED);
  boolean keep="true".equals(InstrumentationRegistry.getArguments().getString("keepProfileFixture"));
  try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
   waitFor(screen,a->a.findViewById(R.id.open_auth_sandbox)!=null);screen.onActivity(a->a.findViewById(R.id.open_auth_sandbox).performClick());
   waitFor(screen,a->a.findViewById(R.id.submit)!=null&&((TextView)a.findViewById(R.id.submit)).getText().toString().equals(a.getString(R.string.cloud_complete_profile)));
   screen.recreate();waitFor(screen,a->a.findViewById(R.id.username)!=null&&a.findViewById(R.id.submit).isEnabled());
   screenshot(app,"profile-pending-light.png");
   screen.onActivity(a->{assertEquals(View.GONE,a.findViewById(R.id.password_layout).getVisibility());((EditText)a.findViewById(R.id.username)).setText("bad/name");a.findViewById(R.id.submit).performClick();});
   waitFor(screen,a->((TextView)a.findViewById(R.id.error)).getText().toString().contains("3–30"));
   screen.onActivity(a->{((EditText)a.findViewById(R.id.username)).setText(username);a.findViewById(R.id.submit).performClick();});state(cloud,SessionState.Status.SIGNED_IN);
   waitFor(screen,a->((TextView)a.findViewById(R.id.subtitle)).getText().toString().contains(username));
   screen.onActivity(a->a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));waitFor(screen,a->a.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE&&a.findViewById(R.id.subtitle)!=null&&((TextView)a.findViewById(R.id.subtitle)).getText().toString().contains(username));
   screen.onActivity(a->AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));waitFor(screen,a->(a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES&&a.findViewById(R.id.subtitle)!=null&&((TextView)a.findViewById(R.id.subtitle)).getText().toString().contains(username));
   screen.onActivity(a->{assertTrue(((TextView)a.findViewById(R.id.subtitle)).getText().toString().contains(username));assertEquals(View.GONE,a.findViewById(R.id.bottom_navigation).getVisibility());NavHostFragment host=(NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host);assertEquals(R.id.firebase_auth_sandbox,host.getNavController().getCurrentDestination().getId());});
   screenshot(app,"profile-ready-dark-landscape.png");
   assertEquals(SessionState.Backend.LOCAL,state(app.auth(),SessionState.Status.SIGNED_OUT).backend);
   if(keep){try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(app.getCacheDir(),"profile-session-fixture.json"))){out.write(new org.json.JSONObject().put("uid",FirebaseAuthSandbox.sdk(app).getUid()).put("username",username).put("pid",android.os.Process.myPid()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}

  }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));if(!keep)await((RepositoryCallback<Boolean> cb)->cloud.logout(Runnable::run,cb));}
 }
}
