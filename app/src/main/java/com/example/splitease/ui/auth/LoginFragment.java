package com.example.splitease.ui.auth;

import android.os.Bundle;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.*;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.R;
import com.example.splitease.auth.AuthException;
import com.example.splitease.databinding.FragmentLoginBinding;

public final class LoginFragment extends Fragment {
    private FragmentLoginBinding binding;
    private AuthViewModel model;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup container,@Nullable Bundle state) {
        binding=FragmentLoginBinding.inflate(inflater,container,false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state) {
        model=new ViewModelProvider(requireActivity()).get(AuthViewModel.class);
        ViewCompat.setAccessibilityHeading(binding.heading,true);
        model.enterForm("login");
        com.example.splitease.debug.FirebaseAuthSandbox.attach((ViewGroup)binding.switchScreen.getParent(),NavHostFragment.findNavController(this));
        binding.password.setSaveFromParentEnabled(false);
        binding.submit.setOnClickListener(v -> submit());
        binding.password.setOnEditorActionListener((v,action,event) -> { if (action == EditorInfo.IME_ACTION_DONE) { submit(); return true; } return false; });
        binding.switchScreen.setOnClickListener(v -> {
            if (model.busy()) return;
            model.clearMessage(); AuthViews.hideKeyboard(view);
            NavHostFragment.findNavController(this).navigate(R.id.signup);
        });
        model.form().observe(getViewLifecycleOwner(),form -> {
            binding.submit.setEnabled(!form.busy); binding.switchScreen.setEnabled(!form.busy);
            binding.email.setEnabled(!form.busy); binding.password.setEnabled(!form.busy);
            binding.progress.setVisibility(form.busy ? View.VISIBLE : View.GONE);
            binding.error.setVisibility(form.error == null ? View.GONE : View.VISIBLE);
            binding.error.setText(AuthViews.error(form.error));
            binding.emailLayout.setError(form.error == AuthException.Code.INVALID_EMAIL ? getString(R.string.auth_invalid_email) : null);
        });
    }
    private void submit() {
        AuthViews.hideKeyboard(binding.getRoot());
        model.login(AuthViews.text(binding.email.getText()),AuthViews.password(binding.password.getText()));
    }
    @Override public void onDestroyView() { binding.password.setText(null); binding=null; super.onDestroyView(); }
}
