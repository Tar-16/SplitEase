package com.example.splitease.ui;

import android.view.View;
import android.widget.TextView;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.auth.SessionState;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Explicit second-process verification; normal suites skip without the prepared fixture argument. */
@RunWith(AndroidJUnit4.class)
public class RecurringColdRestartTest {
 static String accountingFingerprint(SettlementDetails details) {
  List<String> values=new ArrayList<>();values.add("total:"+details.group.totalExpenses);
  for(Expense e:details.group.expenses){values.add("expense:"+e.id+":"+e.groupId+":"+e.title+":"+e.amountPaise+":"+e.payerId+":"+e.creatorId+":"+e.category+":"+e.expenseDate);for(ExpenseSplit split:e.splits)values.add("split:"+e.id+":"+split.userId+":"+split.sharePaise);}
  for(Settlement s:details.records)values.add("settlement:"+s.id+":"+s.groupId+":"+s.payerId+":"+s.payeeId+":"+s.amountPaise+":"+s.settlementDate);
  for(Map.Entry<String,Long> b:details.group.balances.entrySet())values.add("balance:"+b.getKey()+":"+b.getValue());
  for(Transfer t:details.suggestions)values.add("transfer:"+t.payerId+":"+t.payeeId+":"+t.amountPaise);
  Collections.sort(values);return values.toString();
 }
 private <T>T snapshot(ObservableQuery<T> query)throws Exception{CompletableFuture<T> future=new CompletableFuture<>();ObservableQuery.Subscription watch=query.subscribe(Runnable::run,new ObservableQuery.Observer<T>(){public void onChanged(T value){future.complete(value);}public void onError(Exception error){future.completeExceptionally(error);}});try{return future.get(20,TimeUnit.SECONDS);}finally{watch.close();}}
 @Test public void restoredSessionCurrentAndHistoricalCyclesSurviveColdRestart()throws Exception {
  Assume.assumeTrue("Explicit force-stop fixture required","true".equals(InstrumentationRegistry.getArguments().getString("verifyColdRestart")));
  SplitEaseApplication app=ApplicationProvider.getApplicationContext();JSONObject fixture;
  try(java.io.InputStream input=new java.io.FileInputStream(new java.io.File(app.getExternalFilesDir("verification7-extra"),"fixture.json"));java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);fixture=new JSONObject(output.toString("UTF-8"));}
  assertNotEquals("A genuinely new process must read the fixture",fixture.getInt("pid"),android.os.Process.myPid());
  CompletableFuture<String> restored=new CompletableFuture<>();ObservableQuery.Subscription session=app.auth().sessions().subscribe(Runnable::run,new ObservableQuery.Observer<SessionState>(){public void onChanged(SessionState state){if(state.status==SessionState.Status.SIGNED_IN)restored.complete(state.user.id);else if(state.status==SessionState.Status.SIGNED_OUT||state.status==SessionState.Status.UNAVAILABLE)restored.completeExceptionally(new AssertionError("Session not restored"));}public void onError(Exception error){restored.completeExceptionally(error);}});
  String actor;try{actor=restored.get(20,TimeUnit.SECONDS);}finally{session.close();}assertEquals(fixture.getString("actor"),actor);
  RecurringSnapshot data=snapshot(app.recurringBills().observeRecurring(actor));List<RecurringBillCycle> cycles=data.cycles.get(fixture.getString("bill"));assertNotNull(cycles);assertEquals(2,cycles.size());
  for(RecurringBillCycle cycle:cycles){assertEquals(99900,cycle.amountPaise);assertEquals(3,cycle.contributions.size());for(RecurringBillContribution contribution:cycle.contributions){assertEquals(33300,contribution.sharePaise);boolean expected=cycle.id.equals(fixture.getString("historical"))?contribution.userId.equals(fixture.getString("priya")):contribution.userId.equals(fixture.getString("owner"));assertEquals(expected,contribution.paid);}}
  assertEquals(fixture.getString("accounting"),accountingFingerprint(snapshot(app.settlements().observeSettlementDetails(actor,fixture.getString("group")))));
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean ready=false;
   while(System.nanoTime()<deadline){AtomicBoolean flag=new AtomicBoolean();scenario.onActivity(activity->{NavHostFragment host=(NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);flag.set(host.getNavController().getCurrentDestination().getId()==R.id.groups&&activity.findViewById(R.id.main_content).getVisibility()==View.VISIBLE);});if(flag.get()){ready=true;break;}Thread.sleep(50);}assertTrue("Restored directly to authenticated Home",ready);
   scenario.onActivity(activity->{android.os.Bundle args=new android.os.Bundle();args.putString("billId",fixture.optString("bill"));((NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host)).getNavController().navigate(R.id.bill_screen,args);});
   deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);ready=false;while(System.nanoTime()<deadline){AtomicBoolean flag=new AtomicBoolean();scenario.onActivity(activity->{TextView title=activity.findViewById(R.id.title);android.view.ViewGroup current=activity.findViewById(R.id.current),history=activity.findViewById(R.id.history);flag.set(title!=null&&title.getText().toString().equals("WiFi verification")&&current!=null&&current.getChildCount()==5&&history.getChildCount()==5);});if(flag.get()){ready=true;break;}Thread.sleep(50);}assertTrue("Current and historical cycles rendered",ready);
  }
 }
}
