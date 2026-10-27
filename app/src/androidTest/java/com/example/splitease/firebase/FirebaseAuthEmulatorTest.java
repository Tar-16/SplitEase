package com.example.splitease.firebase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.data.firebase.FirebaseSdkAuthBackend;
import com.example.splitease.debug.FirebaseAuthSandbox;
import com.example.splitease.model.User;
import com.example.splitease.repository.RepositoryCallback;
import com.google.firebase.auth.FirebaseAuth;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;
import static com.example.splitease.firebase.AuthEmulatorSupport.*;

@RunWith(AndroidJUnit4.class)
public class FirebaseAuthEmulatorTest {
 @Test public void realSdkRegistrationLoginResetRestorationAndInvalidSessions()throws Exception {
  Assume.assumeTrue("Explicit local Auth emulator required","true".equals(InstrumentationRegistry.getArguments().getString("authEmulator")));requireEmulator();
  SplitEaseApplication app=ApplicationProvider.getApplicationContext();FirebaseAuth sdk=FirebaseAuthSandbox.sdk(app);sdk.signOut();String uid;
  try(FirebaseAuthService auth=new FirebaseAuthService(new FirebaseSdkAuthBackend(sdk))){
   state(auth,SessionState.Status.SIGNED_OUT);
   User created;
   try{created=await((RepositoryCallback<User> cb)->auth.register("Unreserved username",EMAIL,PASSWORD.toCharArray(),Runnable::run,cb));}
   catch(java.util.concurrent.ExecutionException existing){assertEquals(AuthException.Code.DUPLICATE_EMAIL,((AuthException)existing.getCause()).code);try{created=await((RepositoryCallback<User> cb)->auth.login(EMAIL,PASSWORD.toCharArray(),Runnable::run,cb));}catch(java.util.concurrent.ExecutionException changed){created=await((RepositoryCallback<User> cb)->auth.login(EMAIL,CHANGED.toCharArray(),Runnable::run,cb));}}
   assertEquals(sdk.getCurrentUser().getUid(),created.id);assertFalse(created.id.matches("[0-9a-f]{8}-[0-9a-f-]{27}"));assertEquals(SessionState.Backend.FIREBASE,state(auth,SessionState.Status.SIGNED_IN).backend);assertTrue(created.username.isEmpty());
   await((RepositoryCallback<Boolean> cb)->auth.logout(Runnable::run,cb));assertNull(sdk.getCurrentUser());
   error(AuthException.Code.DUPLICATE_EMAIL,(RepositoryCallback<User> cb)->auth.register("Still unreserved",EMAIL,PASSWORD.toCharArray(),Runnable::run,cb));
   error(AuthException.Code.INVALID_CREDENTIALS,(RepositoryCallback<User> cb)->auth.login(EMAIL,"Incorrect1!".toCharArray(),Runnable::run,cb));
   await((RepositoryCallback<Boolean> cb)->auth.resetPassword(EMAIL,Runnable::run,cb));finishReset(EMAIL,CHANGED);
   error(AuthException.Code.INVALID_CREDENTIALS,(RepositoryCallback<User> cb)->auth.login(EMAIL,PASSWORD.toCharArray(),Runnable::run,cb));
   User logged=await((RepositoryCallback<User> cb)->auth.login(EMAIL,CHANGED.toCharArray(),Runnable::run,cb));assertEquals(created.id,logged.id);
   uid=created.id;
  }
  try(FirebaseAuthService auth=new FirebaseAuthService(new FirebaseSdkAuthBackend(sdk))){
   assertEquals(uid,state(auth,SessionState.Status.SIGNED_IN).user.id);
   // Admin emulator deletion leaves the SDK cache initially present; restore must reject it.
   request("POST","/identitytoolkit.googleapis.com/v1/accounts:delete?key=demo-api-key",new JSONObject().put("localId",uid));auth.restore();state(auth,SessionState.Status.SIGNED_OUT);assertNull(sdk.getCurrentUser());
   await((RepositoryCallback<Boolean> cb)->auth.resetPassword("missing-account@example.test",Runnable::run,cb));
   User fixture=await((RepositoryCallback<User> cb)->auth.register("Fixture only",EMAIL,PASSWORD.toCharArray(),Runnable::run,cb));
   assertTrue(app.auth() instanceof LocalAuthService);assertNull(FirebaseAuth.getInstance().getCurrentUser());
   if("true".equals(InstrumentationRegistry.getArguments().getString("keepFirebaseFixture"))){
    try(FileOutputStream out=new FileOutputStream(new File(app.getCacheDir(),"firebase-auth-fixture.json"))){out.write(new JSONObject().put("uid",fixture.id).put("email",fixture.email).put("pid",android.os.Process.myPid()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    assertNotNull(sdk.getCurrentUser());
    // Exercise a normal Activity stop, allowing the SDK's asynchronous durable writes to flush.
    try(androidx.test.core.app.ActivityScenario<com.example.splitease.ui.MainActivity> screen=androidx.test.core.app.ActivityScenario.launch(com.example.splitease.ui.MainActivity.class)){screen.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);}
   }else await((RepositoryCallback<Boolean> cb)->auth.logout(Runnable::run,cb));
  }
 }
}
