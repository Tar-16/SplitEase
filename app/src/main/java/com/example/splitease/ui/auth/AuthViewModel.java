package com.example.splitease.ui.auth;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.*;
import com.example.splitease.SplitEaseApplication;
import com.example.splitease.auth.*;
import com.example.splitease.domain.AccountValidation;
import com.example.splitease.model.User;
import com.example.splitease.repository.*;
import java.util.Arrays;
import java.util.concurrent.Executor;

/** Activity-scoped presentation state survives recreation; passwords are never retained. */
public final class AuthViewModel extends AndroidViewModel {
    public static final class FormState {
        public final boolean busy, saved;
        public final AuthException.Code error;
        FormState(boolean busy, AuthException.Code error, boolean saved) { this.busy=busy; this.error=error; this.saved=saved; }
    }
    private String formOwner;
    private final AuthService auth;
    private final Executor main;
    private final MutableLiveData<SessionState> session=new MutableLiveData<>(SessionState.restoring());
    private final MutableLiveData<FormState> form=new MutableLiveData<>(new FormState(false,null,false));
    private final ObservableQuery.Subscription subscription;
    public AuthViewModel(@NonNull Application application) {
        super(application);
        auth=((SplitEaseApplication)application).auth();
        Handler handler=new Handler(Looper.getMainLooper()); main=handler::post;
        subscription=auth.sessions().subscribe(main,new ObservableQuery.Observer<SessionState>() {
            @Override public void onChanged(SessionState value) { session.setValue(value); }
            @Override public void onError(Exception error) { session.setValue(SessionState.unavailable()); }
        });
    }
    public LiveData<SessionState> session() { return session; }
    public LiveData<FormState> form() { return form; }
    public boolean busy() { return form.getValue() != null && form.getValue().busy; }
    public void enterForm(String owner) { if (!owner.equals(formOwner)) { clearMessage(); formOwner=owner; } }
    public void clearMessage() { if (!busy()) form.setValue(new FormState(false,null,false)); }
    public boolean[] passwordChecklist(char[] password) { return AccountValidation.passwordChecklist(password); }
    private void error(AuthException.Code code) { form.setValue(new FormState(false,code,false)); }
    private <T> RepositoryCallback<T> completion(boolean showSaved) {
        return new RepositoryCallback<T>() {
            @Override public void onSuccess(T value) { form.setValue(new FormState(false,null,showSaved)); }
            @Override public void onError(Exception error) {
                AuthException.Code code=error instanceof AuthException ? ((AuthException)error).code : AuthException.Code.STORAGE;
                form.setValue(new FormState(false,code,false));
            }
        };
    }
    public void signup(String username,String email,char[] password,char[] confirmation) {
        try {
            if (busy()) return;
            if (AccountValidation.normalize(username).isEmpty()) { error(AuthException.Code.USERNAME_REQUIRED); return; }
            if (!AccountValidation.validEmail(email)) { error(AuthException.Code.INVALID_EMAIL); return; }
            if (!AccountValidation.strongPassword(password)) { error(AuthException.Code.WEAK_PASSWORD); return; }
            if (!Arrays.equals(password,confirmation)) { error(AuthException.Code.PASSWORD_MISMATCH); return; }
            form.setValue(new FormState(true,null,false));
            auth.register(username,email,password,main,completion(false));
        } finally { Arrays.fill(password,'\0'); Arrays.fill(confirmation,'\0'); }
    }
    public void login(String email,char[] password) {
        try {
            if (busy()) return;
            if (!AccountValidation.validEmail(email)) { error(AuthException.Code.INVALID_EMAIL); return; }
            if (password.length == 0) { error(AuthException.Code.INVALID_CREDENTIALS); return; }
            form.setValue(new FormState(true,null,false)); auth.login(email,password,main,completion(false));
        } finally { Arrays.fill(password,'\0'); }
    }
    public void logout() {
        if (busy()) return;
        form.setValue(new FormState(true,null,false)); auth.logout(main,completion(false));
    }
    public void saveUpi(String upi) {
        if (busy()) return;
        form.setValue(new FormState(true,null,false)); auth.updateUpi(upi,main,completion(true));
    }
    public void retryRestore() { clearMessage(); auth.restore(); }
    @Override protected void onCleared() { subscription.close(); }
}
