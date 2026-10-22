package com.example.splitease.ui.expenses;

import android.app.Application;
import android.os.*;
import androidx.lifecycle.*;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.domain.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Session-bound expense editor; drafts survive recreation without retaining views. */
public final class ExpenseViewModel extends AndroidViewModel {
    public static final List<String> CATEGORIES=Collections.unmodifiableList(Arrays.asList("Food","Travel","Rent","Bills","Other"));
    public static final class State {
        public final GroupDetails group;
        public final Expense expense;
        public final boolean loading,busy,editing,done;
        public final int error;
        State(ExpenseViewModel m) { group=m.group; expense=m.original; loading=m.loading; busy=m.busy; editing=m.editing(); done=m.done; error=m.error; }
    }
    private final AuthService auth;
    private final GroupRepository groups;
    private final ExpenseRepository expenses;
    private final SavedStateHandle saved;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Executor main=handler::post;
    private final MutableLiveData<State> state=new MutableLiveData<>();
    private ObservableQuery.Subscription sessionWatch,groupWatch;
    private String actor,groupId,expenseId;
    private GroupDetails group;
    private Expense original;
    private boolean loading=true,busy,done;
    private int epoch,error;
    public ExpenseViewModel(Application application,SavedStateHandle saved) {
        super(application); this.saved=saved;
        SplitEaseApplication app=(SplitEaseApplication)application; auth=app.auth(); groups=app.groups(); expenses=app.expenses(); emit();
    }
    public void configure(String groupId,String expenseId) { this.groupId=groupId; this.expenseId=expenseId; }
    public LiveData<State> state() { return state; }
    private void emit() { state.setValue(new State(this)); }
    public boolean editing() { return Boolean.TRUE.equals(saved.get("editing")); }
    public String text(String key) { String value=saved.get(key); return value==null?"":value; }
    public void text(String key,String value) { saved.set(key,value); }
    public long date() { Long value=saved.get("date"); return value==null ? System.currentTimeMillis():value; }
    public void date(long value) { saved.set("date",value); emit(); }
    public List<String> selected() { ArrayList<String> values=saved.get("participants"); return values==null?Collections.emptyList():new ArrayList<>(values); }
    public void participant(String id,boolean include) {
        List<String> values=selected(); if(include && !values.contains(id)) values.add(id); if(!include) values.remove(id);
        saved.set("participants",new ArrayList<>(values));
    }
    public Map<String,Long> preview() { return Rules.equalSplit(ExpenseAmounts.parse(text("amount")),selected()); }
    public void start() {
        if(sessionWatch!=null) return;
        sessionWatch=auth.sessions().subscribe(main,new ObservableQuery.Observer<SessionState>() {
            @Override public void onChanged(SessionState session) {
                String id=session.status==SessionState.Status.SIGNED_IN?session.user.id:null;
                if(!Objects.equals(id,actor)) {
                    epoch++; busy=false; done=false; group=null; original=null;
                    if(id!=null && !id.equals(saved.get("owner"))) {
                        for(String key:new ArrayList<>(saved.keys())) saved.remove(key);
                        saved.set("owner",id);
                    }
                    actor=id;
                }
                load();
            }
            @Override public void onError(Exception failure) { group=null; loading=false; error=R.string.expense_load_error; emit(); }
        });
    }
    public void stop() { if(sessionWatch!=null) sessionWatch.close(); sessionWatch=null; if(groupWatch!=null) groupWatch.close(); groupWatch=null; }
    public void load() {
        if(groupWatch!=null) groupWatch.close(); groupWatch=null;
        loading=actor!=null; error=0; emit(); if(actor==null) return;
        int token=epoch;
        groupWatch=groups.observeGroupDetails(actor,groupId).subscribe(main,new ObservableQuery.Observer<GroupDetails>() {
            @Override public void onChanged(GroupDetails value) {
                if(token!=epoch) return;
                group=value; loading=false;
                original=null;
                if(expenseId!=null) for(Expense expense:value.expenses) if(expense.id.equals(expenseId)) original=expense;
                if(expenseId!=null && original==null) { group=null; if(!busy && !done) error=R.string.expense_missing; emit(); return; }
                if(!Boolean.TRUE.equals(saved.get("initialized")) || !editing()) {
                    saved.set("title",original==null?"":original.title);
                    saved.set("amount",original==null?"":ExpenseAmounts.input(original.amountPaise));
                    saved.set("payer",original==null?actor:original.payerId);
                    saved.set("category",original==null?"Other":original.category);
                    saved.set("date",original==null?System.currentTimeMillis():original.expenseDate);
                    ArrayList<String> ids=new ArrayList<>();
                    if(original!=null) for(ExpenseSplit split:original.splits) ids.add(split.userId);
                    saved.set("participants",ids); saved.set("editing",original==null); saved.set("initialized",true);
                }
                error=0; emit();
            }
            @Override public void onError(Exception failure) { if(token==epoch) { group=null; original=null; loading=false; error=R.string.expense_load_error; emit(); } }
        });
    }
    /** Current members plus the original payer/participants, retained only for this older expense. */
    public List<String> participantOptions() {
        LinkedHashSet<String> ids=new LinkedHashSet<>();
        if(group!=null) for(MemberIdentity member:group.members) ids.add(member.id);
        if(original!=null) for(ExpenseSplit split:original.splits) ids.add(split.userId);
        return new ArrayList<>(ids);
    }
    public List<String> payerOptions() {
        List<String> ids=new ArrayList<>(); if(group!=null) for(MemberIdentity member:group.members) ids.add(member.id);
        if(original!=null && !ids.contains(original.payerId)) ids.add(original.payerId);
        return ids;
    }
    public boolean current(String id) { if(group!=null) for(MemberIdentity member:group.members) if(member.id.equals(id)) return true; return false; }
    public void edit() { if(group!=null && original!=null && !busy) { saved.set("initialized",false); saved.set("editing",true); // Initialize from the latest snapshot, not a stale display draft.
        saved.set("title",original.title); saved.set("amount",ExpenseAmounts.input(original.amountPaise)); saved.set("payer",original.payerId); saved.set("category",original.category); saved.set("date",original.expenseDate);
        ArrayList<String> ids=new ArrayList<>(); for(ExpenseSplit split:original.splits) ids.add(split.userId); saved.set("participants",ids); saved.set("initialized",true); emit(); } }
    public void save() {
        if(actor==null || group==null || busy || !editing()) return;
        final Expense value;
        try {
            String title=Rules.text(text("title")); long amount=ExpenseAmounts.parse(text("amount"));
            if(!CATEGORIES.contains(text("category")) && (original==null || !original.category.equals(text("category")))) throw new IllegalArgumentException();
            if(!payerOptions().contains(text("payer")) || !participantOptions().containsAll(selected())) throw new IllegalArgumentException();
            String id=original==null?UUID.randomUUID().toString():original.id;
            List<ExpenseSplit> splits=new ArrayList<>();
            for(Map.Entry<String,Long> share:Rules.equalSplit(amount,selected()).entrySet()) splits.add(new ExpenseSplit(id,share.getKey(),share.getValue()));
            Rules.date(date());
            value=new Expense(id,groupId,title,amount,text("payer"),original==null?actor:original.creatorId,text("category"),date(),splits);
        } catch(IllegalArgumentException | ArithmeticException failure) { error=R.string.expense_invalid; emit(); return; }
        int token=epoch; busy=true; error=0; emit();
        expenses.saveExpense(actor,value,main,new RepositoryCallback<Expense>() {
            @Override public void onSuccess(Expense expense) { if(token==epoch) { busy=false; done=true; emit(); } }
            @Override public void onError(Exception failure) { failed(token); }
        });
    }
    public void delete() {
        if(actor==null || original==null || group==null || busy) return;
        int token=epoch; busy=true; error=0; emit();
        expenses.deleteExpense(actor,original.id,main,new RepositoryCallback<Boolean>() {
            @Override public void onSuccess(Boolean value) { if(token==epoch) { busy=false; done=true; emit(); } }
            @Override public void onError(Exception failure) { failed(token); }
        });
    }
    private void failed(int token) { if(token==epoch) { busy=false; error=R.string.expense_save_error; emit(); } }
    public void consumeDone() { done=false; }
    @Override protected void onCleared() { stop(); epoch++; }
}
