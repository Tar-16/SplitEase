package com.example.splitease.firebase;

import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.debug.FirebaseAuthSandbox;
import com.example.splitease.repository.RepositoryCallback;
import com.example.splitease.ui.MainActivity;
import com.google.firebase.auth.FirebaseAuth;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;
import static com.example.splitease.firebase.AuthEmulatorSupport.*;

@RunWith(AndroidJUnit4.class)
public class FirebaseProfileColdRestartTest {
 @Test public void separateProcessRestoresSdkIdentityAndServerProfileWithoutLocalMixing()throws Exception{
  Assume.assumeTrue("Explicit previous-process profile fixture required","true".equals(InstrumentationRegistry.getArguments().getString("profilesRestart")));requireEmulator();
  SplitEaseApplication app=ApplicationProvider.getApplicationContext();File fixture=new File(app.getCacheDir(),"profile-session-fixture.json");JSONObject expected;
  try(InputStream input=new FileInputStream(fixture);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[1024];int n;while((n=input.read(bytes))!=-1)out.write(bytes,0,n);expected=new JSONObject(out.toString("UTF-8"));}
  assertNotEquals(expected.getInt("pid"),android.os.Process.myPid());assertNotNull(FirebaseAuthSandbox.sdk(app).getCurrentUser());AuthService cloud=FirebaseAuthSandbox.service(app);
  try{SessionState restored=state(cloud,SessionState.Status.SIGNED_IN);assertEquals(SessionState.Backend.FIREBASE,restored.backend);assertEquals(expected.getString("uid"),restored.user.id);assertEquals(expected.getString("username"),restored.user.username);assertEquals(SessionState.Backend.LOCAL,state(app.auth(),SessionState.Status.SIGNED_OUT).backend);assertNull(FirebaseAuth.getInstance().getCurrentUser());}
  finally{await((RepositoryCallback<Boolean> cb)->cloud.logout(Runnable::run,cb));assertTrue(fixture.delete());try(ActivityScenario<MainActivity> ignored=ActivityScenario.launch(MainActivity.class)){ignored.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);}}
 }
}
