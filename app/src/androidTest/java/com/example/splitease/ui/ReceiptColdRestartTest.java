package com.example.splitease.ui;
import android.view.View;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.example.splitease.ui.receipts.ReceiptViewerViewModel;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Explicit two-process proof. Requires keepReceiptFixture preparation and external force-stop. */
@RunWith(AndroidJUnit4.class)
public class ReceiptColdRestartTest {
 public static String sha256(byte[] bytes)throws Exception{StringBuilder result=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
 private interface Call<T>{void run(RepositoryCallback<T> cb);}
 private <T>T await(Call<T> call)throws Exception{CompletableFuture<T> future=new CompletableFuture<>();call.run(new RepositoryCallback<T>(){public void onSuccess(T value){future.complete(value);}public void onError(Exception error){future.completeExceptionally(error);}});return future.get(30,TimeUnit.SECONDS);}
 @Test public void restoredReceiptBytesMetadataAndViewerSurviveProcessRestart()throws Exception {
  Assume.assumeTrue("Explicit receipt restart fixture required","true".equals(InstrumentationRegistry.getArguments().getString("verifyReceiptRestart")));
  SplitEaseApplication app=ApplicationProvider.getApplicationContext();org.json.JSONObject fixture;
  try(InputStream input=new FileInputStream(new File(app.getExternalFilesDir("verification8"),"fixture.json"));ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);fixture=new org.json.JSONObject(output.toString("UTF-8"));}
  assertNotEquals(fixture.getInt("pid"),android.os.Process.myPid());CompletableFuture<String> restored=new CompletableFuture<>();ObservableQuery.Subscription session=app.auth().sessions().subscribe(Runnable::run,new ObservableQuery.Observer<SessionState>(){public void onChanged(SessionState value){if(value.status==SessionState.Status.SIGNED_IN)restored.complete(value.user.id);else if(value.status==SessionState.Status.SIGNED_OUT||value.status==SessionState.Status.UNAVAILABLE)restored.completeExceptionally(new AssertionError("Session missing"));}public void onError(Exception error){restored.completeExceptionally(error);}});String actor;try{actor=restored.get(20,TimeUnit.SECONDS);}finally{session.close();}assertEquals(fixture.getString("actor"),actor);
  ReceiptMetadata metadata=await((RepositoryCallback<Optional<ReceiptMetadata>> cb)->app.receipts().findForExpense(actor,fixture.optString("expense"),Runnable::run,cb)).get();assertEquals(fixture.getString("receipt"),metadata.id);assertEquals(fixture.getString("group"),metadata.groupId);ReceiptImage image=await((RepositoryCallback<ReceiptImage> cb)->app.receipts().load(actor,metadata.id,Runnable::run,cb));assertEquals(fixture.getLong("bytes"),image.bytes().length);assertEquals(fixture.getString("sha256"),sha256(image.bytes()));
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean ready=false;
   while(System.nanoTime()<until){AtomicBoolean flag=new AtomicBoolean();scenario.onActivity(activity->{NavHostFragment host=(NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);flag.set(host.getNavController().getCurrentDestination().getId()==R.id.groups&&activity.findViewById(R.id.main_content).getVisibility()==View.VISIBLE);});if(flag.get()){ready=true;break;}Thread.sleep(50);}assertTrue(ready);
   scenario.onActivity(activity->{android.os.Bundle args=new android.os.Bundle();args.putString("receiptId",metadata.id);((NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host)).getNavController().navigate(R.id.receipt_viewer,args);});until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);ready=false;
   while(System.nanoTime()<until){AtomicBoolean flag=new AtomicBoolean();scenario.onActivity(activity->{NavHostFragment host=(NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);ReceiptViewerViewModel.State state=new ViewModelProvider(host.getChildFragmentManager().getPrimaryNavigationFragment()).get(ReceiptViewerViewModel.class).state().getValue();flag.set(state!=null&&state.image!=null&&!state.failed);});if(flag.get()){ready=true;break;}Thread.sleep(50);}assertTrue("Stored image rendered after restart",ready);
  }
 }
}
