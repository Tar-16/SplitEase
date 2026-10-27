package com.example.splitease;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.example.splitease.data.SqliteRepositories;
import com.example.splitease.data.PreferencesSessionStore;
import com.example.splitease.auth.AuthService;
import com.example.splitease.auth.LocalAuthService;
import com.example.splitease.repository.*;

/** Composition root: creates the storage adapter without exposing SQL to screens. */
public final class SplitEaseApplication extends Application {
    private SqliteRepositories repositories;
    private LocalAuthService auth;
    private com.example.splitease.platform.receipts.ReceiptCaptureService receiptCapture;
    private com.example.splitease.reminders.RecurringReminders reminders;
    private volatile Exception initializationError;
    @Override public void onCreate() {
        super.onCreate();
        repositories = new SqliteRepositories(this);
        auth = new LocalAuthService(repositories, new PreferencesSessionStore(this));
        receiptCapture=new com.example.splitease.platform.receipts.ReceiptCaptureService(this);
        reminders=new com.example.splitease.reminders.RecurringReminders(this);
        Handler main = new Handler(Looper.getMainLooper());
        repositories.initialize(command -> main.post(command), new RepositoryCallback<Boolean>() {
            @Override public void onSuccess(Boolean result) { initializationError = null; }
            @Override public void onError(Exception error) {
                initializationError = error;
                Log.e("SplitEase", "Database initialization failed", error);
            }
        });
    }
    public com.example.splitease.reminders.RecurringReminders reminders() { return reminders; }
    public com.example.splitease.platform.receipts.ReceiptCaptureService receiptCapture() { return receiptCapture; }
    public AuthService auth() { return auth; }
    public UserRepository users() { return repositories; }
    public GroupRepository groups() { return repositories; }
    public ReceiptStorageRepository receipts() { return repositories; }
    public ExpenseRepository expenses() { return repositories; }
    public SettlementRepository settlements() { return repositories; }
    public RecurringBillRepository recurringBills() { return repositories; }
    public Exception initializationError() { return initializationError; }
}
