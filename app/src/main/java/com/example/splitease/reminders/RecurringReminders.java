package com.example.splitease.reminders;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.*;
import com.example.splitease.*;
import com.example.splitease.R;
import com.example.splitease.auth.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.example.splitease.ui.MainActivity;
import com.example.splitease.ui.expenses.ExpenseUi;
import java.util.*;
import java.util.concurrent.*;

/** Application-owned scheduler. No UI code or passwords; one active local recipient at a time. */
public final class RecurringReminders {
    public static final String CHANNEL="shared_bills", TAG="recurring-reminders", PERIODIC="recurring-periodic", NOW="recurring-refresh";
    private final Context context;
    private final Object lock=new Object();
    private final CountDownLatch restored=new CountDownLatch(1);
    private volatile String actor;
    private ObservableQuery.Subscription watch;
    public RecurringReminders(SplitEaseApplication app) {
        context=app;
        clearNotifications();
        if(Build.VERSION.SDK_INT>=26) {
            NotificationChannel channel=new NotificationChannel(CHANNEL,app.getString(R.string.bill_notification_channel),NotificationManager.IMPORTANCE_DEFAULT);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            app.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Handler main=new Handler(Looper.getMainLooper());
        app.auth().sessions().subscribe(main::post,new ObservableQuery.Observer<SessionState>() {
            @Override public void onChanged(SessionState state) {
                if(state.status==SessionState.Status.RESTORING) return;
                String id=state.status==SessionState.Status.SIGNED_IN?state.user.id:null;
                synchronized(lock) {
                    if(!Objects.equals(actor,id)) {
                        actor=id; clearNotifications(); WorkManager.getInstance(context).cancelAllWorkByTag(TAG);
                        if(watch!=null)watch.close();watch=null;
                        if(id!=null) watch=app.recurringBills().observeRecurring(id).subscribe(main::post,new ObservableQuery.Observer<RecurringSnapshot>() {
                            @Override public void onChanged(RecurringSnapshot snapshot) {synchronized(lock){if(Objects.equals(actor,id)) prune(id,snapshot);}}
                            @Override public void onError(Exception error) {clearNotifications();}
                        });
                    }
                    restored.countDown();
                    if(id!=null) {
                        PeriodicWorkRequest work=new PeriodicWorkRequest.Builder(RecurringReminderWorker.class,12,TimeUnit.HOURS).addTag(TAG).setInputData(new Data.Builder().putString("actor",id).build()).build();
                        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,work);
                        refresh(false);
                    }
                }
            }
            @Override public void onError(Exception error) { synchronized(lock) { actor=null;clearNotifications();WorkManager.getInstance(context).cancelAllWorkByTag(TAG);restored.countDown(); } }
        });
    }
    private void prune(String owner,RecurringSnapshot snapshot) {
        Set<String> allowed=new HashSet<>();
        for(RecurringBill bill:snapshot.bills) if(bill.active) for(RecurringBillCycle cycle:snapshot.cycles.get(bill.id))
            for(RecurringBillContribution contribution:cycle.contributions) if(contribution.userId.equals(owner)&&!contribution.paid) allowed.add(cycle.id);
        for(android.service.notification.StatusBarNotification notification:context.getSystemService(NotificationManager.class).getActiveNotifications())
            if(notification.getTag()!=null&&!allowed.contains(notification.getTag())) NotificationManagerCompat.from(context).cancel(notification.getTag(),notification.getId());
    }
    public String awaitActor() throws InterruptedException { return restored.await(20,TimeUnit.SECONDS)?actor:null; }
    public boolean permitted() {
        if(Build.VERSION.SDK_INT>=26 && context.getSystemService(NotificationManager.class).getNotificationChannel(CHANNEL).getImportance()==NotificationManager.IMPORTANCE_NONE) return false;
        return (Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled(); }
    public void clearNotifications() { NotificationManagerCompat.from(context).cancelAll(); }
    public void refresh(boolean clear) {
        synchronized(lock) {
            if(clear) clearNotifications();
            if(actor==null) return;
            OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(RecurringReminderWorker.class).addTag(TAG).setInputData(new Data.Builder().putString("actor",actor).build()).build();
            WorkManager.getInstance(context).enqueueUniqueWork(NOW,ExistingWorkPolicy.APPEND_OR_REPLACE,work);
        }
    }
    public boolean deliver(String owner,List<BillReminder> reminders) {
        synchronized(lock) {
            if(!Objects.equals(actor,owner) || !permitted()) return false;
            for(BillReminder reminder:reminders) {
                Intent intent=new Intent(context,MainActivity.class).setAction("com.example.splitease.BILL").setData(android.net.Uri.parse("splitease://bill/"+reminder.billId)).putExtra("billId",reminder.billId).putExtra("recipient",owner).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pending=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                Notification notification=new NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_recurring).setContentTitle(reminder.title)
                    .setContentText(context.getString(R.string.bill_notification_body,reminder.groupName,ExpenseUi.date(reminder.dueDate)))
                    .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(new NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_recurring).setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.bill_private_notification)).build()).build();
                try { NotificationManagerCompat.from(context).notify(reminder.cycleId,0,notification); } catch(SecurityException denied) { return false; }
            }
            return true;
        }
    }
}
