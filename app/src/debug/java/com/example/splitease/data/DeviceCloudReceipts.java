package com.example.splitease.data;

import android.content.Context;
import android.util.AtomicFile;
import com.example.splitease.auth.*;
import com.example.splitease.domain.Rules;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** App-scoped, private receipt index and expense decorator for the named demo client. */
public final class DeviceCloudReceipts implements ReceiptStorageRepository, ExpenseRepository, AutoCloseable {
    private static final long DRAFT_TTL = TimeUnit.DAYS.toMillis(1);
    private final FirebaseFirestore db;
    private final FirebaseAuth sdk;
    private final ExpenseRepository expenses;
    private final ReceiptFiles files;
    private final AtomicFile index;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ObservableQuery.Subscription sessions;
    private final CompletableFuture<Void> initialized = new CompletableFuture<>();
    private final Set<String> pendingExpenses = new HashSet<>();
    private final Set<String> pendingDrafts = new HashSet<>();
    private volatile SessionState session = SessionState.restoring(SessionState.Backend.FIREBASE);

    public DeviceCloudReceipts(Context context, FirebaseFirestore db, AuthService auth, ExpenseRepository expenses) {
        if (!"demo-splitease-auth".equals(db.getApp().getOptions().getProjectId())) throw new IllegalArgumentException();
        this.db = db;
        this.sdk = FirebaseAuth.getInstance(db.getApp());
        this.expenses = expenses;
        files = new ReceiptFiles(context, "firebase-demo-device-receipts-v1");
        index = new AtomicFile(new File(context.getNoBackupFilesDir(), "firebase-demo-device-receipts-v1.json"));
        sessions = auth.sessions().subscribe(Runnable::run, new ObservableQuery.Observer<SessionState>() {
            public void onChanged(SessionState value) {
                session = value;
                initialized.complete(null);
            }
            public void onError(Exception error) {
                session = SessionState.unavailable(SessionState.Backend.FIREBASE);
                initialized.complete(null);
            }
        });
        cleanUp(Runnable::run, new RepositoryCallback<Boolean>() {
            public void onSuccess(Boolean value) {}
            public void onError(Exception error) { /* A later cleanup can retry. */ }
        });
    }

    private void authorize(String actor, SessionState expected) {
        if (session != expected || expected.backend != SessionState.Backend.FIREBASE
                || expected.status != SessionState.Status.SIGNED_IN || !expected.user.id.equals(actor)
                || !expected.user.id.equals(sdk.getUid())) throw new SecurityException();
    }

    private DocumentSnapshot server(DocumentReference ref) throws Exception {
        DocumentSnapshot doc = Tasks.await(ref.get(Source.SERVER), 8, TimeUnit.SECONDS);
        if (doc.getMetadata().isFromCache() || doc.getMetadata().hasPendingWrites()) throw new IOException();
        return doc;
    }

    private void member(String actor, String group) throws Exception {
        DocumentSnapshot doc = server(db.collection("groups").document(Rules.id(group)));
        Object members = doc.get("memberIds");
        if (!(members instanceof List) || !((List<?>)members).contains(actor)) throw new SecurityException();
    }

    private boolean activeExpense(String expense, String group) throws Exception {
        DocumentSnapshot doc = server(db.collection("expenses").document(Rules.id(expense)));
        if (!doc.exists()) return false;
        if (!group.equals(doc.getString("groupId"))) throw new SecurityException();
        return !Boolean.TRUE.equals(doc.getBoolean("deleted"));
    }

    private JSONObject readIndex() throws Exception {
        if (!index.getBaseFile().exists() && !new File(index.getBaseFile().getPath()+".bak").exists())
            return new JSONObject();
        return new JSONObject(new String(index.readFully(), StandardCharsets.UTF_8));
    }

    private void writeIndex(JSONObject data) throws IOException {
        FileOutputStream output = index.startWrite();
        byte[] encoded = data.toString().getBytes(StandardCharsets.UTF_8);
        try {
            output.write(encoded);
            output.getFD().sync();
        } catch (IOException error) {
            index.failWrite(output);
            throw error;
        }
        index.finishWrite(output);
        if (!Arrays.equals(encoded,index.readFully())) throw unavailable();
    }

    private static ReceiptMetadata metadata(String id, JSONObject row) throws JSONException {
        return new ReceiptMetadata(id,row.getString("group"),row.optString("expense",null),
                row.getInt("width"),row.getInt("height"),row.getLong("size"),row.getLong("created"));
    }

    private JSONObject owned(JSONObject data, String id, String actor) throws Exception {
        JSONObject row = data.getJSONObject(Rules.id(id));
        if (!actor.equals(row.getString("owner"))) throw new SecurityException();
        return row;
    }

    private void reconcile(JSONObject data) throws IOException {
        Set<String> live = new HashSet<>();
        data.keys().forEachRemaining(live::add);
        files.reconcile(live);
    }

    private void collect(JSONObject data) {
        try { reconcile(data); } catch (IOException deferred) { /* Retry on the next cleanup. */ }
    }

    private interface Work<T> { T run(SessionState session) throws Exception; }
    private static IOException unavailable() { return new IOException("Device receipt unavailable"); }

    private <T> void access(String actor, Executor executor, RepositoryCallback<T> callback, Work<T> work) {
        if (!initialized.isDone()) {
            initialized.thenRun(() -> access(actor,executor,callback,work));
            return;
        }
        SessionState expected = session;
        worker.execute(() -> {
            try {
                authorize(actor,expected);
                T value = work.run(expected);
                executor.execute(() -> {
                    try { authorize(actor,expected); }
                    catch (SecurityException changed) { callback.onError(unavailable()); return; }
                    callback.onSuccess(value);
                });
            } catch (Exception failure) {
                executor.execute(() -> callback.onError(unavailable()));
            }
        });
    }

    @Override public void stage(String actor,String group,byte[] jpeg,Executor executor,RepositoryCallback<ReceiptMetadata> callback) {
        byte[] copy = jpeg == null ? null : jpeg.clone();
        access(actor,executor,callback,expected -> {
            member(actor,group);
            int[] dimensions = files.dimensions(copy);
            JSONObject data = readIndex();
            String id = UUID.randomUUID().toString();
            JSONObject row = new JSONObject().put("owner",actor).put("group",group)
                    .put("width",dimensions[0]).put("height",dimensions[1]).put("size",copy.length)
                    .put("created",System.currentTimeMillis());
            authorize(actor,expected);
            files.write(id,copy);
            try {
                authorize(actor,expected);
                writeIndex(data.put(id,row));
            } catch (Exception failure) {
                // If index publication was ambiguous, preserve anything it currently references.
                collect(readIndex());
                throw failure;
            }
            return metadata(id,row);
        });
    }

    @Override public void load(String actor,String id,Executor executor,RepositoryCallback<ReceiptImage> callback) {
        access(actor,executor,callback,expected -> {
            JSONObject data = readIndex();
            ReceiptMetadata value = metadata(id,owned(data,id,actor));
            member(actor,value.groupId);
            if (value.expenseId != null && !activeExpense(value.expenseId,value.groupId)) {
                data.remove(id);
                writeIndex(data);
                collect(data);
                throw unavailable();
            }
            byte[] jpeg = files.read(id);
            if (jpeg.length != value.byteCount) throw unavailable();
            files.dimensions(jpeg);
            return new ReceiptImage(value,jpeg);
        });
    }

    @Override public void findForExpense(String actor,String expense,Executor executor,RepositoryCallback<Optional<ReceiptMetadata>> callback) {
        access(actor,executor,callback,expected -> {
            Rules.id(expense);
            JSONObject data = readIndex();
            for (Iterator<String> ids = data.keys(); ids.hasNext();) {
                String id = ids.next();
                JSONObject row = data.getJSONObject(id);
                if (!actor.equals(row.getString("owner")) || !expense.equals(row.optString("expense"))) continue;
                ReceiptMetadata value = metadata(id,row);
                member(actor,value.groupId);
                if (activeExpense(expense,value.groupId)) return Optional.of(value);
                ids.remove();
                writeIndex(data);
                collect(data);
                break;
            }
            return Optional.empty();
        });
    }

    @Override public void discardDraft(String actor,String id,Executor executor,RepositoryCallback<Boolean> callback) {
        access(actor,executor,callback,expected -> {
            JSONObject data = readIndex();
            JSONObject row = owned(data,id,actor);
            if (row.has("expense") || pendingDrafts.contains(id)) return false;
            member(actor,row.getString("group"));
            authorize(actor,expected);
            data.remove(id);
            writeIndex(data);
            collect(data);
            return true;
        });
    }

    @Override public void cleanUp(Executor executor,RepositoryCallback<Boolean> callback) {
        worker.execute(() -> {
            try {
                JSONObject data = readIndex();
                boolean changed = false;
                for (Iterator<String> ids = data.keys(); ids.hasNext();) {
                    String id = ids.next();
                    JSONObject row = data.getJSONObject(id);
                    if (!row.has("expense") && !pendingDrafts.contains(id)
                            && System.currentTimeMillis()-row.getLong("created") > DRAFT_TTL) {
                        ids.remove();
                        changed = true;
                    }
                }
                if (changed) writeIndex(data);
                reconcile(data);
                executor.execute(() -> callback.onSuccess(true));
            } catch (Exception error) {
                executor.execute(() -> callback.onError(unavailable()));
            }
        });
    }

    @Override public void saveExpense(String actor,Expense expense,Executor executor,RepositoryCallback<Expense> callback) {
        saveExpense(actor,expense,ReceiptChange.keep(),executor,callback);
    }

    @Override public void saveExpense(String actor,Expense expense,ReceiptChange change,Executor executor,RepositoryCallback<Expense> callback) {
        if (!initialized.isDone()) {
            initialized.thenRun(() -> saveExpense(actor,expense,change,executor,callback));
            return;
        }
        SessionState expected = session;
        worker.execute(() -> {
            try {
                authorize(actor,expected);
                if (pendingExpenses.contains(expense.id)) throw unavailable();
                if (change.kind == ReceiptChange.Kind.REPLACE) {
                    JSONObject row = owned(readIndex(),change.draftId,actor);
                    if (row.has("expense") || !expense.groupId.equals(row.getString("group"))
                            || pendingDrafts.contains(change.draftId)) throw unavailable();
                    if (files.read(change.draftId).length != row.getLong("size")) throw unavailable();
                    pendingDrafts.add(change.draftId);
                }
                pendingExpenses.add(expense.id);
            } catch (Exception failure) {
                executor.execute(() -> callback.onError(unavailable()));
                return;
            }
            expenses.saveExpense(actor,expense,worker,new RepositoryCallback<Expense>() {
                public void onSuccess(Expense saved) {
                    release();
                    try {
                        authorize(actor,expected);
                        if (change.kind != ReceiptChange.Kind.KEEP) {
                            member(actor,saved.groupId);
                            if (!activeExpense(saved.id,saved.groupId)) throw unavailable();
                            authorize(actor,expected);
                            link(actor,saved,change);
                        }
                        executor.execute(() -> callback.onSuccess(saved));
                    } catch (Exception failure) {
                        executor.execute(() -> callback.onError(new ReceiptLinkException()));
                    }
                }
                public void onError(Exception failure) {
                    release();
                    executor.execute(() -> callback.onError(failure));
                }
                private void release() {
                    pendingExpenses.remove(expense.id);
                    if (change.draftId != null) pendingDrafts.remove(change.draftId);
                }
            });
        });
    }

    private void link(String actor,Expense expense,ReceiptChange change) throws Exception {
        JSONObject data = readIndex();
        for (Iterator<String> ids = data.keys(); ids.hasNext();) {
            JSONObject row = data.getJSONObject(ids.next());
            if (actor.equals(row.getString("owner")) && expense.groupId.equals(row.getString("group"))
                    && expense.id.equals(row.optString("expense"))) ids.remove();
        }
        if (change.kind == ReceiptChange.Kind.REPLACE) {
            JSONObject row = owned(data,change.draftId,actor);
            row.put("expense",expense.id);
        }
        writeIndex(data);
        collect(data);
    }

    @Override public void deleteExpense(String actor,String id,Executor executor,RepositoryCallback<Boolean> callback) {
        expenses.deleteExpense(actor,id,executor,callback);
    }

    @Override public void deleteExpense(String actor,Expense expected,Executor executor,RepositoryCallback<Boolean> callback) {
        expenses.deleteExpense(actor,expected,worker,new RepositoryCallback<Boolean>() {
            public void onSuccess(Boolean value) {
                if (Boolean.TRUE.equals(value)) {
                    try {
                        JSONObject data = readIndex();
                        for (Iterator<String> ids = data.keys(); ids.hasNext();) {
                            JSONObject row = data.getJSONObject(ids.next());
                            if (expected.groupId.equals(row.getString("group")) && expected.id.equals(row.optString("expense")))
                                ids.remove();
                        }
                        writeIndex(data);
                        collect(data);
                    } catch (Exception deferred) { /* Server tombstones also invalidate links on access. */ }
                }
                executor.execute(() -> callback.onSuccess(value));
            }
            public void onError(Exception failure) { executor.execute(() -> callback.onError(failure)); }
        });
    }

    @Override public ObservableQuery<List<Expense>> observeExpenses(String group) { return expenses.observeExpenses(group); }
    @Override public void close() { sessions.close(); worker.shutdown(); }
}
