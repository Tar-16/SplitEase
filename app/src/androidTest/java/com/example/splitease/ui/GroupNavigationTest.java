package com.example.splitease.ui;

import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.model.User;
import com.example.splitease.repository.RepositoryCallback;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GroupNavigationTest {
    private AuthService auth;
    private String first, second, third;
    private interface Call<T> { void run(RepositoryCallback<T> callback); }
    private <T> T await(Call<T> call) throws Exception {
        CompletableFuture<T> future=new CompletableFuture<>();
        call.run(new RepositoryCallback<T>() {
            @Override public void onSuccess(T value) { future.complete(value); }
            @Override public void onError(Exception error) { future.completeExceptionally(error); }
        }); return future.get(30,TimeUnit.SECONDS);
    }
    @Before public void accounts() throws Exception {
        auth=((SplitEaseApplication)ApplicationProvider.getApplicationContext()).auth();
        String suffix=UUID.randomUUID().toString().substring(0,8);
        first="alex"+suffix; second="blair"+suffix; third="casey"+suffix;
        await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c));
        for(String name:Arrays.asList(third,second,first)) {
            await((RepositoryCallback<User> c) -> auth.register(name,name+"@example.com","Strong1!".toCharArray(),Runnable::run,c));
            await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c));
        }
        login(first);
    }
    @After public void cleanup() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c));
    }
    private void login(String name) throws Exception { await((RepositoryCallback<User> c) -> auth.login(name+"@example.com","Strong1!".toCharArray(),Runnable::run,c)); }
    private int destination(MainActivity a) { return ((NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host)).getNavController().getCurrentDestination().getId(); }
    private void waitFor(ActivityScenario<MainActivity> scenario,Predicate<MainActivity> condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until) {
            AtomicBoolean ready=new AtomicBoolean(); scenario.onActivity(a -> ready.set(condition.test(a)));
            if(ready.get()) return; Thread.sleep(50);
        } fail("Timed out waiting for group UI");
    }
    private void destination(ActivityScenario<MainActivity> s,int id) throws Exception { waitFor(s,a -> destination(a)==id && a.findViewById(R.id.main_content).getVisibility()==View.VISIBLE); }
    private void fill(MainActivity a,int id,String value) { ((EditText)a.findViewById(id)).setText(value); }
    private void findUser(ActivityScenario<MainActivity> s,String name) throws Exception {
        s.onActivity(a -> { fill(a,R.id.query,name); a.findViewById(R.id.search).performClick(); });
        waitFor(s,a -> a.findViewById(R.id.add).getVisibility()==View.VISIBLE && ((TextView)a.findViewById(R.id.result)).getText().toString().equals(name));
    }
    private View memberAction(MainActivity a,String name) {
        ViewGroup rows=a.findViewById(R.id.members);
        for(int i=0;i<rows.getChildCount();i++) {
            View row=rows.getChildAt(i);
            if(((TextView)row.findViewById(R.id.name)).getText().toString().equals(name)) return row.findViewById(R.id.action);
        } throw new AssertionError("Missing member "+name);
    }
    private void confirm() throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) {
            AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if(root!=null) for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("Confirm")) {
                if(node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            }
            Thread.sleep(100);
        }
        fail("Confirmation dialog must appear");
    }
    private void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        android.content.Context context=ApplicationProvider.getApplicationContext();
        java.io.File directory=context.getExternalFilesDir("verification");
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(directory,name+".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
        } finally { bitmap.recycle(); }
    }
    @Test public void createTransferLeaveAndSwitchAccountsWithRecreationAndThemes() throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)) {
            destination(s,R.id.groups);
            waitFor(s,a -> a.findViewById(R.id.empty).getVisibility()==View.VISIBLE);
            s.onActivity(a -> a.findViewById(R.id.new_group).performClick()); destination(s,R.id.create_group);
            s.onActivity(a -> {
                assertEquals(View.GONE,a.findViewById(R.id.bottom_navigation).getVisibility());
                a.findViewById(R.id.create).performClick();
                assertEquals(a.getString(R.string.group_name_required),((TextView)a.findViewById(R.id.error)).getText().toString());
                fill(a,R.id.group_name,"Our flat");
            });
            findUser(s,second); s.onActivity(a -> a.findViewById(R.id.add).performClick());
            s.onActivity(a -> {
                ViewGroup selected=a.findViewById(R.id.selected_members); assertEquals(1,selected.getChildCount());
                selected.getChildAt(0).findViewById(R.id.action).performClick(); assertEquals(0,selected.getChildCount());
            });
            findUser(s,second); s.onActivity(a -> a.findViewById(R.id.add).performClick());
            screenshot("group-create-light");
            s.recreate(); destination(s,R.id.create_group);
            waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.selected_members)).getChildCount()==1);
            s.onActivity(a -> {
                assertEquals("Our flat",((EditText)a.findViewById(R.id.group_name)).getText().toString());
                a.findViewById(R.id.create).performClick(); assertFalse(a.findViewById(R.id.create).isEnabled());
            });
            destination(s,R.id.group_detail);
            waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.members)).getChildCount()==2);
            s.onActivity(a -> { assertEquals("Our flat",((TextView)a.findViewById(R.id.title)).getText().toString()); assertFalse(a.findViewById(R.id.leave).isEnabled()); });
            s.recreate(); destination(s,R.id.group_detail);
            findUser(s,third); s.onActivity(a -> a.findViewById(R.id.add).performClick()); confirm();
            waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.members)).getChildCount()==3);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
            destination(s,R.id.group_detail);
            waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.members)).getChildCount()==3);
            screenshot("group-detail-dark");
            s.onActivity(a -> memberAction(a,second).performClick()); confirm();
            waitFor(s,a -> a.findViewById(R.id.leave).isEnabled());
            s.onActivity(a -> { assertEquals(View.GONE,a.findViewById(R.id.member_search).getVisibility()); a.findViewById(R.id.leave).performClick(); }); confirm();
            destination(s,R.id.groups);
            waitFor(s,a -> a.findViewById(R.id.empty).getVisibility()==View.VISIBLE);
            await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c)); destination(s,R.id.login);
            login(second); destination(s,R.id.groups);
            waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.group_list)).getChildCount()==1);
            s.onActivity(a -> a.findViewById(R.id.open).performClick()); destination(s,R.id.group_detail);
            waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.members)).getChildCount()==2);
            s.onActivity(a -> { assertEquals(View.VISIBLE,a.findViewById(R.id.member_search).getVisibility()); assertFalse(a.findViewById(R.id.leave).isEnabled()); a.findViewById(R.id.back).performClick(); });
            destination(s,R.id.groups);
            s.onActivity(a -> a.findViewById(R.id.new_group).performClick()); destination(s,R.id.create_group);
            s.onActivity(a -> { fill(a,R.id.group_name,"Weekend trip"); a.findViewById(R.id.trip).performClick(); a.findViewById(R.id.create).performClick(); });
            destination(s,R.id.group_detail);
            waitFor(s,a -> ((TextView)a.findViewById(R.id.meta)).getText().toString().startsWith("Trip"));
            s.onActivity(a -> a.findViewById(R.id.back).performClick()); destination(s,R.id.groups);
            waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.group_list)).getChildCount()==2);
            screenshot("groups-home-dark");
            for(int id:new int[]{R.id.profile,R.id.recurring,R.id.groups}) {
                s.onActivity(a -> ((BottomNavigationView)a.findViewById(R.id.bottom_navigation)).setSelectedItemId(id)); destination(s,id);
            }
            await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c)); destination(s,R.id.login);
            login(first); destination(s,R.id.groups);
            waitFor(s,a -> a.findViewById(R.id.empty).getVisibility()==View.VISIBLE);
        }
    }
    @Test public void unauthorizedDetailAndSignedOutDestinationsNeverExposeMembers() throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)) {
            destination(s,R.id.groups);
            s.onActivity(a -> {
                android.os.Bundle args=new android.os.Bundle(); args.putString("groupId",UUID.randomUUID().toString());
                ((NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host)).getNavController().navigate(R.id.group_detail,args);
            }); destination(s,R.id.group_detail);
            waitFor(s,a -> a.findViewById(R.id.error).getVisibility()==View.VISIBLE);
            s.onActivity(a -> assertEquals(View.GONE,a.findViewById(R.id.content).getVisibility()));
            await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c)); destination(s,R.id.login);
            s.onActivity(a -> ((NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host)).getNavController().navigate(R.id.create_group));
            destination(s,R.id.login);
            s.recreate(); destination(s,R.id.login);
            s.onActivity(a -> assertNull(((NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host)).getNavController().getPreviousBackStackEntry()));
        }
    }
}
