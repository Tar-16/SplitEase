package com.example.splitease.debug;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.*;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.R;
import com.example.splitease.auth.SessionState;
import com.example.splitease.databinding.*;
import com.example.splitease.ui.auth.*;
import java.util.Arrays;

/** Same validation/presentation model and signup fields, isolated from Activity's local auth model. */
public final class FirebaseAuthSandboxFragment extends Fragment {
    private FragmentFirebaseAuthSandboxBinding root;private FragmentSignupBinding binding;
    private AuthViewModel model;private Button reset;private boolean signup=true,signedIn,unavailable,ready,pending;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle state){root=FragmentFirebaseAuthSandboxBinding.inflate(inflater,parent,false);binding=root.form;return root.getRoot();}
    @Override public void onViewCreated(@NonNull View view,Bundle state){
        if(state!=null)signup=state.getBoolean("signup",true);
        model=new ViewModelProvider(this,new ViewModelProvider.Factory(){@NonNull @Override public <T extends ViewModel>T create(@NonNull Class<T> type){return type.cast(new AuthViewModel(requireActivity().getApplication(),FirebaseAuthSandbox.service(requireContext())));}}).get(AuthViewModel.class);
        root.cloudGroupsOpen.setOnClickListener(v->NavHostFragment.findNavController(this).navigate(R.id.firebase_cloud_groups));
        root.back.setOnClickListener(v->NavHostFragment.findNavController(this).popBackStack());
        root.restoreRetry.setOnClickListener(v->model.retryRestore());
        binding.heading.setText(R.string.cloud_sandbox_title);binding.subtitle.setText(R.string.cloud_sandbox_note);binding.localNote.setText(R.string.cloud_sandbox_note);
        ViewCloudPasswordResetBinding resetBinding=ViewCloudPasswordResetBinding.inflate(getLayoutInflater(),(ViewGroup)binding.localNote.getParent(),true);reset=resetBinding.cloudReset;
        binding.password.setSaveFromParentEnabled(false);binding.confirmPassword.setSaveFromParentEnabled(false);
        binding.password.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){checklist();}public void afterTextChanged(Editable text){}});
        binding.submit.setOnClickListener(v->{
            AuthViews.hideKeyboard(view);
            if(signedIn||unavailable)model.logout();
            else if(pending)model.completeProfile(AuthViews.text(binding.username.getText()));
            else if(signup)model.signup(AuthViews.text(binding.username.getText()),AuthViews.text(binding.email.getText()),AuthViews.password(binding.password.getText()),AuthViews.password(binding.confirmPassword.getText()));
            else model.login(AuthViews.text(binding.email.getText()),AuthViews.password(binding.password.getText()));
            binding.password.setText(null);binding.confirmPassword.setText(null);
        });
        binding.switchScreen.setOnClickListener(v->{if(model.busy())return;signup=!signup;binding.password.setText(null);binding.confirmPassword.setText(null);model.clearMessage();render();});
        reset.setOnClickListener(v->{binding.password.setText(null);binding.confirmPassword.setText(null);if(pending)model.logout();else model.resetPassword(AuthViews.text(binding.email.getText()));});
        model.form().observe(getViewLifecycleOwner(),form->{
            binding.error.setVisibility(form.error==null&&!form.resetSent?View.GONE:View.VISIBLE);binding.error.setText(form.resetSent?R.string.cloud_reset_sent:AuthViews.error(form.error));binding.progress.setVisibility(form.busy?View.VISIBLE:View.GONE);
            binding.submit.setEnabled(!form.busy);binding.switchScreen.setEnabled(!form.busy);reset.setEnabled(!form.busy);
            binding.username.setEnabled(!form.busy);binding.email.setEnabled(!form.busy);binding.password.setEnabled(!form.busy);binding.confirmPassword.setEnabled(!form.busy);render();
        });
        model.session().observe(getViewLifecycleOwner(),session->{
            ready=session.status!=SessionState.Status.RESTORING;unavailable=session.status==SessionState.Status.UNAVAILABLE;root.restoreRetry.setVisibility(unavailable?View.VISIBLE:View.GONE);
            pending=session.status==SessionState.Status.PROFILE_REQUIRED;
            signedIn=session.status==SessionState.Status.SIGNED_IN&&session.backend==SessionState.Backend.FIREBASE;
            if(signedIn)binding.subtitle.setText(getString(R.string.cloud_identity,session.user.id,session.user.email,session.user.username));else binding.subtitle.setText(pending?R.string.auth_profile_required:unavailable?R.string.session_unavailable:R.string.cloud_sandbox_note);
            render();
        });
        checklist();model.retryRestore();
    }
    private void render(){
        root.cloudGroupsOpen.setVisibility(signedIn?View.VISIBLE:View.GONE);root.cloudGroupsOpen.setEnabled(ready&&!model.busy());
        boolean enabled=ready&&!model.busy();binding.submit.setEnabled(enabled);binding.switchScreen.setEnabled(enabled);reset.setEnabled(enabled);
        binding.submit.setText(signedIn||unavailable?R.string.logout_action:pending?R.string.cloud_complete_profile:signup?R.string.signup_action:R.string.login_action);
        binding.switchScreen.setText(signup?R.string.to_login:R.string.to_signup);binding.switchScreen.setVisibility(signedIn||unavailable||pending?View.GONE:View.VISIBLE);
        binding.usernameLayout.setVisibility(!signedIn&&!unavailable&&(signup||pending)?View.VISIBLE:View.GONE);binding.emailLayout.setVisibility(signedIn||unavailable||pending?View.GONE:View.VISIBLE);binding.passwordLayout.setVisibility(signedIn||unavailable||pending?View.GONE:View.VISIBLE);binding.confirmPasswordLayout.setVisibility(!signedIn&&!unavailable&&!pending&&signup?View.VISIBLE:View.GONE);
        binding.rulesTitle.setVisibility(!signedIn&&!unavailable&&!pending&&signup?View.VISIBLE:View.GONE);for(TextView label:labels())label.setVisibility(!signedIn&&!unavailable&&!pending&&signup?View.VISIBLE:View.GONE);reset.setText(pending?R.string.logout_action:R.string.cloud_reset);reset.setVisibility(pending||(!signedIn&&!unavailable&&!signup)?View.VISIBLE:View.GONE);
    }
    private TextView[] labels(){return new TextView[]{binding.checkLength,binding.checkUpper,binding.checkLower,binding.checkNumber,binding.checkSpecial};}
    private void checklist(){char[] password=AuthViews.password(binding.password.getText());boolean[] checks=model.passwordChecklist(password);Arrays.fill(password,'\0');int[] rules={R.string.password_length,R.string.password_upper,R.string.password_lower,R.string.password_number,R.string.password_special};TextView[] labels=labels();for(int i=0;i<labels.length;i++)labels[i].setText(getString(checks[i]?R.string.check_met:R.string.check_unmet,getString(rules[i])));}
    @Override public void onResume(){super.onResume();if(model!=null)model.retryRestore();}
    @Override public void onSaveInstanceState(@NonNull Bundle state){state.putBoolean("signup",signup);super.onSaveInstanceState(state);}
    @Override public void onDestroyView(){binding.password.setText(null);binding.confirmPassword.setText(null);binding=null;root=null;reset=null;super.onDestroyView();}
}
