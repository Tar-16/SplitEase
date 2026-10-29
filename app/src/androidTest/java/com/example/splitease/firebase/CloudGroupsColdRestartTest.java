package com.example.splitease.firebase;

import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.debug.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.example.splitease.ui.MainActivity;
import com.google.firebase.auth.FirebaseAuth;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static com.example.splitease.firebase.AuthEmulatorSupport.*;

@RunWith(AndroidJUnit4.class)
public class CloudGroupsColdRestartTest {
 @Test public void restoredCloudSessionLoadsSharedGroupFromServerAndKeepsSqliteSeparate()throws Exception{
  Assume.assumeTrue("Explicit previous-process group fixture required","true".equals(InstrumentationRegistry.getArguments().getString("cloudGroupsRestart")));requireEmulator();SplitEaseApplication app=ApplicationProvider.getApplicationContext();File file=new File(app.getCacheDir(),"cloud-group-fixture.json");JSONObject expected;
  try(InputStream input=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[1024];int n;while((n=input.read(bytes))!=-1)out.write(bytes,0,n);expected=new JSONObject(out.toString("UTF-8"));}
  assertNotEquals(expected.getInt("pid"),android.os.Process.myPid());assertNotNull(FirebaseAuthSandbox.sdk(app).getCurrentUser());AuthService auth=FirebaseAuthSandbox.service(app);assertEquals(expected.getString("uid"),state(auth,SessionState.Status.SIGNED_IN).user.id);GroupRepository groups=CloudGroups.owner(app).groups;state(auth,SessionState.Status.SIGNED_IN);
  CompletableFuture<GroupDetails> loaded=new CompletableFuture<>();ObservableQuery.Subscription sub=groups.observeGroupDetails(expected.getString("uid"),expected.getString("groupId")).subscribe(Runnable::run,new ObservableQuery.Observer<GroupDetails>(){public void onChanged(GroupDetails group){loaded.complete(group);}public void onError(Exception failure){loaded.completeExceptionally(failure);}});
  try{GroupDetails group=loaded.get(20,TimeUnit.SECONDS);assertEquals(expected.getString("uid"),group.group.adminId);assertEquals(3,group.members.size());assertFalse(group.canLeave);assertFalse(group.accountingAvailable);assertEquals(SessionState.Backend.LOCAL,state(app.auth(),SessionState.Status.SIGNED_OUT).backend);assertNull(FirebaseAuth.getInstance().getCurrentUser());}
  finally{sub.close();await((RepositoryCallback<Boolean> cb)->auth.logout(Runnable::run,cb));assertTrue(file.delete());try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){screen.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);}}
 }
}
