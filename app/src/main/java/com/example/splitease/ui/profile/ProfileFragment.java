package com.example.splitease.ui.profile;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.*;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.example.splitease.auth.SessionState;
import com.example.splitease.databinding.FragmentProfileBinding;
import com.example.splitease.ui.auth.*;

public final class ProfileFragment extends Fragment {
    private FragmentProfileBinding binding;
    private String renderedUserId;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup container,@Nullable Bundle state) {
        binding=FragmentProfileBinding.inflate(inflater,container,false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state) {
        AuthViewModel model=new ViewModelProvider(requireActivity()).get(AuthViewModel.class);
        com.example.splitease.debug.FirebaseDiagnostics.attach((ViewGroup)binding.heading.getParent(),getViewLifecycleOwner());
        com.example.splitease.debug.FirebaseAuthSandbox.attach((ViewGroup)binding.heading.getParent(),androidx.navigation.fragment.NavHostFragment.findNavController(this));
        ViewCompat.setAccessibilityHeading(binding.heading,true);
        model.enterForm("profile");
        binding.saveUpi.setOnClickListener(v -> { AuthViews.hideKeyboard(view); model.saveUpi(AuthViews.text(binding.upi.getText())); });
        binding.upi.setOnEditorActionListener((v,action,event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) { binding.saveUpi.performClick(); return true; } return false;
        });
        binding.upi.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { if (binding.upi.hasFocus()) model.clearMessage(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        binding.logout.setOnClickListener(v -> { AuthViews.hideKeyboard(view); model.logout(); });
        model.session().observe(getViewLifecycleOwner(),session -> {
            if (session.status != SessionState.Status.SIGNED_IN) {
                binding.username.setText(null); binding.email.setText(null); binding.upi.setText(null); return;
            }
            binding.username.setText(session.user.username); binding.email.setText(session.user.email);
            if (!session.user.id.equals(renderedUserId) || !binding.upi.hasFocus()) binding.upi.setText(session.user.upiId);
            renderedUserId=session.user.id;
        });
        model.form().observe(getViewLifecycleOwner(),form -> {
            binding.saveUpi.setEnabled(!form.busy); binding.logout.setEnabled(!form.busy); binding.upi.setEnabled(!form.busy);
            binding.progress.setVisibility(form.busy ? View.VISIBLE : View.GONE);
            binding.error.setVisibility(form.error == null ? View.GONE : View.VISIBLE); binding.error.setText(AuthViews.error(form.error));
            binding.saved.setVisibility(form.saved ? View.VISIBLE : View.GONE);
        });
    }
    @Override public void onDestroyView() { binding=null; renderedUserId=null; super.onDestroyView(); }
}
