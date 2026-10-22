package com.example.splitease.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.domain.Rules;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.example.splitease.ui.settlements.SettlementsViewModel;
import com.example.splitease.util.*;
import com.google.android.material.tabs.TabLayout;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettlementNavigationTest {
    private SplitEaseApplication app;
    private AuthService auth;
    private User a,b,c;
    private String groupId;
    private interface Call<T> { void run(RepositoryCallback<T> callback); }
    private <T> T await(Call<T> call) throws Exception {
        CompletableFuture<T> future=new CompletableFuture<>(); call.run(new RepositoryCallback<T>() {
            public void onSuccess(T value) { future.complete(value); }
            public void onError(Exception error) { future.completeExceptionally(error); }
        }); return future.get(30,TimeUnit.SECONDS);
    }
    private User register(String prefix) throws Exception {
        String name=prefix+UUID.randomUUID().toString().substring(0,8);
        User user=await((RepositoryCallback<User> cb) -> auth.register(name,name+"@example.com","Strong1!".toCharArray(),Runnable::run,cb)); logout(); return user;
    }
    private void logout() throws Exception { await((RepositoryCallback<Boolean> cb) -> auth.logout(Runnable::run,cb)); }
    private void login(User user) throws Exception { await((RepositoryCallback<User> cb) -> auth.login(user.email,"Strong1!".toCharArray(),Runnable::run,cb)); }
    private SettlementDetails snapshot() throws Exception {
        CompletableFuture<SettlementDetails> future=new CompletableFuture<>();
        ObservableQuery.Subscription watch=app.settlements().observeSettlementDetails(b.id,groupId).subscribe(Runnable::run,new ObservableQuery.Observer<SettlementDetails>() {
            public void onChanged(SettlementDetails value) { future.complete(value); }
            public void onError(Exception error) { future.completeExceptionally(error); }
        }); try { return future.get(20,TimeUnit.SECONDS); } finally { watch.close(); }
    }
    @Before public void setup() throws Exception {
        app=ApplicationProvider.getApplicationContext();auth=app.auth();logout();a=register("recipient");b=register("payer");c=register("member");
        await((RepositoryCallback<User> cb) -> app.users().updateUser(new User(a.id,a.username,a.email,"review@upi"),Runnable::run,cb));
        groupId=UUID.randomUUID().toString();await((RepositoryCallback<Group> cb) -> app.groups().createGroup(a.id,new Group(groupId,"Settlement test",Group.Type.TRIP,a.id,System.currentTimeMillis()),Arrays.asList(b.id,c.id),Runnable::run,cb));
        String id=UUID.randomUUID().toString();List<ExpenseSplit> splits=new ArrayList<>();
        for(Map.Entry<String,Long> share:Rules.equalSplit(20000,Arrays.asList(a.id,b.id)).entrySet()) splits.add(new ExpenseSplit(id,share.getKey(),share.getValue()));
        await((RepositoryCallback<Expense> cb) -> app.expenses().saveExpense(a.id,new Expense(id,groupId,"Dinner",20000,a.id,a.id,"Food",System.currentTimeMillis(),splits),Runnable::run,cb));login(b);
    }
    @After public void cleanup() throws Exception { InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));logout(); }
    private NavHostFragment host(MainActivity a) { return (NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host); }
    private void waitFor(ActivityScenario<MainActivity> s,Predicate<MainActivity> condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until) { AtomicBoolean ready=new AtomicBoolean();s.onActivity(a -> ready.set(condition.test(a)));if(ready.get()) return;Thread.sleep(50); }fail("Timed out waiting for settlement UI");
    }
    private void destination(ActivityScenario<MainActivity> s,int id) throws Exception { waitFor(s,a -> host(a).getNavController().getCurrentDestination().getId()==id && a.findViewById(R.id.main_content).getVisibility()==View.VISIBLE); }
    private void open(ActivityScenario<MainActivity> s) throws Exception {
        destination(s,R.id.groups);waitFor(s,a -> a.findViewById(R.id.open)!=null);s.onActivity(a -> a.findViewById(R.id.open).performClick());destination(s,R.id.group_detail);
        waitFor(s,a -> a.findViewById(R.id.content).getVisibility()==View.VISIBLE);
        s.onActivity(a -> { TabLayout tabs=a.findViewById(R.id.tabs);tabs.selectTab(tabs.getTabAt(2));a.findViewById(R.id.settle_up).performClick(); });destination(s,R.id.settlements_screen);
        waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.suggestions)).getChildCount()==1);
    }
    private void select(ActivityScenario<MainActivity> s) { s.onActivity(a -> ((ViewGroup)a.findViewById(R.id.suggestions)).getChildAt(0).findViewById(R.id.action).performClick()); }
    private void confirm(String text) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) { AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if(root!=null) for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)) if(node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;Thread.sleep(100);
        }fail("Missing confirmation "+text);
    }
    private void record(ActivityScenario<MainActivity> s,String amount) throws Exception {
        s.onActivity(a -> { ((EditText)a.findViewById(R.id.amount)).setText(amount);a.findViewById(R.id.record).performClick(); });confirm("Confirm");
    }
    private void shot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(300);
        android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(app.getExternalFilesDir("verification6"),name+".png"))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); } finally { bitmap.recycle(); }
    }
    @Test public void recordPartialDuplicateEditSwitchAndCancelUpiWithoutAutomaticRecording() throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)) {
            open(s);select(s);
            s.onActivity(a -> {
                ((EditText)a.findViewById(R.id.amount)).setText("20.00");
                Fragment fragment=host(a).getChildFragmentManager().getPrimaryNavigationFragment();
                SettlementsViewModel model=new ViewModelProvider(fragment).get(SettlementsViewModel.class);
                assertEquals("100.00",model.paymentUri().getQueryParameter("am"));
                assertTrue(UpiPayment.launch(model.paymentUri(),"Choose",new UpiPayment.Gateway() {
                    public boolean available(Intent intent) { return true; }
                    public void start(Intent chooser) { assertEquals(Intent.ACTION_CHOOSER,chooser.getAction()); /* User cancels: no result can trigger recording. */ }
                }));
                // Only exercise the real no-handler path; never open a financial app during tests.
                if(a.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_VIEW,model.paymentUri()),0).isEmpty()) {
                    a.findViewById(R.id.pay).performClick();assertEquals(a.getString(R.string.upi_unavailable),((TextView)a.findViewById(R.id.error)).getText().toString());
                    assertTrue(a.findViewById(R.id.record).isEnabled());
                }
            });
            assertTrue(snapshot().records.isEmpty());s.recreate();destination(s,R.id.settlements_screen);
            waitFor(s,a -> a.findViewById(R.id.form).getVisibility()==View.VISIBLE);
            s.onActivity(a -> assertEquals("20.00",((EditText)a.findViewById(R.id.amount)).getText().toString()));shot("payment-details-light");
            record(s,"101.00");waitFor(s,a -> a.findViewById(R.id.error).getVisibility()==View.VISIBLE);assertTrue(snapshot().records.isEmpty());
            record(s,"20.00");waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.records)).getChildCount()==1);assertEquals(8000,snapshot().suggestions.get(0).amountPaise);
            select(s);record(s,"20.00");confirm("Proceed anyway");waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.records)).getChildCount()==2);assertEquals(6000,snapshot().suggestions.get(0).amountPaise);
            logout();destination(s,R.id.login);login(c);open(s);select(s);
            s.onActivity(a -> assertFalse(a.findViewById(R.id.record).isEnabled()));
            s.onActivity(a -> ((ViewGroup)a.findViewById(R.id.records)).getChildAt(0).findViewById(R.id.action).performClick());
            record(s,"30.00");waitFor(s,a -> a.findViewById(R.id.form).getVisibility()==View.GONE);assertEquals(5000,snapshot().suggestions.get(0).amountPaise);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));destination(s,R.id.settlements_screen);
            waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.records)).getChildCount()==2);shot("settlements-dark");
            logout();destination(s,R.id.login);
            s.onActivity(a -> { Bundle args=new Bundle();args.putString("groupId",groupId);host(a).getNavController().navigate(R.id.settlements_screen,args); });destination(s,R.id.login);
        }
    }
}
