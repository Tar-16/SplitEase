package com.example.splitease.ui;

import android.os.Bundle;
import android.view.View;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.*;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;
import com.example.splitease.R;
import com.example.splitease.auth.SessionState;
import com.example.splitease.databinding.ActivityMainBinding;
import com.example.splitease.ui.auth.AuthViewModel;
import java.util.Objects;

/** Session-driven navigation: no credential or persistence work lives in the Activity. */
public final class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;
    private NavController navigation;
    private SessionState session=SessionState.restoring();
    private String navigationUserId;
    private boolean synchronizing;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding=ActivityMainBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        navigationUserId=savedInstanceState == null ? null : savedInstanceState.getString("navigation_user_id");
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, windowInsets) -> {
            Insets bars=windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard=windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());
        NavHostFragment host=(NavHostFragment)getSupportFragmentManager().findFragmentById(R.id.nav_host);
        if (host == null) throw new IllegalStateException("Navigation host missing");
        navigation=host.getNavController();
        NavigationUI.setupWithNavController(binding.bottomNavigation,navigation);
        navigation.addOnDestinationChangedListener((controller,destination,args) -> synchronizeNavigation());
        if (navigation.getCurrentDestination() == null) {
            // Restore Navigation's pending stack behind the startup gate. Identity is checked below.
            NavGraph initial=navigation.getNavInflater().inflate(R.navigation.main_nav_graph);
            initial.setStartDestination(navigationUserId == null ? R.id.login : R.id.groups);
            navigation.setGraph(initial);
        }
        AuthViewModel model=new ViewModelProvider(this).get(AuthViewModel.class);
        binding.retry.setOnClickListener(view -> model.retryRestore());
        model.session().observe(this,value -> { session=value; synchronizeNavigation(); });
    }
    private boolean isHome(int id) { return id == R.id.groups || id == R.id.recurring || id == R.id.profile; }
    private void synchronizeNavigation() {
        if (synchronizing) return;
        boolean ready=session.status == SessionState.Status.SIGNED_IN || session.status == SessionState.Status.SIGNED_OUT;
        binding.mainContent.setVisibility(ready ? View.VISIBLE : View.INVISIBLE);
        binding.startup.setVisibility(ready ? View.GONE : View.VISIBLE);
        boolean unavailable=session.status == SessionState.Status.UNAVAILABLE;
        binding.startupProgress.setVisibility(unavailable ? View.GONE : View.VISIBLE);
        binding.startupMessage.setText(unavailable ? R.string.session_unavailable : R.string.restoring_session);
        binding.retry.setVisibility(unavailable ? View.VISIBLE : View.GONE);
        if (!ready) { binding.bottomNavigation.setVisibility(View.GONE); return; }
        boolean authenticated=session.status == SessionState.Status.SIGNED_IN;
        String userId=authenticated ? session.user.id : null;
        NavDestination current=navigation.getCurrentDestination();
        boolean allowed=current != null && (authenticated ? (isHome(current.getId()) || current.getId()==R.id.create_group || current.getId()==R.id.group_detail || current.getId()==R.id.expense_screen) : current.getId()==R.id.login || current.getId()==R.id.signup);
        if (!allowed || !Objects.equals(navigationUserId,userId)) {
            binding.mainContent.setVisibility(View.INVISIBLE);
            synchronizing=true;
            try {
                if (current != null) {
                    for (int id : new int[]{R.id.groups,R.id.recurring,R.id.profile,R.id.login,R.id.signup,R.id.create_group,R.id.group_detail,R.id.expense_screen}) navigation.clearBackStack(id);
                }
                int target=authenticated ? R.id.groups : R.id.login;
                NavGraph graph=navigation.getNavInflater().inflate(R.navigation.main_nav_graph);
                graph.setStartDestination(target);
                navigation.setGraph(graph);
                // Also discards any pending restored stack from a different or signed-out account.
                navigation.navigate(target,null,new NavOptions.Builder().setPopUpTo(R.id.main_nav_graph,true).setLaunchSingleTop(true).build());
                navigationUserId=userId;
            } finally { synchronizing=false; }
            binding.mainContent.setVisibility(View.VISIBLE);
        }
        binding.bottomNavigation.setVisibility(authenticated && navigation.getCurrentDestination()!=null && isHome(navigation.getCurrentDestination().getId()) ? View.VISIBLE : View.GONE);
    }
    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putString("navigation_user_id",navigationUserId); super.onSaveInstanceState(outState);
    }
}
