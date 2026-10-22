package com.example.splitease.ui.settlements;

import android.app.Application;
import android.net.Uri;
import android.os.*;
import androidx.lifecycle.*;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.domain.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.example.splitease.util.UpiPayment;
import java.util.*;
import java.util.concurrent.Executor;

public final class SettlementsViewModel extends AndroidViewModel {
    public static final class State {
        public final SettlementDetails details;
        public final String actor;
        public final boolean busy,loading,duplicate,selected,editing;
        public final int error;
        State(SettlementsViewModel m) { details=m.details; actor=m.actor; busy=m.busy; loading=m.loading; duplicate=m.duplicate; selected=!m.text("payer").isEmpty(); editing=!m.text("record").isEmpty(); error=m.error; }
    }
    private final AuthService auth;
    private final SettlementRepository repository;
    private final SavedStateHandle saved;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Executor main=handler::post;
    private final MutableLiveData<State> state=new MutableLiveData<>();
    private ObservableQuery.Subscription sessionWatch,dataWatch;
    private String actor,groupId;
    private SettlementDetails details;
    private int epoch,error;
    private boolean busy,loading=true,duplicate;
    public SettlementsViewModel(Application application,SavedStateHandle saved) {
        super(application); this.saved=saved; SplitEaseApplication app=(SplitEaseApplication)application; auth=app.auth(); repository=app.settlements(); emit();
    }
    public void configure(String groupId) { this.groupId=groupId; }
    public LiveData<State> state() { return state; }
    public String text(String key) { String value=saved.get(key); return value==null?"":value; }
    public void amount(String value) { saved.set("amount",value); }
    public long date() { Long value=saved.get("date"); return value==null?System.currentTimeMillis():value; }
    public void date(long value) { saved.set("date",value); emit(); }
    private void emit() { state.setValue(new State(this)); }
    public void start() {
        if(sessionWatch!=null) return;
        sessionWatch=auth.sessions().subscribe(main,new ObservableQuery.Observer<SessionState>() {
            @Override public void onChanged(SessionState value) {
                String id=value.status==SessionState.Status.SIGNED_IN?value.user.id:null;
                if(!Objects.equals(actor,id)) {
                    epoch++; busy=false; duplicate=false; details=null;
                    if(id==null || !id.equals(saved.get("owner"))) clearSelection();
                    actor=id; saved.set("owner",id);
                }
                refresh();
            }
            @Override public void onError(Exception failure) { details=null; loading=false; error=R.string.settlement_error; emit(); }
        });
    }
    public void stop() { if(sessionWatch!=null) sessionWatch.close(); sessionWatch=null; if(dataWatch!=null) dataWatch.close(); dataWatch=null; }
    public void refresh() {
        if(dataWatch!=null) dataWatch.close(); dataWatch=null;
        loading=actor!=null; error=0; emit(); if(actor==null) return;
        int token=epoch;
        dataWatch=repository.observeSettlementDetails(actor,groupId).subscribe(main,new ObservableQuery.Observer<SettlementDetails>() {
            @Override public void onChanged(SettlementDetails value) { if(token==epoch) { details=value; loading=false; emit(); } }
            @Override public void onError(Exception failure) { if(token==epoch) { details=null; loading=false; error=R.string.settlement_error; emit(); } }
        });
    }
    public void select(Transfer transfer) {
        if(busy || details==null) return;
        saved.set("record",""); saved.set("payer",transfer.payerId); saved.set("payee",transfer.payeeId);
        saved.set("amount",ExpenseAmounts.input(transfer.amountPaise)); saved.set("date",System.currentTimeMillis()); error=0; emit();
    }
    public void select(Settlement record) {
        if(busy || details==null) return;
        saved.set("record",record.id); saved.set("payer",record.payerId); saved.set("payee",record.payeeId);
        saved.set("amount",ExpenseAmounts.input(record.amountPaise)); saved.set("date",record.settlementDate); error=0; emit();
    }
    private Settlement old() { if(details!=null) for(Settlement record:details.records) if(record.id.equals(text("record"))) return record; return null; }
    public long limit() {
        if(details==null) return 0;
        try {
        Settlement old=old();
        if(!text("record").isEmpty()) return old==null?0:SettlementCalculator.editLimit(details.group.expenses,details.records,old);
        return SettlementCalculator.suggestedLimit(details.group.balances,text("payer"),text("payee"));
        } catch(IllegalArgumentException | ArithmeticException failure) { return 0; }
    }
    public boolean mayRecord() { return actor!=null && details!=null && (old()!=null || actor.equals(text("payer")) || actor.equals(text("payee"))); }
    public String upi() { return details==null?null:details.recipientUpi.get(text("payee")); }
    public Uri paymentUri() {
        if(details==null || actor==null || !actor.equals(text("payer")) || !text("record").isEmpty()) throw new IllegalArgumentException();
        // Always uses the currently selected recommendation, never an edited/manual record amount.
        long suggested=SettlementCalculator.suggestedLimit(details.group.balances,text("payer"),text("payee"));
        return UpiPayment.uri(upi(),details.group.names.get(text("payee")),suggested,"SplitEase: "+details.group.group.name);
    }
    public void paymentUnavailable() { error=R.string.upi_unavailable; emit(); }
    public void save(boolean acknowledgeDuplicate) {
        if(busy || !mayRecord()) return;
        final Settlement value;
        try {
            long amount=ExpenseAmounts.parse(text("amount")); if(amount>limit()) throw new IllegalArgumentException();
            Rules.date(date());
            value=new Settlement(text("record").isEmpty()?UUID.randomUUID().toString():text("record"),groupId,text("payer"),text("payee"),amount,date());
        } catch(IllegalArgumentException | ArithmeticException failure) { error=R.string.settlement_invalid; emit(); return; }
        int token=epoch; busy=true; duplicate=false; error=0; emit();
        repository.saveSettlement(actor,value,acknowledgeDuplicate,main,new RepositoryCallback<Settlement>() {
            @Override public void onSuccess(Settlement result) { if(token==epoch) { busy=false; clearSelection(); emit(); } }
            @Override public void onError(Exception failure) { if(token==epoch) { busy=false; duplicate=failure instanceof DuplicateSettlementException; error=duplicate?0:R.string.settlement_invalid; emit(); } }
        });
    }
    public void consumeDuplicate() { duplicate=false; }
    private void clearSelection() { for(String key:Arrays.asList("record","payer","payee","amount","date")) saved.remove(key); }
    @Override protected void onCleared() { stop(); epoch++; }
}
