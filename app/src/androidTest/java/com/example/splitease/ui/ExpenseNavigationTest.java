package com.example.splitease.ui;

import android.os.Bundle;
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
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.example.splitease.util.InrFormatter;
import com.google.android.material.tabs.TabLayout;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExpenseNavigationTest {
    private SplitEaseApplication app;
    private AuthService auth;
    private User a,b,outsider;
    private String groupId;
    private interface Call<T> { void run(RepositoryCallback<T> callback); }
    private <T> T await(Call<T> call) throws Exception {
        CompletableFuture<T> future=new CompletableFuture<>();
        call.run(new RepositoryCallback<T>() {
            @Override public void onSuccess(T value) { future.complete(value); }
            @Override public void onError(Exception error) { future.completeExceptionally(error); }
        }); return future.get(30,TimeUnit.SECONDS);
    }
    private User register(String prefix) throws Exception {
        String name=prefix+UUID.randomUUID().toString().substring(0,8);
        User user=await((RepositoryCallback<User> c) -> auth.register(name,name+"@example.com","Strong1!".toCharArray(),Runnable::run,c));
        await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c)); return user;
    }
    @Before public void setup() throws Exception {
        app=ApplicationProvider.getApplicationContext(); auth=app.auth();
        await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c));
        a=register("payer"); b=register("member"); outsider=register("outside");
        groupId=UUID.randomUUID().toString();
        await((RepositoryCallback<Group> c) -> app.groups().createGroup(a.id,new Group(groupId,"Expense test",Group.Type.FLAT,a.id,System.currentTimeMillis()),Collections.singletonList(b.id),Runnable::run,c));
        login(a);
    }
    private void login(User user) throws Exception { await((RepositoryCallback<User> c) -> auth.login(user.email,"Strong1!".toCharArray(),Runnable::run,c)); }
    @After public void cleanup() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c));
    }
    private int destination(MainActivity a) { return ((NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host)).getNavController().getCurrentDestination().getId(); }
    private void waitFor(ActivityScenario<MainActivity> scenario,Predicate<MainActivity> condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until) {
            AtomicBoolean ready=new AtomicBoolean(); scenario.onActivity(activity -> ready.set(condition.test(activity)));
            if(ready.get()) return; Thread.sleep(50);
        } fail("Timed out waiting for expense UI");
    }
    private void destination(ActivityScenario<MainActivity> s,int id) throws Exception { waitFor(s,activity -> destination(activity)==id && activity.findViewById(R.id.main_content).getVisibility()==View.VISIBLE); }
    private String text(MainActivity a,int id) { return ((TextView)a.findViewById(id)).getText().toString(); }
    private void fill(MainActivity a,int id,String text) { ((EditText)a.findViewById(id)).setText(text); }
    private void tab(ActivityScenario<MainActivity> s,int index) { s.onActivity(a -> { TabLayout tabs=a.findViewById(R.id.tabs); tabs.selectTab(tabs.getTabAt(index)); }); }
    private void openGroup(ActivityScenario<MainActivity> s) throws Exception {
        destination(s,R.id.groups); waitFor(s,a -> a.findViewById(R.id.open)!=null);
        s.onActivity(a -> a.findViewById(R.id.open).performClick()); destination(s,R.id.group_detail);
        waitFor(s,a -> a.findViewById(R.id.content).getVisibility()==View.VISIBLE);
    }
    private void add(ActivityScenario<MainActivity> s) throws Exception {
        tab(s,1); s.onActivity(a -> a.findViewById(R.id.add_expense).performClick()); destination(s,R.id.expense_screen);
        waitFor(s,a -> ((ViewGroup)a.findViewById(R.id.participants)).getChildCount()==2);
    }
    private void confirmDelete() throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) {
            AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if(root!=null) for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("Delete expense"))
                if(node.isClickable() && "android.widget.Button".contentEquals(node.getClassName()) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            Thread.sleep(100);
        } fail("Delete confirmation missing");
    }
    private void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(app.getExternalFilesDir("verification5"),name+".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
        } finally { bitmap.recycle(); }
    }
    @Test public void addExpenseActionRemainsVisibleAndScopedToExpensesTab() throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)) {
            openGroup(s);
            s.onActivity(activity -> assertFalse(activity.findViewById(R.id.add_expense).isShown()));
            tab(s,1);
            s.onActivity(activity -> { assertEquals(View.VISIBLE,activity.findViewById(R.id.expenses_empty).getVisibility()); assertAddAction(activity); });
            screenshot("add-action-empty-light");
            s.onActivity(activity -> activity.findViewById(R.id.add_expense).performClick()); destination(s,R.id.expense_screen);
            s.onActivity(activity -> { NavHostFragment host=(NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);assertEquals(groupId,host.getChildFragmentManager().getPrimaryNavigationFragment().requireArguments().getString("groupId"));activity.findViewById(R.id.back).performClick(); });
            destination(s,R.id.group_detail);
            for(int i=0;i<15;i++) {
                String id=UUID.randomUUID().toString();Expense expense=new Expense(id,groupId,"Test expense "+i,100,a.id,a.id,"Other",System.currentTimeMillis(),Collections.singletonList(new ExpenseSplit(id,a.id,100)));
                await((RepositoryCallback<Expense> c) -> app.expenses().saveExpense(a.id,expense,Runnable::run,c));
            }
            tab(s,1);waitFor(s,activity -> ((ViewGroup)activity.findViewById(R.id.expense_list)).getChildCount()>=15);
            waitFor(s,activity -> { androidx.core.widget.NestedScrollView scroll=activity.findViewById(R.id.detail_scroll);return scroll.getChildAt(0).getHeight()>scroll.getHeight()*2; });
            s.onActivity(activity -> { androidx.core.widget.NestedScrollView scroll=activity.findViewById(R.id.detail_scroll);scroll.scrollTo(0,scroll.getChildAt(0).getHeight()); });
            waitFor(s,activity -> activity.findViewById(R.id.detail_scroll).getScrollY()>0);
            s.onActivity(this::assertAddAction); screenshot("add-action-long-list-light");
            tab(s,0);s.onActivity(activity -> assertFalse(activity.findViewById(R.id.add_expense).isShown()));
            tab(s,2);s.onActivity(activity -> assertFalse(activity.findViewById(R.id.add_expense).isShown()));
            tab(s,1);s.recreate();destination(s,R.id.group_detail);waitFor(s,activity -> activity.findViewById(R.id.add_expense).isShown());
            s.onActivity(activity -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            waitFor(s,activity -> activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE&&activity.findViewById(R.id.add_expense)!=null&&activity.findViewById(R.id.add_expense).isShown());
            s.onActivity(this::assertAddAction);screenshot("add-action-landscape-light");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
            waitFor(s,activity -> (activity.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES&&activity.findViewById(R.id.add_expense)!=null&&activity.findViewById(R.id.add_expense).isShown());
            s.onActivity(this::assertAddAction);screenshot("add-action-landscape-dark");
            s.onActivity(activity -> activity.findViewById(R.id.add_expense).performClick());destination(s,R.id.expense_screen);
            s.onActivity(activity -> activity.findViewById(R.id.back).performClick());destination(s,R.id.group_detail);
        }
    }
    private void assertAddAction(MainActivity activity) {
        View button=activity.findViewById(R.id.add_expense),tabs=activity.findViewById(R.id.tabs);android.graphics.Rect visible=new android.graphics.Rect();
        assertTrue(button instanceof com.google.android.material.button.MaterialButton);assertTrue(button.getGlobalVisibleRect(visible));assertEquals(button.getHeight(),visible.height());
        assertTrue(button.getWidth()>=tabs.getWidth());assertTrue(button.getTop()>=tabs.getBottom());assertTrue(button.isEnabled());
    }
    @Test public void expenseCreateEditDeleteBalancesRecreationAndAccountSwitch() throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)) {
            openGroup(s); add(s);
            s.onActivity(activity -> {
                fill(activity,R.id.expense_title,"Lunch"); fill(activity,R.id.expense_amount,"100.01");
                ((Spinner)activity.findViewById(R.id.category)).setSelection(0);
                ViewGroup people=activity.findViewById(R.id.participants);
                for(int i=0;i<people.getChildCount();i++) ((CompoundButton)people.getChildAt(i)).setChecked(true);
                assertTrue(text(activity,R.id.shares).contains(InrFormatter.format(5001)));
                assertTrue(text(activity,R.id.shares).contains(InrFormatter.format(5000)));
            });
            s.recreate(); destination(s,R.id.expense_screen);
            waitFor(s,activity -> ((ViewGroup)activity.findViewById(R.id.participants)).getChildCount()==2);
            s.onActivity(activity -> {
                assertEquals("Lunch",text(activity,R.id.expense_title)); assertEquals("100.01",text(activity,R.id.expense_amount));
                assertTrue(((CompoundButton)((ViewGroup)activity.findViewById(R.id.participants)).getChildAt(0)).isChecked());
            }); screenshot("expense-editor-light");
            s.onActivity(activity -> activity.findViewById(R.id.save).performClick()); destination(s,R.id.group_detail);
            waitFor(s,activity -> text(activity,R.id.total).contains(InrFormatter.format(10001)));
            s.onActivity(activity -> activity.findViewById(R.id.open).performClick()); destination(s,R.id.expense_screen);
            waitFor(s,activity -> activity.findViewById(R.id.edit).getVisibility()==View.VISIBLE);
            s.onActivity(activity -> { assertFalse(activity.findViewById(R.id.expense_amount).isEnabled()); activity.findViewById(R.id.edit).performClick(); fill(activity,R.id.expense_amount,"200.00"); activity.findViewById(R.id.save).performClick(); });
            destination(s,R.id.group_detail); waitFor(s,activity -> text(activity,R.id.total).contains(InrFormatter.format(20000)));
            tab(s,2); waitFor(s,activity -> ((ViewGroup)activity.findViewById(R.id.balance_list)).getChildCount()==2);
            s.onActivity(activity -> assertEquals(activity.getString(R.string.expense_your_balance,activity.getString(R.string.balance_owed,InrFormatter.format(10000))),text(activity,R.id.your_balance)));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
            destination(s,R.id.group_detail); waitFor(s,activity -> ((ViewGroup)activity.findViewById(R.id.balance_list)).getChildCount()==2);
            screenshot("balances-dark");
            s.onActivity(activity -> activity.findViewById(R.id.back).performClick()); destination(s,R.id.groups);
            waitFor(s,activity -> activity.findViewById(R.id.total)!=null && text(activity,R.id.total).contains(InrFormatter.format(20000)));
            await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c)); destination(s,R.id.login);
            login(b); openGroup(s); tab(s,2);
            s.onActivity(activity -> assertEquals(activity.getString(R.string.expense_your_balance,activity.getString(R.string.balance_owes,InrFormatter.format(10000))),text(activity,R.id.your_balance)));
            tab(s,1); screenshot("expenses-dark");
            s.onActivity(activity -> activity.findViewById(R.id.open).performClick()); destination(s,R.id.expense_screen);
            waitFor(s,activity -> activity.findViewById(R.id.delete).getVisibility()==View.VISIBLE);
            s.onActivity(activity -> activity.findViewById(R.id.delete).performClick()); confirmDelete(); destination(s,R.id.group_detail);
            waitFor(s,activity -> activity.findViewById(R.id.expenses_empty).getVisibility()==View.VISIBLE && text(activity,R.id.total).contains(InrFormatter.format(0)));
            await((RepositoryCallback<Boolean> c) -> auth.logout(Runnable::run,c)); destination(s,R.id.login);
            login(outsider); destination(s,R.id.groups);
            s.onActivity(activity -> { Bundle args=new Bundle(); args.putString("groupId",groupId); ((NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host)).getNavController().navigate(R.id.expense_screen,args); });
            destination(s,R.id.expense_screen); waitFor(s,activity -> activity.findViewById(R.id.error).getVisibility()==View.VISIBLE);
            s.onActivity(activity -> assertEquals(View.GONE,activity.findViewById(R.id.form).getVisibility()));
        }
    }
    @Test public void departedPayerAndParticipantRemainVisibleAndEditable() throws Exception {
        String id=UUID.randomUUID().toString();
        Expense old=new Expense(id,groupId,"Historical bill",101,b.id,a.id,"Bills",100,
                Collections.singletonList(new ExpenseSplit(id,b.id,101)));
        await((RepositoryCallback<Expense> c) -> app.expenses().saveExpense(a.id,old,Runnable::run,c));
        await((RepositoryCallback<Boolean> c) -> app.groups().leaveGroup(b.id,groupId,System.currentTimeMillis(),Runnable::run,c));
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)) {
            openGroup(s); tab(s,1);
            s.onActivity(activity -> activity.findViewById(R.id.open).performClick()); destination(s,R.id.expense_screen);
            waitFor(s,activity -> activity.findViewById(R.id.edit).getVisibility()==View.VISIBLE);
            s.onActivity(activity -> {
                assertTrue(((Spinner)activity.findViewById(R.id.payer)).getSelectedItem().toString().contains("former member"));
                assertTrue(text(activity,R.id.shares).contains(b.username));
                activity.findViewById(R.id.edit).performClick(); fill(activity,R.id.expense_amount,"2.01");
            });
            s.recreate(); destination(s,R.id.expense_screen);
            waitFor(s,activity -> ((ViewGroup)activity.findViewById(R.id.participants)).getChildCount()==2);
            s.onActivity(activity -> {
                assertEquals("2.01",text(activity,R.id.expense_amount));
                assertTrue(text(activity,R.id.shares).contains(InrFormatter.format(201)));
                activity.findViewById(R.id.save).performClick();
            }); destination(s,R.id.group_detail);
            waitFor(s,activity -> text(activity,R.id.total).contains(InrFormatter.format(201)));
            tab(s,2); waitFor(s,activity -> ((ViewGroup)activity.findViewById(R.id.balance_list)).getChildCount()==2);
            s.onActivity(activity -> assertTrue(text(activity,R.id.your_balance).contains(activity.getString(R.string.balance_settled))));
        }
    }
    @Test public void invalidAmountsAndEmptyParticipantsCannotBeSaved() throws Exception {
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)) {
            openGroup(s); add(s);
            s.onActivity(activity -> {
                fill(activity,R.id.expense_title,"Solo bill"); fill(activity,R.id.expense_amount,"10.001"); activity.findViewById(R.id.save).performClick();
                assertEquals(View.VISIBLE,activity.findViewById(R.id.error).getVisibility());
                fill(activity,R.id.expense_amount,"0"); activity.findViewById(R.id.save).performClick(); assertEquals(View.VISIBLE,activity.findViewById(R.id.error).getVisibility());
                fill(activity,R.id.expense_amount,"0.01"); activity.findViewById(R.id.save).performClick(); assertEquals(View.VISIBLE,activity.findViewById(R.id.error).getVisibility());
                ViewGroup people=activity.findViewById(R.id.participants);
                for(int i=0;i<people.getChildCount();i++) if(a.id.equals(people.getChildAt(i).getTag())) ((CompoundButton)people.getChildAt(i)).setChecked(true);
                assertTrue(text(activity,R.id.shares).contains(InrFormatter.format(1)));
                activity.findViewById(R.id.save).performClick();
            }); destination(s,R.id.group_detail); waitFor(s,activity -> text(activity,R.id.total).contains(InrFormatter.format(1)));
            s.onActivity(activity -> assertTrue(text(activity,R.id.your_balance).contains(activity.getString(R.string.balance_settled))));
        }
    }
}
