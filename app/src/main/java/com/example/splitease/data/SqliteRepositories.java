package com.example.splitease.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteConstraintException;
import com.example.splitease.domain.AccountValidation;
import com.example.splitease.domain.Balances;
import com.example.splitease.domain.ExpenseAmounts;
import com.example.splitease.domain.Rules;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App-scoped SQLite repository owner. All I/O uses one worker; writes and their permission
 * checks are atomic. Callbacks use the supplied executor (which must remain accepting).
 * Actor IDs are supplied by the trusted AuthService presentation layer, not an authentication boundary.
 */
public final class SqliteRepositories implements UserRepository, GroupRepository,
        ExpenseRepository, SettlementRepository, RecurringBillRepository, AutoCloseable {
    private final SplitEaseDatabase helper;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "splitease-db"));
    private final List<Watch<?>> watches = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SqliteRepositories(Context context) { helper = new SplitEaseDatabase(context); }
    public SqliteRepositories(Context context, String databaseName) { helper = new SplitEaseDatabase(context, databaseName); }
    private interface Work<T> { T run() throws Exception; }
    private interface Mapper<T> { T read(Cursor cursor); }
    private SQLiteDatabase db() { return helper.getWritableDatabase(); }

    /** Explicit async initialization; opening the owner itself never touches disk. */
    public void initialize(Executor executor, RepositoryCallback<Boolean> callback) {
        submit(false, () -> { db(); return true; }, executor, callback);
    }
    private <T> void submit(boolean write, Work<T> work, Executor executor, RepositoryCallback<T> callback) {
        Objects.requireNonNull(executor); Objects.requireNonNull(callback);
        if (closed.get()) { executor.execute(() -> callback.onError(new IllegalStateException("Repository closed"))); return; }
        try {
            worker.execute(() -> {
                T result;
                try {
                    if (write) {
                        SQLiteDatabase database = db();
                        database.beginTransaction();
                        try { result = work.run(); database.setTransactionSuccessful(); }
                        finally { database.endTransaction(); }
                    } else { result = work.run(); }
                } catch (Exception error) {
                    executor.execute(() -> callback.onError(error));
                    return;
                }
                if (write) for (Watch<?> watch : watches) watch.refresh();
                T value = result;
                executor.execute(() -> callback.onSuccess(value));
            });
        } catch (RejectedExecutionException error) { executor.execute(() -> callback.onError(error)); }
    }
    private <T> ObservableQuery<T> observe(Work<T> read) {
        return (executor, observer) -> {
            Objects.requireNonNull(executor); Objects.requireNonNull(observer);
            Watch<T> watch = new Watch<>(read, executor, observer);
            watches.add(watch);
            try {
                if (closed.get()) throw new RejectedExecutionException("Repository closed");
                worker.execute(watch::refresh);
            } catch (RejectedExecutionException error) { watch.fail(error); }
            return watch;
        };
    }
    private final class Watch<T> implements ObservableQuery.Subscription {
        private final Work<T> read;
        private final Executor executor;
        private final ObservableQuery.Observer<T> observer;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private boolean failed;
        Watch(Work<T> read, Executor executor, ObservableQuery.Observer<T> observer) {
            this.read = read; this.executor = executor; this.observer = observer;
        }
        void refresh() {
            if (cancelled.get() || failed) return;
            final T value;
            try { value = read.run(); } catch (Exception error) { fail(error); return; }
            executor.execute(() -> { if (!cancelled.get()) observer.onChanged(value); });
        }
        void fail(Exception error) {
            failed = true; watches.remove(this);
            executor.execute(() -> { if (!cancelled.get()) observer.onError(error); });
        }
        @Override public void close() { cancelled.set(true); watches.remove(this); }
    }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            for (Watch<?> watch : watches) watch.close();
            worker.execute(helper::close);
            worker.shutdown();
        }
    }
    private <T> List<T> query(String sql, Mapper<T> mapper, String... args) {
        List<T> result = new ArrayList<>();
        try (Cursor cursor = db().rawQuery(sql, args)) { while (cursor.moveToNext()) result.add(mapper.read(cursor)); }
        return Collections.unmodifiableList(result);
    }
    private <T> T one(String sql, Mapper<T> mapper, String... args) {
        List<T> values = query(sql, mapper, args);
        if (values.isEmpty()) throw new NoSuchElementException("Record not found");
        return values.get(0);
    }
    private boolean exists(String table, String id) {
        return !query("SELECT id FROM " + table + " WHERE id=?", c -> c.getString(0), Rules.id(id)).isEmpty();
    }
    private static String str(Cursor c, String column) { return c.getString(c.getColumnIndexOrThrow(column)); }
    private static long num(Cursor c, String column) { return c.getLong(c.getColumnIndexOrThrow(column)); }
    private static ContentValues values(Object... pairs) {
        ContentValues result = new ContentValues();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i]; Object value = pairs[i + 1];
            if (value == null) result.putNull(key);
            else if (value instanceof String) result.put(key, (String) value);
            else if (value instanceof byte[]) result.put(key, (byte[]) value);
            else if (value instanceof Boolean) result.put(key, (Boolean) value);
            else if (value instanceof Number) result.put(key, ((Number) value).longValue());
            else throw new IllegalArgumentException("Unsupported SQL value");
        }
        return result;
    }
    private void insert(String table, ContentValues row) { db().insertOrThrow(table, null, row); }
    private void update(String table, String id, ContentValues row) {
        if (db().update(table, row, "id=?", new String[]{Rules.id(id)}) != 1) throw new NoSuchElementException("Record not found");
    }
    private void member(String group, String user) {
        one("SELECT user_id FROM group_members WHERE group_id=? AND user_id=? AND status='ACTIVE'",
                c -> c.getString(0), Rules.id(group), Rules.id(user));
    }
    private void historicalMember(String group, String user) {
        one("SELECT user_id FROM group_members WHERE group_id=? AND user_id=?", c -> c.getString(0), Rules.id(group), Rules.id(user));
    }
    private User user(String id) {
        return one("SELECT id,username,email,upi_id FROM users WHERE id=?",
                c -> new User(str(c,"id"),str(c,"username"),str(c,"email"),str(c,"upi_id")), Rules.id(id));
    }
    @Override public void getUser(String id, Executor executor, RepositoryCallback<User> callback) {
        submit(false, () -> user(id), executor, callback);
    }
    @Override public void findUserByEmail(String email, Executor executor, RepositoryCallback<User> callback) {
        submit(false, () -> one("SELECT id,username,email,upi_id FROM users WHERE email=?",
                c -> new User(str(c,"id"),str(c,"username"),str(c,"email"),str(c,"upi_id")),
                AccountValidation.normalize(email)), executor, callback);
    }
    private void identifyDuplicate(User user) throws DuplicateAccountException {
        if (!query("SELECT id FROM users WHERE email=? AND id<>?", c -> c.getString(0),
                AccountValidation.normalize(user.email),user.id).isEmpty())
            throw new DuplicateAccountException(DuplicateAccountException.Field.EMAIL);
        if (!query("SELECT id FROM users WHERE username=? AND id<>?", c -> c.getString(0),
                AccountValidation.normalize(user.username),user.id).isEmpty())
            throw new DuplicateAccountException(DuplicateAccountException.Field.USERNAME);
    }
    @Override public void createUser(User user, char[] password, Executor executor, RepositoryCallback<User> callback) {
        char[] copy = password == null ? new char[0] : password.clone();
        submit(true, () -> {
            try {
                identifyDuplicate(user);
                byte[] salt = PasswordHasher.salt();
                byte[] hash = PasswordHasher.hash(copy, salt, PasswordHasher.ITERATIONS, PasswordHasher.MEMORY_KIB, PasswordHasher.PARALLELISM);
                try {
                    ContentValues row = userValues(user);
                    row.put("id", Rules.id(user.id)); row.put("password_hash", hash); row.put("password_salt", salt);
                    row.put("password_algorithm", PasswordHasher.ALGORITHM);
                    row.put("password_iterations", PasswordHasher.ITERATIONS); row.put("password_memory", PasswordHasher.MEMORY_KIB);
                    row.put("password_parallelism", PasswordHasher.PARALLELISM);
                    try { insert("users", row); }
                    catch (SQLiteConstraintException error) { identifyDuplicate(user); throw error; }
                    return user(user.id);
                } finally { Arrays.fill(hash, (byte) 0); }
            } finally { Arrays.fill(copy, '\0'); }
        }, executor, wipingCallback(copy, callback));
    }
    private ContentValues userValues(User user) {
        return values("username", AccountValidation.normalize(Rules.text(user.username)),
                "email", AccountValidation.normalize(Rules.text(user.email)), "upi_id", user.upiId);
    }
    @Override public void updateUser(User user, Executor executor, RepositoryCallback<User> callback) {
        submit(true, () -> { update("users", user.id, userValues(user)); return user(user.id); }, executor, callback);
    }
    @Override public void deleteUser(String id, Executor executor, RepositoryCallback<Boolean> callback) {
        submit(true, () -> { user(id); return db().delete("users", "id=?", new String[]{id}) == 1; }, executor, callback);
    }
    @Override public void verifyPassword(String id, char[] password, Executor executor, RepositoryCallback<Boolean> callback) {
        char[] copy = password == null ? new char[0] : password.clone();
        submit(false, () -> {
            try {
                return one("SELECT * FROM users WHERE id=?", c -> {
                    if (!PasswordHasher.ALGORITHM.equals(str(c,"password_algorithm"))) throw new IllegalStateException("Unsupported credential version");
                    return PasswordHasher.verify(copy, c.getBlob(c.getColumnIndexOrThrow("password_salt")),
                            c.getBlob(c.getColumnIndexOrThrow("password_hash")), (int)num(c,"password_iterations"),
                            (int)num(c,"password_memory"), (int)num(c,"password_parallelism"));
                }, Rules.id(id));
            } finally { Arrays.fill(copy, '\0'); }
        }, executor, wipingCallback(copy, callback));
    }
    private static <T> RepositoryCallback<T> wipingCallback(char[] password, RepositoryCallback<T> callback) {
        return new RepositoryCallback<T>() {
            @Override public void onSuccess(T value) { Arrays.fill(password, '\0'); callback.onSuccess(value); }
            @Override public void onError(Exception error) { Arrays.fill(password, '\0'); callback.onError(error); }
        };
    }
    private Group group(Cursor c) {
        return new Group(str(c,"id"),str(c,"name"),Group.Type.valueOf(str(c,"type")),str(c,"admin_id"),str(c,"creator_id"),num(c,"created_at"));
    }
    private Group group(String id) { return one("SELECT * FROM groups WHERE id=?", this::group, Rules.id(id)); }
    @Override public void createGroup(Group group, Executor executor, RepositoryCallback<Group> callback) {
        createGroup(group.adminId,group,Collections.emptyList(),executor,callback);
    }
    @Override public void createGroup(String actorId, Group group, List<String> memberIds, Executor executor, RepositoryCallback<Group> callback) {
        List<String> selected=new ArrayList<>(memberIds);
        submit(true, () -> {
            Rules.date(group.createdAt); Rules.id(actorId);
            if (!actorId.equals(group.adminId) || !actorId.equals(group.creatorId)) throw new IllegalArgumentException("Creator must be acting admin");
            Set<String> ids=new LinkedHashSet<>();
            for (String id : selected) if (!ids.add(Rules.id(id))) throw new IllegalArgumentException("Duplicate member");
            ids.add(actorId);
            insert("groups", values("id",Rules.id(group.id),"name",Rules.text(group.name),"type",group.type.name(),"admin_id",actorId,"creator_id",actorId,"created_at",group.createdAt));
            for (String id : ids) insert("group_members", values("group_id",group.id,"user_id",id,"status","ACTIVE","joined_at",group.createdAt));
            return group(group.id);
        }, executor, callback);
    }
    @Override public void findMemberByUsername(String actorId, String username, Executor executor, RepositoryCallback<MemberIdentity> callback) {
        submit(false, () -> {
            if (!exists("users",actorId)) throw new SecurityException("Account required");
            return one("SELECT id,username FROM users WHERE username=?",c -> new MemberIdentity(c.getString(0),c.getString(1)),Rules.text(AccountValidation.normalize(username)));
        },executor,callback);
    }
    private GroupDetails details(String actorId, Group value, boolean includeEligibility) {
        member(value.id,actorId);
        List<MemberIdentity> members=query("SELECT u.id,u.username FROM users u JOIN group_members m ON m.user_id=u.id WHERE m.group_id=? AND m.status='ACTIVE' ORDER BY u.username COLLATE NOCASE,u.id",
                c -> new MemberIdentity(c.getString(0),c.getString(1)),value.id);
        List<Expense> expenses=expenses(value.id);
        Map<String,Long> balances=new TreeMap<>(Balances.calculate(expenses,settlements(value.id)));
        Set<String> needed=new HashSet<>(balances.keySet());
        for(MemberIdentity current:members) { needed.add(current.id); balances.putIfAbsent(current.id,0L); }
        Map<String,String> names=new LinkedHashMap<>();
        for(MemberIdentity identity:query("SELECT u.id,u.username FROM users u JOIN group_members m ON m.user_id=u.id WHERE m.group_id=? ORDER BY u.username COLLATE NOCASE,u.id",
                c -> new MemberIdentity(c.getString(0),c.getString(1)),value.id)) {
            if(needed.contains(identity.id)) names.put(identity.id,identity.username);
        }
        return new GroupDetails(value,members,includeEligibility && !value.adminId.equals(actorId) && balances.getOrDefault(actorId,0L)==0L,
                expenses,balances,names,ExpenseAmounts.total(expenses));
    }
    @Override public ObservableQuery<List<GroupDetails>> observeGroupSummaries(String actorId) {
        return observe(() -> {
            List<GroupDetails> result=new ArrayList<>();
            for (Group value : query("SELECT g.* FROM groups g JOIN group_members m ON m.group_id=g.id WHERE m.user_id=? AND m.status='ACTIVE' ORDER BY g.created_at DESC,g.id",this::group,Rules.id(actorId))) result.add(details(actorId,value,false));
            return Collections.unmodifiableList(result);
        });
    }
    @Override public ObservableQuery<GroupDetails> observeGroupDetails(String actorId, String groupId) {
        return observe(() -> details(actorId,group(groupId),true));
    }
    @Override public void updateGroup(String actorId, Group value, Executor executor, RepositoryCallback<Group> callback) {
        submit(true, () -> {
            member(value.id,actorId); Group old = group(value.id);
            if (!old.adminId.equals(value.adminId) || !old.creatorId.equals(value.creatorId) || old.createdAt != value.createdAt) throw new IllegalArgumentException("Use transferAdmin; creation date is immutable");
            update("groups",value.id,values("name",Rules.text(value.name),"type",value.type.name())); return group(value.id);
        },executor,callback);
    }
    @Override public void deleteGroup(String actorId, String groupId, Executor executor, RepositoryCallback<Boolean> callback) {
        submit(true, () -> { member(groupId,actorId); return db().delete("groups","id=?",new String[]{groupId}) == 1; },executor,callback);
    }
    @Override public void addMember(String actorId, String groupId, String userId, long joinedAt, Executor executor, RepositoryCallback<Boolean> callback) {
        submit(true, () -> {
            member(groupId,actorId); Rules.id(userId); Rules.date(joinedAt);
            if (!group(groupId).adminId.equals(actorId)) throw new SecurityException("Only the admin can add members");
            if (joinedAt < group(groupId).createdAt) throw new IllegalArgumentException("Membership predates group");
            // Preserve the original joined timestamp on rejoining; clear only the latest departure.
            int changed = db().update("group_members",values("status","ACTIVE","left_at",null),
                    "group_id=? AND user_id=? AND status='LEFT'",new String[]{groupId,userId});
            if (changed == 0) insert("group_members",values("group_id",groupId,"user_id",userId,"status","ACTIVE","joined_at",joinedAt));
            return true;
        },executor,callback);
    }
    @Override public void leaveGroup(String userId, String groupId, long leftAt, Executor executor, RepositoryCallback<Boolean> callback) {
        submit(true, () -> {
            member(groupId,userId); Rules.date(leftAt);
            if (group(groupId).adminId.equals(userId)) throw new IllegalStateException("Transfer admin ownership first");
            if (balance(groupId).getOrDefault(userId,0L) != 0) throw new IllegalStateException("Only a zero-balance member may leave");
            db().update("group_members",values("status","LEFT","left_at",leftAt),"group_id=? AND user_id=?",new String[]{groupId,userId});
            return true;
        },executor,callback);
    }
    @Override public void transferAdmin(String actorId, String groupId, String newAdminId, Executor executor, RepositoryCallback<Boolean> callback) {
        submit(true, () -> {
            member(groupId,actorId); member(groupId,newAdminId);
            if (!group(groupId).adminId.equals(actorId)) throw new IllegalStateException("Only the admin can transfer ownership");
            update("groups",groupId,values("admin_id",newAdminId)); return true;
        },executor,callback);
    }
    @Override public ObservableQuery<List<Group>> observeGroups(String userId) {
        return observe(() -> query("SELECT g.* FROM groups g JOIN group_members m ON m.group_id=g.id WHERE m.user_id=? AND m.status='ACTIVE' ORDER BY g.created_at,g.id",this::group,Rules.id(userId)));
    }
    @Override public ObservableQuery<List<GroupMember>> observeMembers(String groupId) {
        return observe(() -> query("SELECT * FROM group_members WHERE group_id=? ORDER BY user_id",c -> new GroupMember(str(c,"group_id"),str(c,"user_id"),GroupMember.Status.valueOf(str(c,"status")),num(c,"joined_at"), c.isNull(c.getColumnIndexOrThrow("left_at")) ? null : num(c,"left_at")),Rules.id(groupId)));
    }
    private Map<String,Long> balance(String groupId) { return Balances.calculate(expenses(groupId), settlements(groupId)); }
    @Override public ObservableQuery<Map<String,Long>> observeBalances(String groupId) { return observe(() -> balance(Rules.id(groupId))); }
    private Expense expense(Cursor c) {
        String id = str(c,"id");
        List<ExpenseSplit> splits = query("SELECT * FROM expense_splits WHERE expense_id=? ORDER BY user_id",s -> new ExpenseSplit(id,str(s,"user_id"),num(s,"share_paise")),id);
        return new Expense(id,str(c,"group_id"),str(c,"title"),num(c,"amount_paise"),str(c,"payer_id"),str(c,"creator_id"),str(c,"category"),num(c,"expense_date"),splits);
    }
    private Expense expense(String id) { return one("SELECT * FROM expenses WHERE id=?",this::expense,Rules.id(id)); }
    private List<Expense> expenses(String groupId) { return query("SELECT * FROM expenses WHERE group_id=? ORDER BY expense_date,id",this::expense,Rules.id(groupId)); }
    @Override public ObservableQuery<List<Expense>> observeExpenses(String groupId) { return observe(() -> expenses(groupId)); }
    @Override public void saveExpense(String actorId, Expense value, Executor executor, RepositoryCallback<Expense> callback) {
        submit(true, () -> {
            Rules.id(value.id); member(value.groupId,actorId); Rules.positive(value.amountPaise); Rules.date(value.expenseDate);
            boolean editing = exists("expenses",value.id);
            Expense old = editing ? expense(value.id) : null;
            if (editing && (!old.groupId.equals(value.groupId) || !old.creatorId.equals(value.creatorId))) throw new IllegalArgumentException("Group and creator are immutable");
            if (!editing && !actorId.equals(value.creatorId)) throw new IllegalArgumentException("Creator must be actor");
            if (editing && old.payerId.equals(value.payerId)) historicalMember(value.groupId,value.payerId); else member(value.groupId,value.payerId);
            List<String> ids = new ArrayList<>();
            Set<String> historical = new HashSet<>();
            if (old != null) for (ExpenseSplit split : old.splits) historical.add(split.userId);
            long sum = 0;
            for (ExpenseSplit split : value.splits) {
                if (!value.id.equals(split.expenseId)) throw new IllegalArgumentException("Split expense mismatch");
                if (historical.contains(split.userId)) historicalMember(value.groupId,split.userId); else member(value.groupId,split.userId);
                ids.add(split.userId); sum = Math.addExact(sum,split.sharePaise);
            }
            Map<String,Long> expected = Rules.equalSplit(value.amountPaise,ids);
            if (sum != value.amountPaise) throw new IllegalArgumentException("Shares must sum to total");
            for (ExpenseSplit split : value.splits) if (split.sharePaise != expected.get(split.userId)) throw new IllegalArgumentException("Only deterministic equal splits supported");
            ContentValues row = values("group_id",value.groupId,"title",Rules.text(value.title),"amount_paise",value.amountPaise,"payer_id",value.payerId,"creator_id",value.creatorId,"category",Rules.text(value.category),"expense_date",value.expenseDate);
            if (editing) { update("expenses",value.id,row); db().delete("expense_splits","expense_id=?",new String[]{value.id}); }
            else { row.put("id",value.id); insert("expenses",row); }
            for (ExpenseSplit split : value.splits) insert("expense_splits",values("expense_id",value.id,"group_id",value.groupId,"user_id",split.userId,"share_paise",split.sharePaise));
            balance(value.groupId); // Fail atomically on long overflow rather than persist unusable balances.
            ExpenseAmounts.total(expenses(value.groupId)); // Group totals must also remain representable.
            return expense(value.id);
        },executor,callback);
    }
    @Override public void deleteExpense(String actorId, String expenseId, Executor executor, RepositoryCallback<Boolean> callback) {
        submit(true, () -> { Expense value=expense(expenseId); member(value.groupId,actorId); db().delete("expenses","id=?",new String[]{expenseId}); balance(value.groupId); return true; },executor,callback);
    }
    private Settlement settlement(Cursor c) { return new Settlement(str(c,"id"),str(c,"group_id"),str(c,"payer_id"),str(c,"payee_id"),num(c,"amount_paise"),num(c,"settlement_date")); }
    private Settlement settlement(String id) { return one("SELECT * FROM settlements WHERE id=?",this::settlement,Rules.id(id)); }
    private List<Settlement> settlements(String groupId) { return query("SELECT * FROM settlements WHERE group_id=? ORDER BY settlement_date,id",this::settlement,Rules.id(groupId)); }
    @Override public ObservableQuery<List<Settlement>> observeSettlements(String groupId) { return observe(() -> settlements(groupId)); }
    @Override public void saveSettlement(String actorId, Settlement value, Executor executor, RepositoryCallback<Settlement> callback) {
        submit(true, () -> {
            Rules.id(value.id); member(value.groupId,actorId); Rules.positive(value.amountPaise); Rules.date(value.settlementDate);
            boolean editing=exists("settlements",value.id);
            if (editing) {
                Settlement old=settlement(value.id);
                if (!old.groupId.equals(value.groupId) || !old.payerId.equals(value.payerId) || !old.payeeId.equals(value.payeeId)) throw new IllegalArgumentException("Settlement parties and group are immutable");
                update("settlements",value.id,values("amount_paise",value.amountPaise,"settlement_date",value.settlementDate));
            } else {
                member(value.groupId,value.payerId); member(value.groupId,value.payeeId);
                insert("settlements",values("id",value.id,"group_id",value.groupId,"payer_id",value.payerId,"payee_id",value.payeeId,"amount_paise",value.amountPaise,"settlement_date",value.settlementDate));
            }
            balance(value.groupId); return settlement(value.id);
        },executor,callback);
    }
    @Override public void deleteSettlement(String actorId, String settlementId, Executor executor, RepositoryCallback<Boolean> callback) {
        submit(true, () -> { Settlement value=settlement(settlementId); member(value.groupId,actorId); db().delete("settlements","id=?",new String[]{settlementId}); balance(value.groupId); return true; },executor,callback);
    }
    private RecurringBill bill(Cursor c) {
        String id=str(c,"id");
        List<String> participants=query("SELECT user_id FROM recurring_participants WHERE bill_id=? ORDER BY user_id",s -> s.getString(0),id);
        return new RecurringBill(id,str(c,"group_id"),str(c,"title"),num(c,"amount_paise"),(int)num(c,"due_day"),participants,str(c,"handler_id"),(int)num(c,"reminder_lead_days"),num(c,"active")==1);
    }
    private RecurringBill bill(String id) { return one("SELECT * FROM recurring_bills WHERE id=?",this::bill,Rules.id(id)); }
    @Override public ObservableQuery<List<RecurringBill>> observeBills(String groupId) { return observe(() -> query("SELECT * FROM recurring_bills WHERE group_id=? ORDER BY id",this::bill,Rules.id(groupId))); }
    @Override public void saveBill(String actorId, RecurringBill value, Executor executor, RepositoryCallback<RecurringBill> callback) {
        submit(true, () -> {
            Rules.id(value.id); member(value.groupId,actorId); Rules.equalSplit(value.amountPaise,value.participantIds);
            Rules.dueDate(2000,1,value.dueDay);
            if (value.reminderLeadDays < 0) throw new IllegalArgumentException("Negative reminder lead time");
            boolean editing=exists("recurring_bills",value.id);
            RecurringBill old=editing ? bill(value.id) : null;
            if (editing && !old.groupId.equals(value.groupId)) throw new IllegalArgumentException("Bill group is immutable");
            // Disabling a bill is always possible, including after a participant leaves.
            for (String id : value.participantIds) {
                if (!value.active && old != null && old.participantIds.contains(id)) historicalMember(value.groupId,id);
                else member(value.groupId,id);
            }
            if (value.handlerId != null) {
                if (!value.active && old != null && value.handlerId.equals(old.handlerId)) historicalMember(value.groupId,value.handlerId);
                else member(value.groupId,value.handlerId);
            }
            ContentValues row=values("group_id",value.groupId,"title",Rules.text(value.title),"amount_paise",value.amountPaise,"due_day",value.dueDay,"handler_id",value.handlerId,"reminder_lead_days",value.reminderLeadDays,"active",value.active);
            if (editing) { update("recurring_bills",value.id,row); db().delete("recurring_participants","bill_id=?",new String[]{value.id}); }
            else { row.put("id",value.id); insert("recurring_bills",row); }
            for (String id : value.participantIds) insert("recurring_participants",values("bill_id",value.id,"group_id",value.groupId,"user_id",id));
            return bill(value.id);
        },executor,callback);
    }
    @Override public void deleteBill(String actorId, String billId, Executor executor, RepositoryCallback<Boolean> callback) {
        submit(true, () -> { member(bill(billId).groupId,actorId); return db().delete("recurring_bills","id=?",new String[]{billId})==1; },executor,callback);
    }
    private RecurringBillCycle cycle(Cursor c) {
        String id=str(c,"id");
        List<RecurringBillContribution> contributions=query("SELECT * FROM recurring_contributions WHERE cycle_id=? ORDER BY user_id",s -> new RecurringBillContribution(id,str(s,"user_id"),num(s,"share_paise"),num(s,"paid")==1),id);
        return new RecurringBillCycle(id,str(c,"bill_id"),(int)num(c,"year"),(int)num(c,"month"),num(c,"due_date"),num(c,"amount_paise"),str(c,"handler_id"),contributions);
    }
    private RecurringBillCycle cycle(String id) { return one("SELECT * FROM recurring_cycles WHERE id=?",this::cycle,Rules.id(id)); }
    @Override public ObservableQuery<List<RecurringBillCycle>> observeCycles(String billId) { return observe(() -> query("SELECT * FROM recurring_cycles WHERE bill_id=? ORDER BY year,month",this::cycle,Rules.id(billId))); }
    @Override public void createCycle(String actorId, String billId, int year, int month, Executor executor, RepositoryCallback<RecurringBillCycle> callback) {
        submit(true, () -> {
            RecurringBill value=bill(billId); member(value.groupId,actorId);
            if (!value.active) throw new IllegalStateException("Bill is inactive");
            for (String id : value.participantIds) member(value.groupId,id);
            if (value.handlerId != null) member(value.groupId,value.handlerId);
            String id=UUID.randomUUID().toString();
            insert("recurring_cycles",values("id",id,"bill_id",billId,"group_id",value.groupId,"year",year,"month",month,"due_date",Rules.dueDate(year,month,value.dueDay),"amount_paise",value.amountPaise,"handler_id",value.handlerId));
            for (Map.Entry<String,Long> share : Rules.equalSplit(value.amountPaise,value.participantIds).entrySet())
                insert("recurring_contributions",values("cycle_id",id,"group_id",value.groupId,"user_id",share.getKey(),"share_paise",share.getValue(),"paid",false));
            return cycle(id);
        },executor,callback);
    }
    @Override public void setContributionPaid(String actorId, String cycleId, String userId, boolean paid, Executor executor, RepositoryCallback<Boolean> callback) {
        submit(true, () -> {
            String groupId=one("SELECT group_id FROM recurring_cycles WHERE id=?",c -> c.getString(0),Rules.id(cycleId));
            member(groupId,actorId);
            if (db().update("recurring_contributions",values("paid",paid),"cycle_id=? AND user_id=?",new String[]{cycleId,Rules.id(userId)}) != 1) throw new NoSuchElementException("Contribution not found");
            return true;
        },executor,callback);
    }
}
