package com.example.splitease.ui;

import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.R;
import com.example.splitease.SplitEaseApplication;
import com.example.splitease.auth.*;
import com.example.splitease.repository.*;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NavigationTest {
    private AuthService auth;
    @Before public void signOut() throws Exception {
        auth=((SplitEaseApplication)ApplicationProvider.getApplicationContext()).auth();
        CompletableFuture<Boolean> future=new CompletableFuture<>();
        auth.logout(Runnable::run,new RepositoryCallback<Boolean>() {
            @Override public void onSuccess(Boolean value) { future.complete(value); }
            @Override public void onError(Exception error) { future.completeExceptionally(error); }
        }); future.get(20,TimeUnit.SECONDS);
    }
    @After public void resetThemeAndSession() throws Exception {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        signOut();
    }
    private int destination(MainActivity activity) {
        NavHostFragment host=(NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);
        return host == null || host.getNavController().getCurrentDestination() == null ? 0 : host.getNavController().getCurrentDestination().getId();
    }
    private void waitFor(ActivityScenario<MainActivity> scenario,Predicate<MainActivity> condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime()<until) {
            AtomicBoolean ready=new AtomicBoolean(); scenario.onActivity(a -> ready.set(condition.test(a)));
            if (ready.get()) return; Thread.sleep(50);
        }
        fail("Timed out waiting for UI state");
    }
    private void destination(ActivityScenario<MainActivity> scenario,int id) throws Exception { waitFor(scenario,a -> destination(a)==id && a.findViewById(R.id.main_content).getVisibility()==View.VISIBLE); }
    private void fill(MainActivity activity,int id,String value) { ((EditText)activity.findViewById(id)).setText(value); }
    private void signup(ActivityScenario<MainActivity> scenario,String name,String email) throws Exception {
        scenario.onActivity(a -> a.findViewById(R.id.switch_screen).performClick()); destination(scenario,R.id.signup);
        scenario.onActivity(a -> {
            fill(a,R.id.username,name); fill(a,R.id.email,email); fill(a,R.id.password,"Strong1!"); fill(a,R.id.confirm_password,"Strong1!");
            assertTrue(((TextView)a.findViewById(R.id.check_special)).getText().toString().startsWith("✓"));
            a.findViewById(R.id.submit).performClick();
            assertFalse(a.findViewById(R.id.submit).isEnabled());
        }); destination(scenario,R.id.groups);
    }
    private void profile(ActivityScenario<MainActivity> scenario,String email) throws Exception {
        scenario.onActivity(a -> ((BottomNavigationView)a.findViewById(R.id.bottom_navigation)).setSelectedItemId(R.id.profile));
        destination(scenario,R.id.profile); waitFor(scenario,a -> ((TextView)a.findViewById(R.id.email)).getText().toString().equals(email));
    }
    private void logout(ActivityScenario<MainActivity> scenario) throws Exception {
        waitFor(scenario,a -> a.findViewById(R.id.logout).isEnabled());
        scenario.onActivity(a -> a.findViewById(R.id.logout).performClick()); destination(scenario,R.id.login);
    }
    @Test public void signupProfileRecreationLogoutLoginAndSecondAccountInBothThemes() throws Exception {
        String suffix=UUID.randomUUID().toString(); String first="first"+suffix, second="second"+suffix;
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            destination(scenario,R.id.login);
            scenario.onActivity(a -> assertEquals(View.GONE,a.findViewById(R.id.bottom_navigation).getVisibility()));
            signup(scenario,first,first+"@example.com");
            for (int id : new int[]{R.id.recurring,R.id.profile,R.id.groups}) {
                scenario.onActivity(a -> ((BottomNavigationView)a.findViewById(R.id.bottom_navigation)).setSelectedItemId(id)); destination(scenario,id);
            }
            profile(scenario,first+"@example.com");
            scenario.onActivity(a -> { assertEquals(first,((TextView)a.findViewById(R.id.username)).getText().toString()); fill(a,R.id.upi,"first@upi"); a.findViewById(R.id.save_upi).performClick(); });
            waitFor(scenario,a -> a.findViewById(R.id.saved).getVisibility()==View.VISIBLE);
            scenario.recreate(); destination(scenario,R.id.profile);
            scenario.onActivity(a -> assertEquals("first@upi",((EditText)a.findViewById(R.id.upi)).getText().toString()));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
            destination(scenario,R.id.profile);
            logout(scenario);
            // A programmatic attempt to enter protected content is redirected as well.
            scenario.onActivity(a -> ((NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host)).getNavController().navigate(R.id.groups));
            destination(scenario,R.id.login);
            signup(scenario,second,second+"@example.com"); profile(scenario,second+"@example.com");
            scenario.onActivity(a -> assertEquals("",((EditText)a.findViewById(R.id.upi)).getText().toString()));
            logout(scenario);
            scenario.onActivity(a -> { fill(a,R.id.email,first+"@example.com"); fill(a,R.id.password,"Strong1!"); a.findViewById(R.id.submit).performClick(); });
            destination(scenario,R.id.groups); profile(scenario,first+"@example.com");
            logout(scenario); scenario.recreate(); destination(scenario,R.id.login);
            scenario.onActivity(a -> {
                NavHostFragment host=(NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host);
                assertNull(host.getNavController().getPreviousBackStackEntry());
                a.getOnBackPressedDispatcher().onBackPressed();
            });
            // Android 12+ may background the root task instead of destroying its Activity.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        // A new Activity launch after logout must not restore a saved authenticated tab.
        try (ActivityScenario<MainActivity> reopened=ActivityScenario.launch(MainActivity.class)) { destination(reopened,R.id.login); }
    }
    @Test public void confirmationAndEmailErrorsAreVisibleAndPasswordIsNotSavedOnRecreation() throws Exception {
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            destination(scenario,R.id.login); scenario.onActivity(a -> a.findViewById(R.id.switch_screen).performClick()); destination(scenario,R.id.signup);
            scenario.onActivity(a -> {
                fill(a,R.id.username,"valid"); fill(a,R.id.email,"not-email"); fill(a,R.id.password,"Strong1!"); fill(a,R.id.confirm_password,"Different1!");
                a.findViewById(R.id.submit).performClick();
                assertEquals(a.getString(R.string.auth_invalid_email),((TextView)a.findViewById(R.id.error)).getText().toString());
                fill(a,R.id.email,"valid@example.com"); a.findViewById(R.id.submit).performClick();
                assertEquals(a.getString(R.string.auth_mismatch),((TextView)a.findViewById(R.id.error)).getText().toString());
            });
            scenario.recreate(); destination(scenario,R.id.signup);
            scenario.onActivity(a -> { assertEquals("",((EditText)a.findViewById(R.id.password)).getText().toString()); assertEquals("",((EditText)a.findViewById(R.id.confirm_password)).getText().toString()); });
        }
    }
}
