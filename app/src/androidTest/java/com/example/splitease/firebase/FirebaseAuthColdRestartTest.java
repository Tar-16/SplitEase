package com.example.splitease.firebase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.SplitEaseApplication;
import com.example.splitease.auth.*;
import com.example.splitease.data.firebase.FirebaseSdkAuthBackend;
import com.example.splitease.debug.FirebaseAuthSandbox;
import com.example.splitease.repository.RepositoryCallback;
import com.google.firebase.auth.FirebaseAuth;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;
import static com.example.splitease.firebase.AuthEmulatorSupport.*;

@RunWith(AndroidJUnit4.class)
public class FirebaseAuthColdRestartTest {
 @Test public void sdkManagedSessionSurvivesForceStopWithoutLocalIdentityMixing()throws Exception{
  Assume.assumeTrue("Explicit restart fixture required","true".equals(InstrumentationRegistry.getArguments().getString("verifyFirebaseRestart")));requireEmulator();
  SplitEaseApplication app=ApplicationProvider.getApplicationContext();JSONObject fixture;
  try(InputStream in=new FileInputStream(new File(app.getCacheDir(),"firebase-auth-fixture.json"));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[1024];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);fixture=new JSONObject(out.toString("UTF-8"));}
  assertNotEquals(fixture.getInt("pid"),android.os.Process.myPid());
  assertNotNull("SDK-managed cached identity exists before adapter restore",FirebaseAuthSandbox.sdk(app).getCurrentUser());
  try(FirebaseAuthService auth=new FirebaseAuthService(new FirebaseSdkAuthBackend(FirebaseAuthSandbox.sdk(app)))){
   SessionState restored=state(auth,SessionState.Status.SIGNED_IN);assertEquals(SessionState.Backend.FIREBASE,restored.backend);assertEquals(fixture.getString("uid"),restored.user.id);assertEquals(fixture.getString("email"),restored.user.email);
   assertEquals(SessionState.Backend.LOCAL,state(app.auth(),SessionState.Status.SIGNED_OUT).backend);assertNull(FirebaseAuth.getInstance().getCurrentUser());
   await((RepositoryCallback<Boolean> cb)->auth.logout(Runnable::run,cb));
  }
 }
}
