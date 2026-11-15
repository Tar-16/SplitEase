package com.example.splitease.data.firebase;

import com.example.splitease.auth.AuthService;
import com.example.splitease.auth.SessionState;
import com.example.splitease.domain.RecurringSchedule;
import com.example.splitease.domain.Rules;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.*;
import java.util.concurrent.*;

/** Debug-only adapter. Cycle creation advances a server-validated UTC cursor in the same transaction. */
public final class FirestoreRecurringRepository implements RecurringBillRepository, AutoCloseable {
    private static final int BATCH_MONTHS = 12;
    private final FirebaseFirestore db;
    private final FirebaseAuth sdk;
    private final GroupRepository groups;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Set<Watch> watches = ConcurrentHashMap.newKeySet();
    private volatile SessionState session = SessionState.restoring(SessionState.Backend.FIREBASE);
    private volatile long epoch;
    private final ObservableQuery.Subscription sessions;

    public FirestoreRecurringRepository(FirebaseFirestore db, AuthService auth, GroupRepository groups) {
        this.db = db;
        this.sdk = FirebaseAuth.getInstance(db.getApp());
        this.groups = groups;
        sessions=auth.sessions().subscribe(Runnable::run, new ObservableQuery.Observer<SessionState>() {
            public void onChanged(SessionState next) {
                String old = identity();
                session = next;
                if (!Objects.equals(old, identity())) invalidate();
            }
            public void onError(Exception error) {
                session = SessionState.unavailable(SessionState.Backend.FIREBASE);
                invalidate();
            }
        });
    }

    private synchronized void invalidate() {
        epoch++;
        for (Watch watch : watches) watch.fail();
    }

    private String identity() {
        SessionState s = session;
        return s.backend == SessionState.Backend.FIREBASE && s.status == SessionState.Status.SIGNED_IN ? s.user.id : null;
    }

    private boolean current(String actor, long token) {
        return actor != null && actor.equals(identity()) && actor.equals(sdk.getUid()) && token == epoch;
    }

    private void check(String actor, long token) {
        if (!current(actor, token)) throw new SecurityException();
    }

    private DocumentReference bill(String id) { return db.collection("recurringBills").document(Rules.id(id)); }
    private Query groupBills(String group) { return db.collection("recurringBills").whereEqualTo("groupId", group); }
    private static int monthNow() { return RecurringSchedule.month(System.currentTimeMillis()); }
    private static <T> T await(Task<T> task) throws Exception { return Tasks.await(task, 12, TimeUnit.SECONDS); }

    @SuppressWarnings("unchecked")
    private static List<String> ids(DocumentSnapshot doc, String field) {
        return (List<String>) Objects.requireNonNull(doc.get(field));
    }

    private void authorize(Transaction tx, String group, String actor) throws FirebaseFirestoreException {
        DocumentSnapshot doc = tx.get(db.collection("groups").document(Rules.id(group)));
        if (!doc.exists() || !ids(doc,"memberIds").contains(actor)) throw new SecurityException();
    }

    private interface Work<T> { T run(long token) throws Exception; }

    private <T> void execute(String actor, Executor executor, RepositoryCallback<T> callback, Work<T> work) {
        long token = epoch;
        worker.execute(() -> {
            try {
                check(actor,token);
                T value = work.run(token);
                executor.execute(() -> {
                    if (current(actor,token)) callback.onSuccess(value);
                    else callback.onError(new IllegalStateException("Recurring session changed"));
                });
            } catch (Exception error) {
                executor.execute(() -> callback.onError(new IllegalStateException("Cloud recurring operation failed")));
            }
        });
    }

    private static RecurringBill template(DocumentSnapshot doc) {
        return new RecurringBill(doc.getId(),doc.getString("groupId"),doc.getString("title"),doc.getLong("amountPaise"),
                doc.getLong("dueDay").intValue(),ids(doc,"participants"),doc.getString("handlerId"),
                doc.getLong("reminderLeadDays").intValue(),Boolean.TRUE.equals(doc.getBoolean("active")),doc.getLong("revision"));
    }

    private static RecurringBillCycle cycle(DocumentSnapshot doc, String billId) {
        int month = doc.getLong("month").intValue();
        String id = billId + "/" + doc.getId();
        Map<String,Long> shares = Rules.equalSplitIdentities(doc.getLong("amountPaise"), ids(doc,"participants"));
        List<String> paid = ids(doc,"paid");
        List<RecurringBillContribution> contributions = new ArrayList<>();
        for (String uid : ids(doc,"participants")) contributions.add(new RecurringBillContribution(id,uid,shares.get(uid),paid.contains(uid)));
        return new RecurringBillCycle(id,billId,month/12,month%12+1,RecurringSchedule.due(month,doc.getLong("dueDay").intValue()),
                doc.getLong("amountPaise"),doc.getString("handlerId"),contributions);
    }

    private static Map<String,Object> templateFields(RecurringBill value) {
        String title = Rules.text(value.title);
        if (title.length()>200 || value.amountPaise>1_000_000_000_000L || value.participantIds.size()>8
                || value.dueDay<1 || value.dueDay>31 || value.reminderLeadDays<0 || value.reminderLeadDays>31
                || value.handlerId==null || value.handlerId.isEmpty()) throw new IllegalArgumentException();
        Map<String,Long> shares = Rules.equalSplitIdentities(value.amountPaise,value.participantIds);
        Map<String,Object> fields = new HashMap<>();
        fields.put("title",title);
        fields.put("amountPaise",value.amountPaise);
        fields.put("handlerId",value.handlerId);
        fields.put("participants",new ArrayList<>(shares.keySet()));
        fields.put("shares",shares);
        fields.put("groupId",Rules.id(value.groupId));
        fields.put("dueDay",value.dueDay);
        fields.put("reminderLeadDays",value.reminderLeadDays);
        fields.put("active",value.active);
        return fields;
    }

    @Override public void saveBill(String actor, RecurringBill value, Executor executor, RepositoryCallback<RecurringBill> callback) {
        execute(actor,executor,callback,token -> {
            Map<String,Object> fields = templateFields(value);
            DocumentReference ref = bill(value.id);
            DocumentSnapshot old = await(ref.get(Source.SERVER));
            int month = monthNow();
            if (old.exists() && Boolean.TRUE.equals(old.getBoolean("active")) && old.getLong("revision")==value.revision) {
                advance(actor,token,ref,old.getLong("nextMonth").intValue(),month);
            }
            return await(db.runTransaction(tx -> {
                check(actor,token);
                DocumentSnapshot saved = tx.get(ref);
                authorize(tx,value.groupId,actor);
                long revision = saved.exists() ? saved.getLong("revision") : 0;
                if (revision!=value.revision || saved.exists() && !value.groupId.equals(saved.getString("groupId")))
                    throw new IllegalStateException("Stale recurring bill");
                boolean wasActive = saved.exists() && Boolean.TRUE.equals(saved.getBoolean("active"));
                long next = !saved.exists() ? month : !wasActive && value.active ? Math.max(month,saved.getLong("nextMonth")) : saved.getLong("nextMonth");
                if (wasActive && next<=month) throw new IllegalStateException("Refresh to continue cycle catch-up");
                long updated = Math.addExact(revision,1);
                Map<String,Object> write = new HashMap<>(fields);
                write.put("nextMonth",next);
                write.put("revision",updated);
                check(actor,token);
                tx.set(ref,write);
                return new RecurringBill(value.id,value.groupId,value.title.trim(),value.amountPaise,value.dueDay,
                        value.participantIds,value.handlerId,value.reminderLeadDays,value.active,updated);
            }));
        });
    }

    private static final class Generated {
        final boolean created;
        final RecurringBillCycle value;
        Generated(boolean created, RecurringBillCycle value) { this.created=created; this.value=value; }
    }

    private static boolean rulesConflict(ExecutionException error) {
        Throwable cause=error.getCause();
        return cause instanceof FirebaseFirestoreException &&
                ((FirebaseFirestoreException)cause).getCode()==FirebaseFirestoreException.Code.PERMISSION_DENIED;
    }

    private Generated generate(String actor,long token,DocumentReference ref,int month,boolean strict) throws Exception {
        DocumentReference cycleRef=ref.collection("cycles").document(Integer.toString(month));
        try {
            return await(db.runTransaction(tx -> {
                check(actor,token);
                DocumentSnapshot b=tx.get(ref);
                if (!b.exists()) throw new IllegalArgumentException();
                authorize(tx,b.getString("groupId"),actor);
                DocumentSnapshot old=tx.get(cycleRef);
                if (old.exists()) return new Generated(false,cycle(old,ref.getId()));
                if (!Boolean.TRUE.equals(b.getBoolean("active")) || b.getLong("nextMonth")!=month || month>monthNow()) {
                    if (strict) throw new IllegalStateException("Month is not due");
                    return new Generated(false,null);
                }
                Map<String,Object> data=new HashMap<>();
                for (String field : Arrays.asList("amountPaise","handlerId","dueDay","participants")) data.put(field,b.get(field));
                data.put("month",month);
                data.put("paid",Collections.emptyList());
                check(actor,token);
                tx.set(cycleRef,data);
                tx.update(ref,"nextMonth",month+1);
                return new Generated(true,null);
            }));
        } catch (ExecutionException failure) {
            if (!rulesConflict(failure)) throw failure;
            check(actor,token);
            // The Rules may see the winner before the SDK can retry its stale precondition.
            // Only a confirmed, readable canonical cycle proves another transaction finished.
            DocumentSnapshot confirmed=await(cycleRef.get(Source.SERVER));
            check(actor,token);
            if (!confirmed.exists()) throw failure;
            return new Generated(false,cycle(confirmed,ref.getId()));
        }
    }

    private int advance(String actor,long token,DocumentReference ref,int start,int target) throws Exception {
        int count=0;
        for (int month=start; month<=target && month-start<BATCH_MONTHS; month++) {
            Generated result=generate(actor,token,ref,month,false);
            if (result.created) count++;
            else if (result.value==null) break;
        }
        return count;
    }

    @Override public void ensureCycles(String actor,long now,Executor executor,RepositoryCallback<Integer> callback) {
        execute(actor,executor,callback,token -> {
            int target=Math.min(RecurringSchedule.month(now),monthNow()),count=0;
            QuerySnapshot memberships=await(db.collection("groups").whereArrayContains("memberIds",actor).get(Source.SERVER));
            for (DocumentSnapshot group : memberships) {
                check(actor,token);
                for (DocumentSnapshot b : await(groupBills(group.getId()).get(Source.SERVER))) {
                    if (Boolean.TRUE.equals(b.getBoolean("active")))
                        count+=advance(actor,token,b.getReference(),b.getLong("nextMonth").intValue(),target);
                }
            }
            return count;
        });
    }

    @Override public void createCycle(String actor,String id,int year,int month,Executor executor,RepositoryCallback<RecurringBillCycle> callback) {
        execute(actor,executor,callback,token -> {
            Rules.dueDate(year,month,1);
            int key=year*12+month-1;
            DocumentReference ref=bill(id);
            Generated result=generate(actor,token,ref,key,true);
            return result.created ? cycle(await(ref.collection("cycles").document(Integer.toString(key)).get(Source.SERVER)),id) : result.value;
        });
    }

    @Override public void setContributionPaid(String actor,String cycleId,String participant,boolean paid,Executor executor,RepositoryCallback<Boolean> callback) {
        execute(actor,executor,callback,token -> {
            String[] key=cycleId.split("/",-1);
            if (key.length!=2 || !key[1].matches("[0-9]{5,6}")) throw new IllegalArgumentException();
            DocumentReference ref=bill(key[0]), cycle=ref.collection("cycles").document(key[1]);
            for (int attempt=0;;attempt++) {
                java.util.concurrent.atomic.AtomicReference<List<String>> attempted=new java.util.concurrent.atomic.AtomicReference<>();
                try {
                    return await(db.runTransaction(tx -> {
                        check(actor,token);
                        DocumentSnapshot b=tx.get(ref);
                        if (!b.exists()) throw new IllegalArgumentException();
                        authorize(tx,b.getString("groupId"),actor);
                        DocumentSnapshot row=tx.get(cycle);
                        if (!row.exists() || !ids(row,"participants").contains(participant)) throw new IllegalArgumentException();
                        List<String> selected=new ArrayList<>(ids(row,"paid"));
                        attempted.set(new ArrayList<>(selected));
                        boolean changed=paid ? !selected.contains(participant) && selected.add(participant) : selected.remove(participant);
                        check(actor,token);
                        if (changed) tx.update(cycle,"paid",selected);
                        return true;
                    }));
                } catch (ExecutionException failure) {
                    if (attempt==1 || !rulesConflict(failure) || attempted.get()==null) throw failure;
                    check(actor,token);
                    DocumentSnapshot confirmed=await(cycle.get(Source.SERVER));
                    check(actor,token);
                    if (!confirmed.exists() || ids(confirmed,"paid").equals(attempted.get())) throw failure;
                    // Retry once only after an authorized read proves the paid set changed.
                }
            }
        });
    }

    @Override public void claimReminders(String actor,long now,Executor executor,RepositoryCallback<List<BillReminder>> callback) {
        executor.execute(() -> callback.onError(new UnsupportedOperationException("Cloud reminders are disabled")));
    }

    @Override public void deleteBill(String actor,String id,Executor executor,RepositoryCallback<Boolean> callback) {
        executor.execute(() -> callback.onError(new UnsupportedOperationException("Deactivate to preserve cycle history")));
    }

    private boolean confirmed(QuerySnapshot snapshot) {
        return snapshot!=null && !snapshot.getMetadata().isFromCache() && !snapshot.getMetadata().hasPendingWrites();
    }

    private final class Watch implements ObservableQuery.Subscription {
        final String actor, selected;
        final long token=epoch;
        final Executor executor;
        final ObservableQuery.Observer<RecurringSnapshot> observer;
        final Map<String,ListenerRegistration> billListeners=new HashMap<>();
        final Map<String,List<RecurringBill>> templates=new HashMap<>();
        List<GroupDetails> details;
        List<RecurringBillCycle> history;
        ListenerRegistration cycles;
        ObservableQuery.Subscription membership;
        ScheduledFuture<?> deadline;
        boolean closed, ready;
        Watch(String actor,String selected,Executor executor,ObservableQuery.Observer<RecurringSnapshot> observer) {
            this.actor=actor;this.selected=selected;this.executor=executor;this.observer=observer;
        }
        synchronized void start() {
            if (closed) return;
            if (!current(actor,token)) { fail(); return; }
            watches.add(this);
            deadline=timer.schedule(() -> executor.execute(this::timedOut),10,TimeUnit.SECONDS);
            membership=groups.observeGroupSummaries(actor).subscribe(executor,new ObservableQuery.Observer<List<GroupDetails>>() {
                public void onChanged(List<GroupDetails> value) { members(value); }
                public void onError(Exception error) { fail(); }
            });
            if (closed) membership.close();
        }
        synchronized void timedOut() { if (!ready) fail(); }
        synchronized void members(List<GroupDetails> value) {
            if (closed) return;
            details=value;
            Set<String> active=new HashSet<>();
            for (GroupDetails group : value) active.add(group.group.id);
            for (String id : new ArrayList<>(billListeners.keySet())) if (!active.contains(id)) {
                billListeners.remove(id).remove();
                templates.remove(id);
            }
            for (String id : active) if (!billListeners.containsKey(id)) {
                billListeners.put(id,groupBills(id).addSnapshotListener(executor,MetadataChanges.INCLUDE,
                        (snapshot,error) -> bills(id,snapshot,error)));
            }
            emit();
        }
        synchronized void bills(String id,QuerySnapshot snapshot,Exception error) {
            if (closed || !billListeners.containsKey(id)) return;
            if (error!=null) { fail(); return; }
            if (!confirmed(snapshot)) {
                if (ready && snapshot!=null && snapshot.getMetadata().isFromCache()) fail();
                return;
            }
            List<RecurringBill> updated=new ArrayList<>();
            for (DocumentSnapshot doc : snapshot) updated.add(template(doc));
            templates.put(id,updated);
            emit();
        }
        synchronized void history(QuerySnapshot snapshot,Exception error) {
            if (closed) return;
            if (error!=null) { fail(); return; }
            if (!confirmed(snapshot)) {
                if (ready && snapshot!=null && snapshot.getMetadata().isFromCache()) fail();
                return;
            }
            List<RecurringBillCycle> values=new ArrayList<>();
            for (DocumentSnapshot doc : snapshot) values.add(cycle(doc,selected));
            values.sort(Comparator.comparingInt((RecurringBillCycle c)->c.year*12+c.month).reversed());
            history=Collections.unmodifiableList(values);
            emit();
        }
        void emit() {
            if (closed) return;
            if (!current(actor,token)) { fail(); return; }
            if (details==null || templates.size()!=billListeners.size()) return;
            List<RecurringBill> all=new ArrayList<>();
            for (List<RecurringBill> rows : templates.values()) all.addAll(rows);
            all.sort(Comparator.comparing((RecurringBill b)->b.title).thenComparing(b->b.id));
            boolean visible=selected!=null && all.stream().anyMatch(b->b.id.equals(selected));
            if (!visible && cycles!=null) { cycles.remove(); cycles=null; history=null; }
            if (visible && cycles==null) {
                cycles=bill(selected).collection("cycles").addSnapshotListener(executor,MetadataChanges.INCLUDE,this::history);
            }
            if (visible && history==null) return;
            Map<String,String> names=new HashMap<>();
            for (GroupDetails group : details) names.putAll(group.names);
            ready=true;
            deadline.cancel(false);
            observer.onChanged(new RecurringSnapshot(details,all,visible?Collections.singletonMap(selected,history):Collections.emptyMap(),names));
        }
        void fail() {
            // Serialize observer calls on the caller's executor, even for auth invalidation.
            executor.execute(() -> {
                synchronized (this) {
                    if (closed) return;
                    close();
                    observer.onError(new IllegalStateException("Cloud recurring data unavailable"));
                }
            });
        }
        public synchronized void close() {
            if (closed) return;
            closed=true;
            watches.remove(this);
            if (deadline!=null) deadline.cancel(false);
            if (membership!=null) membership.close();
            for (ListenerRegistration listener : billListeners.values()) listener.remove();
            billListeners.clear();
            if (cycles!=null) cycles.remove();
        }
    }

    @Override public ObservableQuery<RecurringSnapshot> observeRecurring(String actor) { return observeRecurring(actor,null); }

    @Override public ObservableQuery<RecurringSnapshot> observeRecurring(String actor,String id) {
        return (executor,observer) -> {
            Watch watch=new Watch(actor,id,executor,observer);
            executor.execute(watch::start);
            return watch;
        };
    }

    @Override public ObservableQuery<List<RecurringBill>> observeBills(String group) {
        return project(observeRecurring(identity()),value -> {
            List<RecurringBill> result=new ArrayList<>();
            for (RecurringBill bill : value.bills) if (bill.groupId.equals(group)) result.add(bill);
            return Collections.unmodifiableList(result);
        });
    }

    @Override public ObservableQuery<List<RecurringBillCycle>> observeCycles(String id) {
        return project(observeRecurring(identity(),id),value -> value.cycles.getOrDefault(id,Collections.emptyList()));
    }

    @Override public void close() {
        sessions.close();
        invalidate();
        worker.shutdown();
        timer.shutdownNow();
    }

    private static <T> ObservableQuery<T> project(ObservableQuery<RecurringSnapshot> source,
                                                   java.util.function.Function<RecurringSnapshot,T> mapping) {
        return (executor,observer) -> source.subscribe(executor,new ObservableQuery.Observer<RecurringSnapshot>() {
            public void onChanged(RecurringSnapshot value) { observer.onChanged(mapping.apply(value)); }
            public void onError(Exception error) { observer.onError(error); }
        });
    }
}
