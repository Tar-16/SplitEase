package com.example.splitease.firebase;
import android.view.*;
import android.widget.*;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.debug.FirebaseAuthSandbox;
import com.example.splitease.model.User;
import com.example.splitease.repository.RepositoryCallback;
import com.example.splitease.ui.MainActivity;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static com.example.splitease.firebase.AuthEmulatorSupport.*;

@RunWith(AndroidJUnit4.class)
public class FirebaseAuthSandboxNavigationTest {
 private interface Condition{boolean ready(MainActivity activity);}
 private void waitFor(ActivityScenario<MainActivity> scenario,Condition condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<deadline){AtomicBoolean ready=new AtomicBoolean();scenario.onActivity(activity->ready.set(condition.ready(activity)));if(ready.get())return;Thread.sleep(50);}java.util.concurrent.atomic.AtomicReference<String> details=new java.util.concurrent.atomic.AtomicReference<>();scenario.onActivity(activity->{TextView error=activity.findViewById(R.id.error),submit=activity.findViewById(R.id.submit);details.set("destination="+destination(activity)+", action="+(submit==null?"none":submit.getText())+", error="+(error==null?"none":error.getText()));});fail("Sandbox UI timeout: "+details.get());}
 private int destination(MainActivity activity){return ((NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host)).getNavController().getCurrentDestination().getId();}
 private void fill(MainActivity activity,int id,String value){((EditText)activity.findViewById(id)).setText(value);}
 @Test public void cloudSignupCannotExposeLocalGroupsAndLocalLoginStillWorks()throws Exception{
  Assume.assumeTrue("Explicit demo emulator required","true".equals(InstrumentationRegistry.getArguments().getString("authEmulator")));requireEmulator();
  SplitEaseApplication app=ApplicationProvider.getApplicationContext();await((RepositoryCallback<Boolean> cb)->app.auth().logout(Runnable::run,cb));AuthService cloud=FirebaseAuthSandbox.service(app);await((RepositoryCallback<Boolean> cb)->cloud.logout(Runnable::run,cb));
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   waitFor(scenario,activity->destination(activity)==R.id.login&&activity.findViewById(R.id.open_auth_sandbox)!=null);
   scenario.onActivity(activity->activity.findViewById(R.id.open_auth_sandbox).performClick());
   waitFor(scenario,activity->destination(activity)==R.id.firebase_auth_sandbox&&activity.findViewById(R.id.submit).isEnabled());
   scenario.onActivity(activity->{fill(activity,R.id.username,"Cloud_ui_test");fill(activity,R.id.email,"ui-test@example.test");fill(activity,R.id.password,PASSWORD);fill(activity,R.id.confirm_password,"Mismatch1!");activity.findViewById(R.id.submit).performClick();assertTrue(((TextView)activity.findViewById(R.id.error)).getText().toString().contains("match"));fill(activity,R.id.password,PASSWORD);fill(activity,R.id.confirm_password,PASSWORD);activity.findViewById(R.id.submit).performClick();});
   waitFor(scenario,activity->((TextView)activity.findViewById(R.id.submit)).getText().toString().equals(activity.getString(R.string.logout_action))||((TextView)activity.findViewById(R.id.error)).getText().toString().contains("already exists"));
   scenario.onActivity(activity->{if(!((TextView)activity.findViewById(R.id.submit)).getText().toString().equals(activity.getString(R.string.logout_action))){activity.findViewById(R.id.switch_screen).performClick();fill(activity,R.id.password,PASSWORD);activity.findViewById(R.id.submit).performClick();}});
   waitFor(scenario,activity->{String action=((TextView)activity.findViewById(R.id.submit)).getText().toString();return action.equals(activity.getString(R.string.logout_action))||action.equals(activity.getString(R.string.cloud_complete_profile));});
   scenario.onActivity(activity->{if(((TextView)activity.findViewById(R.id.submit)).getText().toString().equals(activity.getString(R.string.cloud_complete_profile))){fill(activity,R.id.username,"Cloud_ui_test");activity.findViewById(R.id.submit).performClick();}});
   waitFor(scenario,activity->((TextView)activity.findViewById(R.id.submit)).getText().toString().equals(activity.getString(R.string.logout_action)));
   assertEquals(SessionState.Backend.LOCAL,state(app.auth(),SessionState.Status.SIGNED_OUT).backend);assertNotNull(FirebaseAuthSandbox.sdk(app).getCurrentUser());assertNull(FirebaseAuth.getInstance().getCurrentUser());
   scenario.recreate();waitFor(scenario,activity->destination(activity)==R.id.firebase_auth_sandbox&&((TextView)activity.findViewById(R.id.submit)).getText().toString().equals(activity.getString(R.string.logout_action)));
   scenario.onActivity(activity->{assertEquals(View.GONE,activity.findViewById(R.id.bottom_navigation).getVisibility());NavHostFragment host=(NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);host.getNavController().navigate(R.id.groups);});
   waitFor(scenario,activity->destination(activity)==R.id.login);
   error(AuthException.Code.INVALID_CREDENTIALS,(RepositoryCallback<User> cb)->app.auth().login("ui-test@example.test",PASSWORD.toCharArray(),Runnable::run,cb));
   await((RepositoryCallback<Boolean> cb)->cloud.logout(Runnable::run,cb));
  }finally{await((RepositoryCallback<Boolean> cb)->cloud.logout(Runnable::run,cb));}
 }
}
