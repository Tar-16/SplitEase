package com.example.splitease.ui;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.model.*;
import com.example.splitease.platform.receipts.ReceiptImagingTest;
import com.example.splitease.repository.*;
import com.example.splitease.ui.expenses.ExpenseViewModel;
import com.example.splitease.ui.receipts.ReceiptViewerViewModel;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class) @SdkSuppress(minSdkVersion=26)
public class ReceiptNavigationTest {
 private SplitEaseApplication app;private AuthService auth;private User owner,member;private String group;private boolean keepSession;
 private interface Call<T>{void run(RepositoryCallback<T> callback);}
 private <T>T await(Call<T> call)throws Exception{CompletableFuture<T> result=new CompletableFuture<>();call.run(new RepositoryCallback<T>(){public void onSuccess(T value){result.complete(value);}public void onError(Exception error){result.completeExceptionally(error);}});return result.get(30,TimeUnit.SECONDS);}
 private <T>T snapshot(ObservableQuery<T> query)throws Exception{CompletableFuture<T> result=new CompletableFuture<>();ObservableQuery.Subscription watch=query.subscribe(Runnable::run,new ObservableQuery.Observer<T>(){public void onChanged(T value){result.complete(value);}public void onError(Exception failure){result.completeExceptionally(failure);}});try{return result.get(30,TimeUnit.SECONDS);}finally{watch.close();}}
 private void logout()throws Exception{await((RepositoryCallback<Boolean> cb)->auth.logout(Runnable::run,cb));}
 private User register(String prefix)throws Exception{String name=prefix+UUID.randomUUID().toString().substring(0,8);User value=await((RepositoryCallback<User> cb)->auth.register(name,name+"@example.com","Strong1!".toCharArray(),Runnable::run,cb));logout();return value;}
 @Before public void setup()throws Exception{app=ApplicationProvider.getApplicationContext();auth=app.auth();logout();owner=register("receiptowner");member=register("receiptmember");group=UUID.randomUUID().toString();await((RepositoryCallback<Group> cb)->app.groups().createGroup(owner.id,new Group(group,"Receipt test",Group.Type.FLAT,owner.id,System.currentTimeMillis()),Arrays.asList(member.id),Runnable::run,cb));await((RepositoryCallback<User> cb)->auth.login(owner.email,"Strong1!".toCharArray(),Runnable::run,cb));}
 @After public void cleanup()throws Exception{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));if("true".equals(InstrumentationRegistry.getArguments().getString("keepReceiptFixture"))){File fixtureFile=new File(app.getExternalFilesDir("verification8"),"fixture.json");if(fixtureFile.isFile()){org.json.JSONObject fixture;try(InputStream input=new FileInputStream(fixtureFile);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] bytes=new byte[4096];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);fixture=new org.json.JSONObject(output.toString("UTF-8"));}User user=await((RepositoryCallback<User> cb)->app.users().getUser(fixture.optString("actor"),Runnable::run,cb));logout();await((RepositoryCallback<User> cb)->auth.login(user.email,"Strong1!".toCharArray(),Runnable::run,cb));}else if(!keepSession)logout();}else if(!keepSession)logout();}
 private NavHostFragment host(MainActivity activity){return (NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);}
 private ExpenseViewModel model(MainActivity activity){return new ViewModelProvider(host(activity).getChildFragmentManager().getPrimaryNavigationFragment()).get(ExpenseViewModel.class);}
 private void waitFor(ActivityScenario<MainActivity> scenario,Predicate<MainActivity> condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);while(System.nanoTime()<until){AtomicBoolean done=new AtomicBoolean();scenario.onActivity(activity->done.set(condition.test(activity)));if(done.get())return;Thread.sleep(60);}fail("Timed out waiting for receipt UI");}
 private void destination(ActivityScenario<MainActivity> scenario,int destination)throws Exception{waitFor(scenario,activity->host(activity).getNavController().getCurrentDestination().getId()==destination&&activity.findViewById(R.id.main_content).getVisibility()==View.VISIBLE);}
 private void open(ActivityScenario<MainActivity> scenario,String id)throws Exception{destination(scenario,R.id.groups);scenario.onActivity(activity->{Bundle args=new Bundle();args.putString("groupId",group);args.putString("expenseId",id);host(activity).getNavController().navigate(R.id.expense_screen,args);});destination(scenario,R.id.expense_screen);waitFor(scenario,activity->activity.findViewById(R.id.form).getVisibility()==View.VISIBLE&&!model(activity).state().getValue().busy);}
 private void text(MainActivity activity,int id,String value){((EditText)activity.findViewById(id)).setText(value);}
 private void confirm(String label)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label))if(node.isClickable()&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;Thread.sleep(60);}fail("Missing confirmation "+label);}
 private void grantCamera()throws Exception{try(ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("pm grant com.example.splitease android.permission.CAMERA");InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] buffer=new byte[1024];while(input.read(buffer)!=-1){}}}
 private final class Camera extends Instrumentation.ActivityMonitor {
  final AtomicReference<byte[]> photo=new AtomicReference<>();final AtomicBoolean cancelled=new AtomicBoolean();final AtomicInteger launches=new AtomicInteger();Uri last;
  @Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(!MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction()))return null;launches.incrementAndGet();last=intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);assertNotNull(last);assertEquals("content",last.getScheme());
   if(cancelled.get())return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);
   try(OutputStream output=app.getContentResolver().openOutputStream(last)){assertNotNull(output);output.write(photo.get());}catch(IOException error){throw new AssertionError(error);}return new Instrumentation.ActivityResult(Activity.RESULT_OK,null);
  }
 }
 private void shot(String name)throws Exception{InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(250);Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);try(FileOutputStream output=new FileOutputStream(new File(app.getExternalFilesDir("verification8"),name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,output);}finally{image.recycle();}}
 @Test public void cameraDeniedOrCancelledKeepsManualEntryAndCreatesNoExpense()throws Exception {
  Camera camera=new Camera();camera.cancelled.set(true);Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.addMonitor(camera);
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   open(scenario,null);scenario.onActivity(activity->{assertFalse(activity.findViewById(R.id.receipt_use_amount).isShown());model(activity).cameraDenied();});waitFor(scenario,activity->((TextView)activity.findViewById(R.id.receipt_message)).getText().toString().contains("denied"));assertTrue(snapshot(app.expenses().observeExpenses(group)).isEmpty());
   grantCamera();scenario.onActivity(activity->activity.findViewById(R.id.scan_receipt).performClick());
   waitFor(scenario,activity->((TextView)activity.findViewById(R.id.receipt_message)).getText().toString().contains("cancelled")&&!model(activity).state().getValue().busy);assertEquals(1,camera.launches.get());assertTrue(snapshot(app.expenses().observeExpenses(group)).isEmpty());
   scenario.onActivity(activity->{assertTrue(activity.findViewById(R.id.expense_amount).isEnabled());assertTrue(activity.findViewById(R.id.save).isEnabled());});
   assertFalse(new File(app.getCacheDir(),"receipt-captures/"+camera.last.getLastPathSegment()).exists());
  }finally{instrumentation.removeMonitor(camera);}
 }
 @Test public void cancelledProcessingCannotPublishALateReceipt()throws Exception {
  grantCamera();Camera camera=new Camera();camera.photo.set(ReceiptImagingTest.receipt("999.00",8000));Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.addMonitor(camera);
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   open(scenario,null);scenario.onActivity(activity->activity.findViewById(R.id.scan_receipt).performClick());waitFor(scenario,activity->model(activity).state().getValue().capturePending);scenario.onActivity(activity->activity.findViewById(R.id.receipt_cancel_capture).performClick());
   CompletableFuture<com.example.splitease.platform.receipts.ReceiptCaptureService.Target> barrier=new CompletableFuture<>();app.receiptCapture().prepare(Runnable::run,new RepositoryCallback<com.example.splitease.platform.receipts.ReceiptCaptureService.Target>(){public void onSuccess(com.example.splitease.platform.receipts.ReceiptCaptureService.Target target){barrier.complete(target);}public void onError(Exception error){barrier.completeExceptionally(error);}});com.example.splitease.platform.receipts.ReceiptCaptureService.Target target=barrier.get(45,TimeUnit.SECONDS);app.receiptCapture().cancel(target.id);instrumentation.waitForIdleSync();
   scenario.onActivity(activity->{assertFalse(model(activity).state().getValue().busy);assertTrue(model(activity).visibleReceiptId().isEmpty());assertNull(model(activity).state().getValue().receiptPreview);assertTrue(activity.findViewById(R.id.expense_amount).isEnabled());});assertTrue(snapshot(app.expenses().observeExpenses(group)).isEmpty());
  }finally{instrumentation.removeMonitor(camera);}
 }
 @Test public void scanReviewCorrectSaveViewReplaceRemoveAndDelete()throws Exception {
  grantCamera();Camera camera=new Camera();camera.photo.set(ReceiptImagingTest.receipt("1299.99",1800));Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.addMonitor(camera);
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   open(scenario,null);scenario.onActivity(activity->{text(activity,R.id.expense_title,"Receipt dinner");text(activity,R.id.expense_amount,"25.00");for(int i=0;i<2;i++)((ViewGroup)activity.findViewById(R.id.participants)).getChildAt(i).performClick();activity.findViewById(R.id.scan_receipt).performClick();});
   waitFor(scenario,activity->model(activity).state().getValue().detectedPaise!=null&&!model(activity).state().getValue().busy);
   scenario.onActivity(activity->{assertEquals(Long.valueOf(129999),model(activity).state().getValue().detectedPaise);assertEquals("25.00",((EditText)activity.findViewById(R.id.expense_amount)).getText().toString());assertNotNull(model(activity).state().getValue().receiptPreview);assertNotNull(model(activity).state().getValue().receiptEvidence);assertNotNull(model(activity).state().getValue().receiptDebug);activity.findViewById(R.id.receipt_debug_toggle).performClick();assertEquals(View.VISIBLE,activity.findViewById(R.id.receipt_debug).getVisibility());assertTrue(((TextView)activity.findViewById(R.id.receipt_debug)).getText().toString().contains("CANDIDATE"));activity.findViewById(R.id.receipt_debug_toggle).performClick();});assertTrue(snapshot(app.expenses().observeExpenses(group)).isEmpty());
   // Keep the scan result in frame for visual review in both themes.
   scenario.onActivity(activity->{View result=activity.findViewById(R.id.receipt_detected_result);result.requestRectangleOnScreen(new android.graphics.Rect(0,0,result.getWidth(),result.getHeight()),true);});shot("detected-review");
   scenario.onActivity(activity->AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
   waitFor(scenario,activity->(activity.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES&&activity.findViewById(R.id.receipt_detected_result)!=null&&activity.findViewById(R.id.receipt_detected_result).getVisibility()==View.VISIBLE);
   scenario.onActivity(activity->{View result=activity.findViewById(R.id.receipt_detected_result);result.requestRectangleOnScreen(new android.graphics.Rect(0,0,result.getWidth(),result.getHeight()),true);});shot("detected-review-dark");
   scenario.onActivity(activity->{View tick=activity.findViewById(R.id.receipt_use_amount);assertEquals("Confirm detected amount",tick.getContentDescription().toString());tick.performClick();assertEquals("1299.99",((EditText)activity.findViewById(R.id.expense_amount)).getText().toString());assertTrue(model(activity).state().getValue().receiptAmountApplied);assertEquals("Amount applied",tick.getContentDescription().toString());assertEquals(View.VISIBLE,activity.findViewById(R.id.receipt_amount_applied).getVisibility());tick.performClick();assertEquals(Long.valueOf(129999),model(activity).state().getValue().detectedPaise);View result=activity.findViewById(R.id.receipt_detected_result);result.requestRectangleOnScreen(new android.graphics.Rect(0,0,result.getWidth(),result.getHeight()),true);});assertTrue(snapshot(app.expenses().observeExpenses(group)).isEmpty());shot("detected-applied-dark");
   scenario.onActivity(activity->AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO));
   waitFor(scenario,activity->(activity.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_NO&&activity.findViewById(R.id.receipt_detected_result)!=null);
   scenario.recreate();waitFor(scenario,activity->model(activity).state().getValue().receiptPreview!=null&&!model(activity).state().getValue().busy);
   scenario.onActivity(activity->{assertTrue(model(activity).state().getValue().receiptAmountApplied);View result=activity.findViewById(R.id.receipt_detected_result);result.requestRectangleOnScreen(new android.graphics.Rect(0,0,result.getWidth(),result.getHeight()),true);});shot("detected-applied-light");
   scenario.onActivity(activity->{activity.findViewById(R.id.receipt_use_amount).performClick();assertEquals("1299.99",((EditText)activity.findViewById(R.id.expense_amount)).getText().toString());text(activity,R.id.expense_amount,"1300.01");assertFalse(model(activity).state().getValue().receiptAmountApplied);assertEquals(View.GONE,activity.findViewById(R.id.receipt_amount_applied).getVisibility());assertEquals("Confirm detected amount",activity.findViewById(R.id.receipt_use_amount).getContentDescription().toString());assertTrue(((TextView)activity.findViewById(R.id.receipt_detected_amount)).getText().toString().contains("1,299.99"));text(activity,R.id.expense_amount,"1299.99");assertFalse(model(activity).state().getValue().receiptAmountApplied);activity.findViewById(R.id.receipt_use_amount).performClick();assertTrue(model(activity).state().getValue().receiptAmountApplied);text(activity,R.id.expense_amount,"1300.01");activity.findViewById(R.id.save).performClick();});destination(scenario,R.id.groups);
   Expense expense=snapshot(app.expenses().observeExpenses(group)).get(0);assertEquals(130001,expense.amountPaise);assertEquals(130001,Math.addExact(expense.splits.get(0).sharePaise,expense.splits.get(1).sharePaise));
   String first=await((RepositoryCallback<Optional<ReceiptMetadata>> cb)->app.receipts().findForExpense(owner.id,expense.id,Runnable::run,cb)).get().id;assertNotNull(await((RepositoryCallback<ReceiptImage> cb)->app.receipts().load(member.id,first,Runnable::run,cb)));
   open(scenario,expense.id);waitFor(scenario,activity->model(activity).state().getValue().receiptPreview!=null);scenario.onActivity(activity->activity.findViewById(R.id.receipt_view).performClick());destination(scenario,R.id.receipt_viewer);
   waitFor(scenario,activity->new ViewModelProvider(host(activity).getChildFragmentManager().getPrimaryNavigationFragment()).get(ReceiptViewerViewModel.class).state().getValue().image!=null);shot("viewer-light");scenario.onActivity(activity->{activity.findViewById(R.id.zoom_in).performClick();activity.findViewById(R.id.zoom_out).performClick();activity.findViewById(R.id.back).performClick();});destination(scenario,R.id.expense_screen);
   scenario.onActivity(activity->activity.findViewById(R.id.edit).performClick());camera.photo.set(ReceiptImagingTest.receipt("799.50",1800));scenario.onActivity(activity->activity.findViewById(R.id.scan_receipt).performClick());waitFor(scenario,activity->Long.valueOf(79950).equals(model(activity).state().getValue().detectedPaise)&&!model(activity).state().getValue().busy);scenario.onActivity(activity->{assertEquals("1300.01",((EditText)activity.findViewById(R.id.expense_amount)).getText().toString());activity.findViewById(R.id.receipt_use_amount).performClick();activity.findViewById(R.id.save).performClick();});destination(scenario,R.id.groups);
   assertThrows(ExecutionException.class,()->await((RepositoryCallback<ReceiptImage> cb)->app.receipts().load(owner.id,first,Runnable::run,cb)));Expense changed=snapshot(app.expenses().observeExpenses(group)).get(0);assertEquals(79950,changed.amountPaise);
   open(scenario,expense.id);waitFor(scenario,activity->model(activity).state().getValue().receiptPreview!=null);scenario.onActivity(activity->{activity.findViewById(R.id.edit).performClick();activity.findViewById(R.id.receipt_remove).performClick();});confirm("Remove receipt");assertTrue(await((RepositoryCallback<Optional<ReceiptMetadata>> cb)->app.receipts().findForExpense(owner.id,expense.id,Runnable::run,cb)).isPresent());scenario.onActivity(activity->activity.findViewById(R.id.save).performClick());destination(scenario,R.id.groups);assertFalse(await((RepositoryCallback<Optional<ReceiptMetadata>> cb)->app.receipts().findForExpense(owner.id,expense.id,Runnable::run,cb)).isPresent());
   open(scenario,expense.id);scenario.onActivity(activity->{activity.findViewById(R.id.edit).performClick();activity.findViewById(R.id.scan_receipt).performClick();});waitFor(scenario,activity->!model(activity).state().getValue().receiptId.isEmpty()&&!model(activity).state().getValue().busy);scenario.onActivity(activity->activity.findViewById(R.id.save).performClick());destination(scenario,R.id.groups);
   ReceiptMetadata finalReceipt=await((RepositoryCallback<Optional<ReceiptMetadata>> cb)->app.receipts().findForExpense(owner.id,expense.id,Runnable::run,cb)).get();open(scenario,expense.id);scenario.onActivity(activity->activity.findViewById(R.id.delete).performClick());confirm("Delete expense");destination(scenario,R.id.groups);assertTrue(snapshot(app.expenses().observeExpenses(group)).isEmpty());assertThrows(ExecutionException.class,()->await((RepositoryCallback<ReceiptImage> cb)->app.receipts().load(owner.id,finalReceipt.id,Runnable::run,cb)));
   if("true".equals(InstrumentationRegistry.getArguments().getString("keepReceiptFixture"))){
    ReceiptMetadata stage=await((RepositoryCallback<ReceiptMetadata> cb)->app.receipts().stage(owner.id,group,camera.photo.get(),Runnable::run,cb));await((RepositoryCallback<Expense> cb)->app.expenses().saveExpense(owner.id,changed,ReceiptChange.replace(stage.id),Runnable::run,cb));
    org.json.JSONObject fixture=new org.json.JSONObject().put("actor",owner.id).put("group",group).put("expense",changed.id).put("receipt",stage.id).put("pid",android.os.Process.myPid()).put("bytes",stage.byteCount).put("sha256",ReceiptColdRestartTest.sha256(camera.photo.get()));
    try(FileOutputStream output=new FileOutputStream(new File(app.getExternalFilesDir("verification8"),"fixture.json"))){output.write(fixture.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}keepSession=true;
   }
  }finally{instrumentation.removeMonitor(camera);}
 }
}
