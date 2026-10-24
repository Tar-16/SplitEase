package com.example.splitease.reminders;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.example.splitease.SplitEaseApplication;
import com.example.splitease.model.BillReminder;
import com.example.splitease.repository.*;
import java.util.*;
import java.util.concurrent.*;

/** Deferrable work, not an exact alarm. Revalidates the restored identity before claims/delivery. */
public final class RecurringReminderWorker extends Worker {
 public RecurringReminderWorker(@NonNull Context context,@NonNull WorkerParameters parameters) { super(context,parameters); }
 @NonNull @Override public Result doWork() {
  SplitEaseApplication app=(SplitEaseApplication)getApplicationContext();
  try {
   String actor=app.reminders().awaitActor();
   if(actor==null || !actor.equals(getInputData().getString("actor"))) return Result.success();
   CompletableFuture<Integer> generation=new CompletableFuture<>();
   app.recurringBills().ensureCycles(actor,System.currentTimeMillis(),Runnable::run,callback(generation));generation.get(30,TimeUnit.SECONDS);
   if(isStopped() || !app.reminders().permitted()) return Result.success();
   CompletableFuture<List<BillReminder>> claims=new CompletableFuture<>();
   app.recurringBills().claimReminders(actor,System.currentTimeMillis(),Runnable::run,callback(claims));
   List<BillReminder> values=claims.get(30,TimeUnit.SECONDS);
   if(!isStopped()) app.reminders().deliver(actor,values);
   return Result.success();
  } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();return Result.retry(); }
    catch(ExecutionException|TimeoutException failure) { return Result.retry(); }
 }
 private static <T> RepositoryCallback<T> callback(CompletableFuture<T> future) { return new RepositoryCallback<T>() {
  @Override public void onSuccess(T value) { future.complete(value); }
  @Override public void onError(Exception error) { future.completeExceptionally(error); }
 }; }
}
