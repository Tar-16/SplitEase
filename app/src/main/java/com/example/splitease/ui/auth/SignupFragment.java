package com.example.splitease.ui.auth;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import androidx.annotation.*;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.R;
import com.example.splitease.auth.AuthException;
import com.example.splitease.databinding.FragmentSignupBinding;
import java.util.Arrays;

public final class SignupFragment extends Fragment {
    private FragmentSignupBinding binding;
    private AuthViewModel model;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup container,@Nullable Bundle state) {
        binding=FragmentSignupBinding.inflate(inflater,container,false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state) {
        model=new ViewModelProvider(requireActivity()).get(AuthViewModel.class);
        ViewCompat.setAccessibilityHeading(binding.heading,true);
        model.enterForm("signup");
        binding.password.setSaveFromParentEnabled(false);
        binding.confirmPassword.setSaveFromParentEnabled(false);
        binding.submit.setOnClickListener(v -> submit());
        binding.confirmPassword.setOnEditorActionListener((v,action,event) -> { if (action == EditorInfo.IME_ACTION_DONE) { submit(); return true; } return false; });
        binding.switchScreen.setOnClickListener(v -> {
            if (model.busy()) return;
            model.clearMessage(); AuthViews.hideKeyboard(view);
            NavHostFragment.findNavController(this).popBackStack(R.id.login,false);
        });
        binding.password.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { updateChecklist(); }
            @Override public void afterTextChanged(Editable editable) {}
        });
        updateChecklist();
        model.form().observe(getViewLifecycleOwner(),form -> {
            binding.submit.setEnabled(!form.busy); binding.switchScreen.setEnabled(!form.busy);
            binding.username.setEnabled(!form.busy); binding.email.setEnabled(!form.busy);
            binding.password.setEnabled(!form.busy); binding.confirmPassword.setEnabled(!form.busy);
            binding.progress.setVisibility(form.busy ? View.VISIBLE : View.GONE);
            binding.error.setVisibility(form.error == null ? View.GONE : View.VISIBLE);
            binding.error.setText(AuthViews.error(form.error));
            AuthException.Code code=form.error;
            binding.usernameLayout.setError(code == AuthException.Code.USERNAME_REQUIRED || code == AuthException.Code.DUPLICATE_USERNAME ? getString(AuthViews.error(code)) : null);
            binding.emailLayout.setError(code == AuthException.Code.INVALID_EMAIL || code == AuthException.Code.DUPLICATE_EMAIL ? getString(AuthViews.error(code)) : null);
            binding.passwordLayout.setError(code == AuthException.Code.WEAK_PASSWORD ? getString(AuthViews.error(code)) : null);
            binding.confirmPasswordLayout.setError(code == AuthException.Code.PASSWORD_MISMATCH ? getString(AuthViews.error(code)) : null);
        });
    }
    private void updateChecklist() {
        char[] password=AuthViews.password(binding.password.getText());
        boolean[] checks=model.passwordChecklist(password); Arrays.fill(password,'\0');
        TextView[] labels={binding.checkLength,binding.checkUpper,binding.checkLower,binding.checkNumber,binding.checkSpecial};
        int[] rules={R.string.password_length,R.string.password_upper,R.string.password_lower,R.string.password_number,R.string.password_special};
        for (int i=0;i<labels.length;i++) labels[i].setText(getString(checks[i] ? R.string.check_met : R.string.check_unmet,getString(rules[i])));
    }
    private void submit() {
        AuthViews.hideKeyboard(binding.getRoot());
        model.signup(AuthViews.text(binding.username.getText()),AuthViews.text(binding.email.getText()),
                AuthViews.password(binding.password.getText()),AuthViews.password(binding.confirmPassword.getText()));
    }
    @Override public void onDestroyView() {
        binding.password.setText(null); binding.confirmPassword.setText(null); binding=null; super.onDestroyView();
    }
}
