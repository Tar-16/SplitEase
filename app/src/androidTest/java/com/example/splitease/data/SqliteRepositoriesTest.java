package com.example.splitease.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.splitease.domain.Rules;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SqliteRepositoriesTest {
    private Context context;
    private String name;
    private SqliteRepositories repo;
    private ExecutorService callbacks;
    private final String a="00000000-0000-0000-0000-000000000001";
    private final String b="00000000-0000-0000-0000-000000000002";
    private final String outsider="00000000-0000-0000-0000-000000000003";
    private String group;
    private interface Call<T> { void start(RepositoryCallback<T> callback); }
    private <T> T await(Call<T> operation) throws Exception {
        CompletableFuture<T> future=new CompletableFuture<>();
        operation.start(new RepositoryCallback<T>() {
            @Override public void onSuccess(T value) { future.complete(value); }
            @Override public void onError(Exception error) { future.completeExceptionally(error); }
        });
        return future.get(30,TimeUnit.SECONDS);
    }
    private <T> T snapshot(ObservableQuery<T> query) throws Exception {
        CompletableFuture<T> future=new CompletableFuture<>();
        ObservableQuery.Subscription subscription=query.subscribe(callbacks,new ObservableQuery.Observer<T>() {
            @Override public void onChanged(T value) { future.complete(value); }
            @Override public void onError(Exception error) { future.completeExceptionally(error); }
        });
        try { return future.get(30,TimeUnit.SECONDS); } finally { subscription.close(); }
    }
    @Before public void setup() throws Exception {
        context=ApplicationProvider.getApplicationContext(); name="test-"+UUID.randomUUID()+".db";
        callbacks=Executors.newSingleThreadExecutor(r -> new Thread(r,"test-callback"));
        repo=new SqliteRepositories(context,name);
        await((RepositoryCallback<Boolean> c) -> repo.initialize(callbacks,c));
    }
    @After public void cleanup() { repo.close(); callbacks.shutdown(); /* Unique test files avoid races with asynchronous close. */ }
    private void seed() throws Exception {
        await((RepositoryCallback<User> c) -> repo.createUser(new User(a,"Alice","alice@example.com",null),"pass-one".toCharArray(),callbacks,c));
        await((RepositoryCallback<User> c) -> repo.createUser(new User(b,"Bob","bob@example.com",null),"pass-two".toCharArray(),callbacks,c));
        await((RepositoryCallback<User> c) -> repo.createUser(new User(outsider,"Other","other@example.com",null),"pass-three".toCharArray(),callbacks,c));
        group=UUID.randomUUID().toString();
        await((RepositoryCallback<Group> c) -> repo.createGroup(new Group(group,"Flat",Group.Type.FLAT,a,100),callbacks,c));
        await((RepositoryCallback<Boolean> c) -> repo.addMember(a,group,b,101,callbacks,c));
    }
    private Expense expense(String id,long amount) {
        List<ExpenseSplit> splits=new ArrayList<>();
        for (Map.Entry<String,Long> entry : Rules.equalSplit(amount,Arrays.asList(a,b)).entrySet()) splits.add(new ExpenseSplit(id,entry.getKey(),entry.getValue()));
        return new Expense(id,group,"Groceries",amount,a,a,"Food",200,splits);
    }
    private long count(SQLiteDatabase db,String table) {
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM "+table,null)) { c.moveToFirst(); return c.getLong(0); }
    }
    @Test public void groupCreationIsAtomicAndUsernameLookupIsNormalized() throws Exception {
        seed();
        MemberIdentity identity=await((RepositoryCallback<MemberIdentity> c) -> repo.findMemberByUsername(a,"  BOB  ",callbacks,c));
        assertEquals(b,identity.id); assertEquals("bob",identity.username);
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<MemberIdentity> c) -> repo.findMemberByUsername(a,"bo",callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<MemberIdentity> c) -> repo.findMemberByUsername(UUID.randomUUID().toString(),"bob",callbacks,c)));
        String id=UUID.randomUUID().toString();
        Group value=new Group(id,"  Weekend  ",Group.Type.TRIP,a,200);
        await((RepositoryCallback<Group> c) -> repo.createGroup(a,value,Arrays.asList(b,outsider),callbacks,c));
        GroupDetails detail=snapshot(repo.observeGroupDetails(b,id));
        assertEquals("Weekend",detail.group.name); assertEquals(Group.Type.TRIP,detail.group.type);
        assertEquals(3,detail.members.size()); assertEquals(a,detail.group.adminId); assertEquals(a,detail.group.creatorId);
        assertFalse(snapshot(repo.observeGroupDetails(a,id)).canLeave); assertTrue(detail.canLeave);
        for(List<String> ids : Arrays.asList(Arrays.asList(b,b),Arrays.asList(b,UUID.randomUUID().toString()))) {
            Group invalid=new Group(UUID.randomUUID().toString(),"Failed",Group.Type.FLAT,a,200);
            assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Group> c) -> repo.createGroup(a,invalid,ids,callbacks,c)));
        }
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Group> c) -> repo.createGroup(b,new Group(UUID.randomUUID().toString(),"Wrong actor",Group.Type.FLAT,a,200),Collections.emptyList(),callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Group> c) -> repo.createGroup(a,new Group(UUID.randomUUID().toString(),"  ",Group.Type.FLAT,a,200),Collections.emptyList(),callbacks,c)));
        assertEquals(2,snapshot(repo.observeGroupSummaries(a)).size());
        try(SplitEaseDatabase helper=new SplitEaseDatabase(context,name)) {
            assertEquals(2,count(helper.getReadableDatabase(),"groups"));
            assertEquals(5,count(helper.getReadableDatabase(),"group_members"));
        }
    }
    @Test public void adminMembershipPermissionsTransferAndHistoricalLeave() throws Exception {
        seed();
        assertTrue(snapshot(repo.observeGroupSummaries(outsider)).isEmpty());
        assertThrows(ExecutionException.class, () -> snapshot(repo.observeGroupDetails(outsider,group)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.addMember(b,group,outsider,200,callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.addMember(outsider,group,outsider,200,callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.addMember(a,group,b,200,callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.transferAdmin(b,group,a,callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.transferAdmin(a,group,outsider,callbacks,c)));
        await((RepositoryCallback<Boolean> c) -> repo.transferAdmin(a,group,b,callbacks,c));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.addMember(a,group,outsider,200,callbacks,c)));
        await((RepositoryCallback<Boolean> c) -> repo.addMember(b,group,outsider,200,callbacks,c));
        GroupDetails changed=snapshot(repo.observeGroupDetails(a,group));
        assertEquals(b,changed.group.adminId); assertEquals(a,changed.group.creatorId); assertEquals(3,changed.members.size());
        await((RepositoryCallback<Boolean> c) -> repo.leaveGroup(a,group,300,callbacks,c));
        assertTrue(snapshot(repo.observeGroupSummaries(a)).isEmpty());
        assertThrows(ExecutionException.class, () -> snapshot(repo.observeGroupDetails(a,group)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.transferAdmin(a,group,outsider,callbacks,c)));
        assertEquals(2,snapshot(repo.observeGroupDetails(b,group)).members.size());
        GroupMember departed=snapshot(repo.observeMembers(group)).get(0);
        assertEquals(GroupMember.Status.LEFT,departed.status); assertEquals(100,departed.joinedAt); assertEquals(Long.valueOf(300),departed.leftAt);
        await((RepositoryCallback<Boolean> c) -> repo.addMember(b,group,a,400,callbacks,c));
        GroupMember rejoined=snapshot(repo.observeMembers(group)).get(0);
        assertEquals(GroupMember.Status.ACTIVE,rejoined.status); assertEquals(100,rejoined.joinedAt); assertNull(rejoined.leftAt);
    }
    @Test public void detailObservationRevokesAccessAndBalanceEligibilityIsExact() throws Exception {
        seed();
        String expenseId=UUID.randomUUID().toString();
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(expenseId,2),callbacks,c));
        assertFalse(snapshot(repo.observeGroupDetails(b,group)).canLeave);
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.leaveGroup(b,group,300,callbacks,c)));
        await((RepositoryCallback<Boolean> c) -> repo.deleteExpense(a,expenseId,callbacks,c));
        assertTrue(snapshot(repo.observeGroupDetails(b,group)).canLeave);
        CompletableFuture<GroupDetails> initial=new CompletableFuture<>();
        CompletableFuture<Exception> revoked=new CompletableFuture<>();
        ObservableQuery.Subscription watch=repo.observeGroupDetails(b,group).subscribe(callbacks,new ObservableQuery.Observer<GroupDetails>() {
            @Override public void onChanged(GroupDetails value) { initial.complete(value); }
            @Override public void onError(Exception error) { revoked.complete(error); }
        });
        try {
            initial.get(10,TimeUnit.SECONDS);
            await((RepositoryCallback<Boolean> c) -> repo.leaveGroup(b,group,400,callbacks,c));
            assertNotNull(revoked.get(10,TimeUnit.SECONDS));
        } finally { watch.close(); }
    }
    @Test public void expenseSummariesRecalculateAndPersistAfterReopen() throws Exception {
        seed(); String id=UUID.randomUUID().toString();
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(id,101),callbacks,c));
        GroupDetails first=snapshot(repo.observeGroupDetails(b,group));
        assertEquals(101,first.totalExpenses); assertEquals(51,first.expenses.get(0).splits.get(0).sharePaise);
        assertEquals(Long.valueOf(-50),first.balances.get(b)); assertEquals(Long.valueOf(50),first.balances.get(a));
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(b,expense(id,201),callbacks,c));
        assertEquals(201,snapshot(repo.observeGroupSummaries(b)).get(0).totalExpenses);
        repo.close(); repo=new SqliteRepositories(context,name);
        GroupDetails reopened=snapshot(repo.observeGroupDetails(a,group));
        assertEquals(201,reopened.totalExpenses); assertEquals(Long.valueOf(-100),reopened.balances.get(b));
        assertThrows(ExecutionException.class,() -> snapshot(repo.observeGroupDetails(outsider,group)));
        assertThrows(ExecutionException.class,() -> await((RepositoryCallback<Expense> c) -> repo.saveExpense(outsider,expense(id,301),callbacks,c)));
        assertThrows(ExecutionException.class,() -> await((RepositoryCallback<Boolean> c) -> repo.deleteExpense(outsider,id,callbacks,c)));
        await((RepositoryCallback<Boolean> c) -> repo.deleteExpense(b,id,callbacks,c));
        GroupDetails deleted=snapshot(repo.observeGroupDetails(a,group));
        assertEquals(0,deleted.totalExpenses); assertTrue(deleted.expenses.isEmpty());
        assertEquals(Long.valueOf(0),deleted.balances.get(a)); assertEquals(Long.valueOf(0),deleted.balances.get(b));
    }
    @Test public void historicalAccountingIdentitiesSurviveDepartureAndEdits() throws Exception {
        seed(); String id=UUID.randomUUID().toString();
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(id,100),callbacks,c));
        await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(b,new Settlement(UUID.randomUUID().toString(),group,b,a,50,300),callbacks,c));
        await((RepositoryCallback<Boolean> c) -> repo.leaveGroup(b,group,400,callbacks,c));
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(id,300),callbacks,c));
        GroupDetails history=snapshot(repo.observeGroupDetails(a,group));
        assertEquals(1,history.members.size()); assertEquals("bob",history.names.get(b));
        assertEquals(Long.valueOf(-100),history.balances.get(b)); assertEquals(Long.valueOf(100),history.balances.get(a));
        assertThrows(ExecutionException.class,() -> await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(UUID.randomUUID().toString(),100),callbacks,c)));
        assertThrows(ExecutionException.class,() -> await((RepositoryCallback<Expense> c) -> repo.saveExpense(b,expense(id,200),callbacks,c)));
        await((RepositoryCallback<Boolean> c) -> repo.deleteExpense(a,id,callbacks,c));
        assertEquals(Long.valueOf(50),snapshot(repo.observeGroupDetails(a,group)).balances.get(b));
        assertEquals("bob",snapshot(repo.observeGroupDetails(a,group)).names.get(b));
    }
    @Test public void aggregateOverflowRollsBackEvenWhenEveryNetBalanceIsZero() throws Exception {
        seed(); String id=UUID.randomUUID().toString(), second=UUID.randomUUID().toString();
        Expense max=new Expense(id,group,"Solo",Long.MAX_VALUE,a,a,"Other",200,Collections.singletonList(new ExpenseSplit(id,a,Long.MAX_VALUE)));
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,max,callbacks,c));
        Expense extra=new Expense(second,group,"Overflow",1,b,b,"Other",201,Collections.singletonList(new ExpenseSplit(second,b,1)));
        assertThrows(ExecutionException.class,() -> await((RepositoryCallback<Expense> c) -> repo.saveExpense(b,extra,callbacks,c)));
        GroupDetails snapshot=snapshot(repo.observeGroupDetails(a,group));
        assertEquals(Long.MAX_VALUE,snapshot.totalExpenses); assertEquals(1,snapshot.expenses.size()); assertEquals(Long.valueOf(0),snapshot.balances.get(a));
        await((RepositoryCallback<Boolean> c) -> repo.deleteExpense(a,id,callbacks,c));
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(b,extra,callbacks,c));
        assertEquals(1,snapshot(repo.observeGroupDetails(b,group)).totalExpenses);
    }
    @Test public void settlementsEnforcePartiesDuplicatesAndCurrentSuggestionCaps() throws Exception {
        seed(); await((RepositoryCallback<Boolean> c) -> repo.addMember(a,group,outsider,200,callbacks,c));
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(UUID.randomUUID().toString(),200),callbacks,c));
        Settlement first=new Settlement(UUID.randomUUID().toString(),group,b,a,20,300);
        assertThrows(ExecutionException.class,() -> await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(outsider,first,callbacks,c)));
        assertThrows(ExecutionException.class,() -> await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(b,new Settlement(UUID.randomUUID().toString(),group,b,a,101,300),callbacks,c)));
        await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(b,first,callbacks,c));
        assertEquals(80,snapshot(repo.observeSettlementDetails(a,group)).suggestions.get(0).amountPaise);
        Settlement second=new Settlement(UUID.randomUUID().toString(),group,b,a,20,301);
        ExecutionException duplicate=assertThrows(ExecutionException.class,() -> await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(a,second,callbacks,c)));
        assertTrue(duplicate.getCause() instanceof DuplicateSettlementException);
        await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(a,second,true,callbacks,c));
        assertEquals(60,snapshot(repo.observeSettlementDetails(a,group)).suggestions.get(0).amountPaise);
        await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(outsider,new Settlement(first.id,group,b,a,30,86400000),callbacks,c));
        assertEquals(50,snapshot(repo.observeSettlementDetails(a,group)).suggestions.get(0).amountPaise);
        assertThrows(ExecutionException.class,() -> await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(a,new Settlement(first.id,group,a,b,30,86400000),callbacks,c)));
        assertThrows(ExecutionException.class,() -> await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(a,new Settlement(first.id,group,b,a,81,86400000),callbacks,c)));
        await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(a,new Settlement(UUID.randomUUID().toString(),group,b,a,50,400),callbacks,c));
        assertTrue(snapshot(repo.observeSettlementDetails(a,group)).suggestions.isEmpty());
        assertEquals(3,snapshot(repo.observeSettlementDetails(b,group)).records.size());
    }
    @Test public void settlementHistoryCorrectionsAndUpiProjectionRemainScoped() throws Exception {
        seed(); String expenseId=UUID.randomUUID().toString();
        await((RepositoryCallback<User> c) -> repo.updateUser(new User(a,"alice","alice@example.com","alice@upi"),callbacks,c));
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(expenseId,100),callbacks,c));
        assertEquals("alice@upi",snapshot(repo.observeSettlementDetails(b,group)).recipientUpi.get(a));
        assertThrows(ExecutionException.class,() -> snapshot(repo.observeSettlementDetails(outsider,group)));
        Settlement old=new Settlement(UUID.randomUUID().toString(),group,b,a,50,300);
        await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(a,old,callbacks,c));
        await((RepositoryCallback<Boolean> c) -> repo.leaveGroup(b,group,400,callbacks,c));
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(expenseId,200),callbacks,c));
        Settlement historical=new Settlement(UUID.randomUUID().toString(),group,b,a,50,86400000);
        await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(a,historical,callbacks,c));
        assertThrows(ExecutionException.class,() -> await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(b,new Settlement(old.id,group,b,a,20,500),callbacks,c)));
        await((RepositoryCallback<Boolean> c) -> repo.deleteExpense(a,expenseId,callbacks,c));
        // After underlying expense deletion the original amount can still be retained/reduced for correction.
        await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(a,new Settlement(old.id,group,b,a,40,500),callbacks,c));
        repo.close(); repo=new SqliteRepositories(context,name);
        SettlementDetails reopened=snapshot(repo.observeSettlementDetails(a,group));
        assertEquals(2,reopened.records.size()); assertEquals("bob",reopened.group.names.get(b));
        assertEquals(Long.valueOf(90),reopened.group.balances.get(b));
        assertEquals(b,reopened.suggestions.get(0).payeeId);
    }
    @Test public void credentialsCrudConstraintsAndReopen() throws Exception {
        seed();
        assertTrue(await((RepositoryCallback<Boolean> c) -> repo.verifyPassword(a,"pass-one".toCharArray(),callbacks,c)));
        assertFalse(await((RepositoryCallback<Boolean> c) -> repo.verifyPassword(a,"wrong".toCharArray(),callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<User> c) -> repo.createUser(new User(UUID.randomUUID().toString(),"ALICE","different@example.com",null),"password".toCharArray(),callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<User> c) -> repo.createUser(new User(UUID.randomUUID().toString(),"different","ALICE@EXAMPLE.COM",null),"password".toCharArray(),callbacks,c)));
        await((RepositoryCallback<User> c) -> repo.updateUser(new User(a,"Alice","alice@example.com","alice@upi"),callbacks,c));
        try(SplitEaseDatabase helper=new SplitEaseDatabase(context,name)) {
            SQLiteDatabase db=helper.getWritableDatabase();
            assertEquals(3,count(db,"users"));
            try(Cursor c=db.rawQuery("SELECT password_hash,password_salt,password_algorithm FROM users WHERE id=?",new String[]{a})) {
                assertTrue(c.moveToFirst()); assertEquals(32,c.getBlob(0).length); assertEquals(16,c.getBlob(1).length); assertEquals("argon2id-v19",c.getString(2));
            }
            assertThrows(SQLiteConstraintException.class, () -> db.execSQL("INSERT INTO group_members VALUES(?,?,'ACTIVE',0,NULL)",new Object[]{group,UUID.randomUUID().toString()}));
            assertThrows(SQLiteConstraintException.class, () -> db.execSQL("INSERT INTO group_members VALUES(?,?,'ACTIVE',0,NULL)",new Object[]{group,a}));
        }
        repo.close(); repo=new SqliteRepositories(context,name);
        User reopened=await((RepositoryCallback<User> c) -> repo.getUser(a,callbacks,c));
        assertEquals("alice@upi",reopened.upiId);
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.deleteUser(a,callbacks,c)));
        assertTrue(await((RepositoryCallback<Boolean> c) -> repo.deleteUser(outsider,callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<User> c) -> repo.getUser(outsider,callbacks,c)));
    }
    @Test public void expensesSettlementsMembershipAndPermissions() throws Exception {
        seed(); String expenseId=UUID.randomUUID().toString();
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(expenseId,101),callbacks,c));
        assertEquals(Long.valueOf(-50),snapshot(repo.observeBalances(group)).get(b));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.leaveGroup(b,group,300,callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.deleteExpense(outsider,expenseId,callbacks,c)));
        String settlementId=UUID.randomUUID().toString();
        await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(b,new Settlement(settlementId,group,b,a,20,300),callbacks,c));
        assertEquals(Long.valueOf(-30),snapshot(repo.observeBalances(group)).get(b));
        await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(a,new Settlement(settlementId,group,b,a,50,400),callbacks,c));
        assertEquals(1,snapshot(repo.observeSettlements(group)).size());
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Settlement> c) -> repo.saveSettlement(a,new Settlement(settlementId,group,a,b,50,400),callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.leaveGroup(a,group,500,callbacks,c)));
        await((RepositoryCallback<Boolean> c) -> repo.transferAdmin(a,group,b,callbacks,c));
        await((RepositoryCallback<Boolean> c) -> repo.leaveGroup(a,group,500,callbacks,c));
        List<GroupMember> members=snapshot(repo.observeMembers(group));
        assertEquals(GroupMember.Status.LEFT,members.get(0).status); assertEquals(Long.valueOf(500),members.get(0).leftAt);
        assertEquals(a,snapshot(repo.observeExpenses(group)).get(0).payerId);
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(b,expense(expenseId,101),callbacks,c));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.deleteExpense(a,expenseId,callbacks,c)));
        repo.close(); repo=new SqliteRepositories(context,name);
        assertEquals(101,snapshot(repo.observeExpenses(group)).get(0).amountPaise);
        assertEquals(50,snapshot(repo.observeSettlements(group)).get(0).amountPaise);
        await((RepositoryCallback<Boolean> c) -> repo.deleteExpense(b,expenseId,callbacks,c));
        await((RepositoryCallback<Boolean> c) -> repo.deleteSettlement(b,settlementId,callbacks,c));
        assertTrue(snapshot(repo.observeExpenses(group)).isEmpty());
    }
    @Test public void expenseReplacementRollsBackAfterMidTransactionFailure() throws Exception {
        seed(); String id=UUID.randomUUID().toString();
        await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(id,100),callbacks,c));
        try(SplitEaseDatabase helper=new SplitEaseDatabase(context,name)) {
            helper.getWritableDatabase().execSQL("CREATE TRIGGER fail_split BEFORE INSERT ON expense_splits WHEN NEW.user_id='"+b+"' BEGIN SELECT RAISE(ABORT,'injected failure'); END");
            assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Expense> c) -> repo.saveExpense(b,expense(id,200),callbacks,c)));
            Expense old=snapshot(repo.observeExpenses(group)).get(0);
            assertEquals(100,old.amountPaise); assertEquals(2,old.splits.size()); assertEquals(50,old.splits.get(0).sharePaise);
            assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(UUID.randomUUID().toString(),200),callbacks,c)));
            assertEquals(1,count(helper.getWritableDatabase(),"expenses"));
        }
    }
    @Test public void recurringSnapshotsRemainSeparateAndUnique() throws Exception {
        seed(); String id=UUID.randomUUID().toString();
        RecurringBill bill=new RecurringBill(id,group,"WiFi",99900,31,Arrays.asList(a,b),a,3,true);
        await((RepositoryCallback<RecurringBill> c) -> repo.saveBill(a,bill,callbacks,c));
        RecurringBillCycle first=await((RepositoryCallback<RecurringBillCycle> c) -> repo.createCycle(b,id,2026,2,callbacks,c));
        assertFalse(first.contributions.get(0).paid);
        await((RepositoryCallback<Boolean> c) -> repo.setContributionPaid(b,first.id,a,true,callbacks,c));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<RecurringBillCycle> c) -> repo.createCycle(a,id,2026,2,callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.setContributionPaid(outsider,first.id,a,false,callbacks,c)));
        RecurringBill changed=new RecurringBill(id,group,"WiFi new",120000,15,Arrays.asList(b),b,5,true);
        await((RepositoryCallback<RecurringBill> c) -> repo.saveBill(b,changed,callbacks,c));
        RecurringBillCycle second=await((RepositoryCallback<RecurringBillCycle> c) -> repo.createCycle(a,id,2026,3,callbacks,c));
        assertEquals(120000,second.amountPaise); assertEquals(1,second.contributions.size()); assertFalse(second.contributions.get(0).paid);
        repo.close(); repo=new SqliteRepositories(context,name);
        List<RecurringBillCycle> cycles=snapshot(repo.observeCycles(id));
        assertEquals(2,cycles.size()); assertEquals(99900,cycles.get(0).amountPaise); assertEquals(a,cycles.get(0).handlerId);
        assertEquals(49950,cycles.get(0).contributions.get(0).sharePaise); assertTrue(cycles.get(0).contributions.get(0).paid);
        assertTrue(snapshot(repo.observeExpenses(group)).isEmpty()); assertTrue(snapshot(repo.observeSettlements(group)).isEmpty()); assertTrue(snapshot(repo.observeBalances(group)).isEmpty());
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.deleteBill(a,id,callbacks,c)));
        assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.deleteGroup(a,group,callbacks,c)));
        await((RepositoryCallback<Boolean> c) -> repo.setContributionPaid(b,first.id,a,false,callbacks,c));
        assertFalse(snapshot(repo.observeCycles(id)).get(0).contributions.get(0).paid);
    }
    @Test public void migrationPreservesCoreDataAndRejectsDowngrade() throws Exception {
        String migrationName="migration-"+UUID.randomUUID()+".db";
        try(SplitEaseDatabase old=new SplitEaseDatabase(context,migrationName,1)) {
            SQLiteDatabase db=old.getWritableDatabase();
            db.execSQL("INSERT INTO users VALUES(?,?,?,NULL,?,?,?,2,19456,1)",new Object[]{a,"alice","alice@example.com",new byte[32],new byte[16],"argon2id-v19"});
            db.execSQL("INSERT INTO groups VALUES(?,?,'FLAT',?,100)",new Object[]{UUID.randomUUID().toString(),"Preserved",a});
            assertEquals(1,db.getVersion());
            assertThrows(SQLiteException.class, () -> count(db,"recurring_bills"));
        }
        try(SplitEaseDatabase upgraded=new SplitEaseDatabase(context,migrationName)) {
            SQLiteDatabase db=upgraded.getWritableDatabase();
            assertEquals(3,db.getVersion()); assertEquals(1,count(db,"users")); assertEquals(1,count(db,"groups")); assertEquals(0,count(db,"recurring_cycles"));
            try(Cursor creator=db.rawQuery("SELECT creator_id FROM groups",null)) { assertTrue(creator.moveToFirst()); assertEquals(a,creator.getString(0)); }
            try(Cursor c=db.rawQuery("PRAGMA foreign_key_check",null)) { assertEquals(0,c.getCount()); }
        }
        try(SplitEaseDatabase downgrade=new SplitEaseDatabase(context,migrationName,1)) {
            assertThrows(IllegalStateException.class,downgrade::getWritableDatabase);
        }
        try(SplitEaseDatabase again=new SplitEaseDatabase(context,migrationName)) { assertEquals(1,count(again.getWritableDatabase(),"users")); }
    }
    @Test public void groupCrudAndRejoiningPreserveMembershipIdentity() throws Exception {
        seed();
        Group changed=await((RepositoryCallback<Group> c) -> repo.updateGroup(b,new Group(group,"Trip",Group.Type.TRIP,a,100),callbacks,c));
        assertEquals("Trip",changed.name);
        await((RepositoryCallback<Boolean> c) -> repo.leaveGroup(b,group,300,callbacks,c));
        assertTrue(snapshot(repo.observeGroups(b)).isEmpty());
        await((RepositoryCallback<Boolean> c) -> repo.addMember(a,group,b,400,callbacks,c));
        List<GroupMember> members=snapshot(repo.observeMembers(group));
        assertEquals(2,members.size()); assertEquals(101,members.get(1).joinedAt); assertNull(members.get(1).leftAt);
        await((RepositoryCallback<Boolean> c) -> repo.transferAdmin(a,group,b,callbacks,c));
        Group transferred=snapshot(repo.observeGroups(b)).get(0);
        assertEquals(a,transferred.creatorId); assertEquals(b,transferred.adminId);
        await((RepositoryCallback<Boolean> c) -> repo.deleteGroup(b,group,callbacks,c));
        assertTrue(snapshot(repo.observeGroups(a)).isEmpty()); assertTrue(snapshot(repo.observeMembers(group)).isEmpty());
    }
    @Test public void observersReceiveCommittedSnapshotsAndNoFailedWriteNotification() throws Exception {
        seed();
        BlockingQueue<List<Expense>> updates=new LinkedBlockingQueue<>();
        ObservableQuery.Subscription subscription=repo.observeExpenses(group).subscribe(callbacks,new ObservableQuery.Observer<List<Expense>>() {
            @Override public void onChanged(List<Expense> value) { updates.add(value); }
            @Override public void onError(Exception error) { fail(error.toString()); }
        });
        try {
            assertTrue(updates.poll(10,TimeUnit.SECONDS).isEmpty());
            String id=UUID.randomUUID().toString();
            await((RepositoryCallback<Expense> c) -> repo.saveExpense(a,expense(id,101),callbacks,c));
            List<Expense> next=updates.poll(10,TimeUnit.SECONDS);
            assertNotNull(next); assertEquals(1,next.size()); assertEquals(2,next.get(0).splits.size());
            Expense invalid=new Expense(id,group,"Bad",101,a,a,"Food",200,Arrays.asList(new ExpenseSplit(id,a,50),new ExpenseSplit(id,b,50)));
            assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Expense> c) -> repo.saveExpense(b,invalid,callbacks,c)));
            // Awaited callback is on the same serial delivery executor as notifications.
            assertTrue(updates.isEmpty());
            assertEquals(101,snapshot(repo.observeExpenses(group)).get(0).amountPaise);
        } finally { subscription.close(); }
    }
    @Test public void callbacksUseRequestedThreadAndCancelledObserversDropQueuedEvents() throws Exception {
        CompletableFuture<String> thread=new CompletableFuture<>();
        repo.initialize(callbacks,new RepositoryCallback<Boolean>() {
            @Override public void onSuccess(Boolean value) { assertNotEquals(Looper.getMainLooper().getThread(),Thread.currentThread()); thread.complete(Thread.currentThread().getName()); }
            @Override public void onError(Exception error) { thread.completeExceptionally(error); }
        });
        assertEquals("test-callback",thread.get(10,TimeUnit.SECONDS));
        BlockingQueue<Runnable> delivery=new LinkedBlockingQueue<>(); AtomicInteger received=new AtomicInteger();
        ObservableQuery.Subscription subscription=repo.observeGroups(a).subscribe(delivery::add,new ObservableQuery.Observer<List<Group>>() {
            @Override public void onChanged(List<Group> value) { received.incrementAndGet(); }
            @Override public void onError(Exception error) { received.incrementAndGet(); }
        });
        Runnable queued=delivery.poll(10,TimeUnit.SECONDS); assertNotNull(queued);
        subscription.close(); queued.run(); assertEquals(0,received.get());
        repo.close(); assertThrows(ExecutionException.class, () -> await((RepositoryCallback<Boolean> c) -> repo.initialize(callbacks,c)));
    }
}
