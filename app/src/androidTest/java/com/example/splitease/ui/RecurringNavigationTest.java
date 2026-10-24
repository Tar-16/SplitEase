package com.example.splitease.ui;
import android.app.*;
import android.os.*;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.*;
import com.example.splitease.*;
import com.example.splitease.R;
import com.example.splitease.auth.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.example.splitease.reminders.*;
import com.example.splitease.domain.Rules;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RecurringNavigationTest {
 private boolean keepVerificationSession;
 private SplitEaseApplication app;private AuthService auth;private User a,b,other;private String group;
 private interface Call<T>{void run(RepositoryCallback<T> cb);}
 private <T>T await(Call<T> call)throws Exception{CompletableFuture<T> f=new CompletableFuture<>();call.run(new RepositoryCallback<T>(){public void onSuccess(T v){f.complete(v);}public void onError(Exception e){f.completeExceptionally(e);}});return f.get(30,TimeUnit.SECONDS);}
 private User register(String prefix)throws Exception{String n=prefix+UUID.randomUUID().toString().substring(0,8);User u=await((RepositoryCallback<User> cb)->auth.register(n,n+"@example.com","Strong1!".toCharArray(),Runnable::run,cb));logout();return u;}
 private void logout()throws Exception{await((RepositoryCallback<Boolean> cb)->auth.logout(Runnable::run,cb));}
 private void login(User u)throws Exception{await((RepositoryCallback<User> cb)->auth.login(u.email,"Strong1!".toCharArray(),Runnable::run,cb));}
 private RecurringSnapshot snapshot(String actor)throws Exception{CompletableFuture<RecurringSnapshot> f=new CompletableFuture<>();ObservableQuery.Subscription s=app.recurringBills().observeRecurring(actor).subscribe(Runnable::run,new ObservableQuery.Observer<RecurringSnapshot>(){public void onChanged(RecurringSnapshot v){f.complete(v);}public void onError(Exception e){f.completeExceptionally(e);}});try{return f.get(20,TimeUnit.SECONDS);}finally{s.close();}}
 @Before public void setup()throws Exception{app=ApplicationProvider.getApplicationContext();auth=app.auth();logout();a=register("billowner");b=register("billmember");other=register("private");group=UUID.randomUUID().toString();await((RepositoryCallback<Group> cb)->app.groups().createGroup(a.id,new Group(group,"Monthly flat",Group.Type.FLAT,a.id,System.currentTimeMillis()),Arrays.asList(b.id),Runnable::run,cb));login(a);}
 @After public void cleanup()throws Exception{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));if(!keepVerificationSession)logout();}
 private NavHostFragment host(MainActivity a){return (NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host);}
 private void waitFor(ActivityScenario<MainActivity> s,Predicate<MainActivity> condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<until){AtomicBoolean done=new AtomicBoolean();s.onActivity(a->done.set(condition.test(a)));if(done.get())return;Thread.sleep(50);}fail("Timed out waiting for recurring UI");}
 private void destination(ActivityScenario<MainActivity> s,int id)throws Exception{waitFor(s,a->host(a).getNavController().getCurrentDestination().getId()==id&&a.findViewById(R.id.main_content).getVisibility()==View.VISIBLE);}
 private void home(ActivityScenario<MainActivity> s)throws Exception{destination(s,R.id.groups);s.onActivity(a->a.findViewById(R.id.recurring).performClick());destination(s,R.id.recurring);waitFor(s,a->a.findViewById(R.id.content).getVisibility()==View.VISIBLE);}
 private void confirm()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText("Confirm"))if(n.isClickable()&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;Thread.sleep(50);}fail("Missing confirmation");}
 private void shot(String name)throws Exception{InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(200);android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(app.getExternalFilesDir("verification7"),name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}}
 private void text(MainActivity a,int id,String value){((EditText)a.findViewById(id)).setText(value);}
 @Test public void createEditTrackRecreateAndSwitchAccounts()throws Exception{
  try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
   home(s);s.onActivity(a->a.findViewById(R.id.create).performClick());destination(s,R.id.bill_screen);waitFor(s,a->((ViewGroup)a.findViewById(R.id.participants)).getChildCount()==2);
   s.onActivity(a->{text(a,R.id.name,"Internet plan");text(a,R.id.amount,"100.01");text(a,R.id.day,"31");text(a,R.id.lead,"3");ViewGroup participants=a.findViewById(R.id.participants);((CompoundButton)participants.getChildAt(0)).setChecked(true);((CompoundButton)participants.getChildAt(1)).setChecked(true);});
   s.recreate();waitFor(s,a->((EditText)a.findViewById(R.id.name)).getText().toString().equals("Internet plan"));s.onActivity(a->a.findViewById(R.id.save).performClick());confirm();
   waitFor(s,a->a.findViewById(R.id.edit).getVisibility()==View.VISIBLE&&((ViewGroup)a.findViewById(R.id.current)).getChildCount()==4);
   RecurringBill bill=snapshot(a.id).bills.get(0);RecurringBillCycle cycle=snapshot(a.id).cycles.get(bill.id).get(0);assertEquals(10001,cycle.amountPaise);assertEquals(10001,cycle.contributions.get(0).sharePaise+cycle.contributions.get(1).sharePaise);
   shot("bill-light");logout();destination(s,R.id.login);login(b);destination(s,R.id.groups);home(s);s.onActivity(a->((ViewGroup)a.findViewById(R.id.bills)).getChildAt(0).findViewById(R.id.action).performClick());destination(s,R.id.bill_screen);
   waitFor(s,a->((ViewGroup)a.findViewById(R.id.current)).getChildCount()==4);s.onActivity(a->((ViewGroup)a.findViewById(R.id.current)).getChildAt(2).findViewById(R.id.action).performClick());confirm();
   waitFor(s,a->((TextView)((ViewGroup)a.findViewById(R.id.current)).getChildAt(2).findViewById(R.id.role)).getText().toString().contains("Paid"));
   s.onActivity(a->a.findViewById(R.id.edit).performClick());s.onActivity(a->{text(a,R.id.amount,"200.03");((CompoundButton)a.findViewById(R.id.active)).setChecked(false);a.findViewById(R.id.save).performClick();});confirm();
   waitFor(s,a->a.findViewById(R.id.edit).getVisibility()==View.VISIBLE&&a.findViewById(R.id.progress).getVisibility()==View.GONE);RecurringSnapshot data=snapshot(b.id);assertFalse(data.bills.get(0).active);assertEquals(10001,data.cycles.get(bill.id).get(0).amountPaise);assertTrue(data.cycles.get(bill.id).get(0).contributions.get(0).paid);
   InstrumentationRegistry.getInstrumentation().runOnMainSync(()->AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));s.recreate();destination(s,R.id.bill_screen);waitFor(s,a->a.findViewById(R.id.edit).getVisibility()==View.VISIBLE);shot("bill-dark");
   logout();destination(s,R.id.login);login(other);destination(s,R.id.groups);home(s);waitFor(s,a->((ViewGroup)a.findViewById(R.id.bills)).getChildCount()==0);assertTrue(snapshot(other.id).bills.isEmpty());
   s.onActivity(a->{Bundle args=new Bundle();args.putString("billId",bill.id);host(a).getNavController().navigate(R.id.bill_screen,args);});destination(s,R.id.bill_screen);waitFor(s,a->a.findViewById(R.id.content).getVisibility()==View.GONE&&a.findViewById(R.id.error).getVisibility()==View.VISIBLE);
   logout();destination(s,R.id.login);s.onActivity(a->{Bundle args=new Bundle();args.putString("billId",bill.id);host(a).getNavController().navigate(R.id.bill_screen,args);});destination(s,R.id.login);
  }
 }
 /** Exact requested WiFi scenario with a nonzero accounting control, not an empty-group check. */
 @Test public void additionalWifiThreeMembersPaidUnpaidIsolationAndAccountSwitch() throws Exception {
  logout();User priya=register("priya");login(a);
  await((RepositoryCallback<Boolean> cb)->app.groups().addMember(a.id,group,priya.id,System.currentTimeMillis(),Runnable::run,cb));
  String expenseId=UUID.randomUUID().toString();List<ExpenseSplit> splits=new ArrayList<>();
  for(Map.Entry<String,Long> share:Rules.equalSplit(90000,Arrays.asList(a.id,b.id,priya.id)).entrySet())splits.add(new ExpenseSplit(expenseId,share.getKey(),share.getValue()));
  await((RepositoryCallback<Expense> cb)->app.expenses().saveExpense(a.id,new Expense(expenseId,group,"Ordinary control expense",90000,a.id,a.id,"Bills",System.currentTimeMillis(),splits),Runnable::run,cb));
  await((RepositoryCallback<Settlement> cb)->app.settlements().saveSettlement(b.id,new Settlement(UUID.randomUUID().toString(),group,b.id,a.id,10000,System.currentTimeMillis()),Runnable::run,cb));
  String control=accounting(a.id);assertTrue(control.contains("90000"));
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
   home(scenario);scenario.onActivity(activity->activity.findViewById(R.id.create).performClick());destination(scenario,R.id.bill_screen);
   waitFor(scenario,activity->((ViewGroup)activity.findViewById(R.id.participants)).getChildCount()==3);
   scenario.onActivity(activity->{text(activity,R.id.name,"WiFi verification");text(activity,R.id.amount,"999");text(activity,R.id.day,"31");text(activity,R.id.lead,"1");
    // Use view clicks, so the actual checkbox listeners and draft rendering participate.
    for(int index=0;index<3;index++)((ViewGroup)activity.findViewById(R.id.participants)).getChildAt(index).performClick();
    activity.findViewById(R.id.save).performClick();});confirm();
   waitFor(scenario,activity->activity.findViewById(R.id.edit).getVisibility()==View.VISIBLE&&((ViewGroup)activity.findViewById(R.id.current)).getChildCount()==5);
   RecurringBill bill=snapshot(a.id).bills.get(0);RecurringBillCycle current=snapshot(a.id).cycles.get(bill.id).get(0);
   assertEquals(99900,bill.amountPaise);assertEquals(3,current.contributions.size());
   for(RecurringBillContribution contribution:current.contributions){assertEquals(33300,contribution.sharePaise);assertFalse(contribution.paid);}
   progress(scenario,0);assertEquals(control,accounting(a.id));shot("extra-wifi-unpaid");
   clickContribution(scenario,priya.username);confirm();progress(scenario,1);assertTrue(contribution(snapshot(a.id),bill.id,current.id,priya.id).paid);assertEquals(control,accounting(a.id));shot("extra-priya-paid");
   clickContribution(scenario,priya.username);confirm();progress(scenario,0);assertFalse(contribution(snapshot(a.id),bill.id,current.id,priya.id).paid);assertEquals(control,accounting(a.id));shot("extra-priya-unpaid");
   Calendar previous=Calendar.getInstance(TimeZone.getTimeZone("UTC"));previous.add(Calendar.MONTH,-1);
   RecurringBillCycle historical=await((RepositoryCallback<RecurringBillCycle> cb)->app.recurringBills().createCycle(a.id,bill.id,previous.get(Calendar.YEAR),previous.get(Calendar.MONTH)+1,Runnable::run,cb));
   await((RepositoryCallback<Boolean> cb)->app.recurringBills().setContributionPaid(a.id,historical.id,priya.id,true,Runnable::run,cb));
   logout();destination(scenario,R.id.login);login(priya);destination(scenario,R.id.groups);home(scenario);
   assertEquals(bill.id,snapshot(priya.id).bills.get(0).id);
   scenario.onActivity(activity->((ViewGroup)activity.findViewById(R.id.bills)).getChildAt(0).findViewById(R.id.action).performClick());destination(scenario,R.id.bill_screen);progress(scenario,0);
   // A different participating account can update another participant's contribution.
   clickContribution(scenario,a.username);confirm();progress(scenario,1);assertTrue(contribution(snapshot(priya.id),bill.id,current.id,a.id).paid);
   assertTrue(snapshot(other.id).bills.isEmpty());
   assertThrows(ExecutionException.class,()->await((RepositoryCallback<Boolean> cb)->app.recurringBills().setContributionPaid(other.id,current.id,priya.id,true,Runnable::run,cb)));
   assertThrows(ExecutionException.class,()->await((RepositoryCallback<RecurringBill> cb)->app.recurringBills().saveBill(other.id,bill,Runnable::run,cb)));
   assertEquals(control,accounting(priya.id));shot("extra-participant-account");
   if("true".equals(InstrumentationRegistry.getArguments().getString("keepVerificationFixture"))) {
    org.json.JSONObject fixture=new org.json.JSONObject();fixture.put("actor",priya.id).put("owner",a.id).put("priya",priya.id).put("group",group).put("bill",bill.id).put("current",current.id).put("historical",historical.id).put("pid",android.os.Process.myPid()).put("accounting",control);
    try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(app.getExternalFilesDir("verification7-extra"),"fixture.json"))){output.write(fixture.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    keepVerificationSession=true;
   }
  }
 }
 private void progress(ActivityScenario<MainActivity> scenario,int count)throws Exception {
  waitFor(scenario,activity->{ViewGroup current=activity.findViewById(R.id.current);return current!=null&&current.getChildCount()==5&&((TextView)current.getChildAt(0)).getText().toString().contains("Paid "+count+"/3");});
 }
 private void clickContribution(ActivityScenario<MainActivity> scenario,String username)throws Exception {
  waitFor(scenario,activity->activity.findViewById(R.id.current)!=null);
  scenario.onActivity(activity->{ViewGroup current=activity.findViewById(R.id.current);boolean found=false;for(int index=2;index<current.getChildCount();index++){View row=current.getChildAt(index);if(((TextView)row.findViewById(R.id.name)).getText().toString().equals(username)){row.findViewById(R.id.action).performClick();found=true;break;}}assertTrue("Contribution row for "+username,found);});
 }
 private RecurringBillContribution contribution(RecurringSnapshot data,String bill,String cycle,String user) {
  for(RecurringBillCycle value:data.cycles.get(bill))if(value.id.equals(cycle))for(RecurringBillContribution contribution:value.contributions)if(contribution.userId.equals(user))return contribution;
  throw new AssertionError("Missing persisted contribution");
 }
 private String accounting(String actor)throws Exception {
  CompletableFuture<SettlementDetails> future=new CompletableFuture<>();ObservableQuery.Subscription watch=app.settlements().observeSettlementDetails(actor,group).subscribe(Runnable::run,new ObservableQuery.Observer<SettlementDetails>(){public void onChanged(SettlementDetails value){future.complete(value);}public void onError(Exception error){future.completeExceptionally(error);}});
  try{return RecurringColdRestartTest.accountingFingerprint(future.get(20,TimeUnit.SECONDS));}finally{watch.close();}
 }
 private void shell(String command)throws Exception{try(ParcelFileDescriptor descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){byte[] buffer=new byte[1024];while(in.read(buffer)!=-1){}}}
 @Test public void notificationPermissionSchedulingDeepLinkAndLogoutCancellation()throws Exception{
  String billId=UUID.randomUUID().toString();Calendar today=Calendar.getInstance(TimeZone.getTimeZone("UTC"));int day=today.get(Calendar.DAY_OF_MONTH);
  RecurringBill bill=new RecurringBill(billId,group,"Private reminder",10000,day,Arrays.asList(a.id,b.id),a.id,3,true);
  await((RepositoryCallback<RecurringBill> cb)->app.recurringBills().saveBill(a.id,bill,Runnable::run,cb));
  try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
   destination(s,R.id.groups);WorkManager manager=WorkManager.getInstance(app);
   waitFor(s,x->{try{return !manager.getWorkInfosForUniqueWork(RecurringReminders.PERIODIC).get().isEmpty();}catch(Exception e){throw new AssertionError(e);}});
   shell("appops set com.example.splitease POST_NOTIFICATION deny");assertFalse(app.reminders().permitted());
   BillReminder reminder=new BillReminder(billId,snapshot(a.id).cycles.get(billId).get(0).id,bill.title,"Monthly flat",System.currentTimeMillis());assertFalse(app.reminders().deliver(a.id,Arrays.asList(reminder)));assertEquals(0,app.getSystemService(NotificationManager.class).getActiveNotifications().length);
   OneTimeWorkRequest denied=new OneTimeWorkRequest.Builder(RecurringReminderWorker.class).setInputData(new Data.Builder().putString("actor",a.id).build()).build();manager.enqueue(denied).getResult().get();
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!manager.getWorkInfoById(denied.getId()).get().getState().isFinished()&&System.nanoTime()<deadline)Thread.sleep(100);assertEquals(WorkInfo.State.SUCCEEDED,manager.getWorkInfoById(denied.getId()).get().getState());
   shell("pm grant com.example.splitease android.permission.POST_NOTIFICATIONS");shell("appops set com.example.splitease POST_NOTIFICATION allow");assertTrue(app.reminders().permitted());assertFalse(app.reminders().deliver(b.id,Arrays.asList(reminder)));
   OneTimeWorkRequest enabled=new OneTimeWorkRequest.Builder(RecurringReminderWorker.class).setInputData(new Data.Builder().putString("actor",a.id).build()).build();manager.enqueue(enabled).getResult().get();
   waitFor(s,x->app.getSystemService(NotificationManager.class).getActiveNotifications().length==1);
   assertTrue(await((RepositoryCallback<List<BillReminder>> cb)->app.recurringBills().claimReminders(a.id,System.currentTimeMillis(),Runnable::run,cb)).isEmpty());
   app.getSystemService(NotificationManager.class).getActiveNotifications()[0].getNotification().contentIntent.send();destination(s,R.id.bill_screen);waitFor(s,x->((TextView)x.findViewById(R.id.title)).getText().toString().equals("Private reminder"));
   logout();destination(s,R.id.login);waitFor(s,x->app.getSystemService(NotificationManager.class).getActiveNotifications().length==0);
   assertFalse(app.reminders().deliver(a.id,Arrays.asList(reminder)));
   waitFor(s,x->{try{for(WorkInfo info:manager.getWorkInfosForUniqueWork(RecurringReminders.PERIODIC).get())if(info.getState()!=WorkInfo.State.CANCELLED)return false;return true;}catch(Exception e){throw new AssertionError(e);}});
  }finally{shell("appops set com.example.splitease POST_NOTIFICATION allow");}
 }
}
