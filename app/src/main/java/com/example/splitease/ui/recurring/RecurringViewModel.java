package com.example.splitease.ui.recurring;
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

/** Session-bound recurring presentation; SQL and scheduling stay behind application services. */
public final class RecurringViewModel extends AndroidViewModel {
 public static final class State {
  public final RecurringSnapshot data;public final boolean loading,busy,editing;public final int error;public final String savedId;
  State(RecurringViewModel m) {data=m.data;loading=m.loading;busy=m.busy;error=m.error;editing=m.editing;savedId=m.savedId;}
 }
 private final SplitEaseApplication app;private final SavedStateHandle saved;
 private final Executor main=new Handler(Looper.getMainLooper())::post;
 private final MutableLiveData<State> state=new MutableLiveData<>();
 private ObservableQuery.Subscription sessions,watch;
 private String actor,billId,savedId;private RecurringSnapshot data;
 private int epoch,error;private boolean loading=true,busy,editing;
 public RecurringViewModel(Application application,SavedStateHandle saved) {super(application);app=(SplitEaseApplication)application;this.saved=saved;emit();}
 public void configure(String billId,boolean create) {this.billId=billId;editing=create||Boolean.TRUE.equals(saved.get("editing"));}
 public LiveData<State> state() {return state;}
 private void emit() {state.setValue(new State(this));}
 public String text(String key) {String v=saved.get(key);return v==null?"":v;}
 public void text(String key,String value) {saved.set(key,value);}
 public boolean active() {return !Boolean.FALSE.equals(saved.get("active"));}
 public void active(boolean value) {saved.set("active",value);}
 public ArrayList<String> selected() {ArrayList<String> ids=saved.get("participants");return ids==null?new ArrayList<>():new ArrayList<>(ids);}
 public void select(String id,boolean include) {ArrayList<String> ids=selected();ids.remove(id);if(include) ids.add(id);saved.set("participants",ids);emit();}
 public void group(String id) {if(!id.equals(text("group"))) {text("group",id);saved.set("participants",new ArrayList<String>());text("handler","");}emit();}
 public void handler(String id) {text("handler",id);}
 public GroupDetails group() {if(data!=null) for(GroupDetails g:data.groups) if(g.group.id.equals(text("group"))) return g;return null;}
 public RecurringBill bill() {if(data!=null) for(RecurringBill b:data.bills) if(b.id.equals(billId)) return b;return null;}
 public String name(String id) {return data==null?id:data.names.getOrDefault(id,id);}
 public String groupName(String id) {if(data!=null) for(GroupDetails g:data.groups) if(g.group.id.equals(id)) return g.group.name;return id;}
 public void start() {
  if(sessions!=null) return;
  sessions=app.auth().sessions().subscribe(main,new ObservableQuery.Observer<SessionState>() {
   @Override public void onChanged(SessionState s) {
    String id=s.status==SessionState.Status.SIGNED_IN?s.user.id:null;
    if(!Objects.equals(actor,id)) {epoch++;data=null;busy=false;error=0;savedId=null;
     if(!Objects.equals(id,saved.get("owner"))) {for(String key:Arrays.asList("title","amount","group","handler","day","lead","participants","active","loaded","filter","editing")) saved.remove(key);}
     actor=id;saved.set("owner",id);
    }
    refresh();
   }
   @Override public void onError(Exception failure) {data=null;loading=false;error=R.string.bill_load_error;emit();}
  });
 }
 public void stop() {if(sessions!=null)sessions.close();sessions=null;if(watch!=null)watch.close();watch=null;}
 public void refresh() {
  if(watch!=null)watch.close();watch=null;loading=actor!=null;emit();if(actor==null)return;
  int token=epoch;
  app.recurringBills().ensureCycles(actor,System.currentTimeMillis(),main,new RepositoryCallback<Integer>() {
   @Override public void onSuccess(Integer count) {if(token!=epoch)return;subscribe(token);}
   @Override public void onError(Exception failure) {if(token==epoch){loading=false;error=R.string.bill_load_error;emit();}}
  });
 }
 private void subscribe(int token) {
  if(sessions==null)return;
  if(watch!=null)watch.close();
  watch=app.recurringBills().observeRecurring(actor).subscribe(main,new ObservableQuery.Observer<RecurringSnapshot>() {
   @Override public void onChanged(RecurringSnapshot value) {
    if(token!=epoch)return;data=value;loading=false;error=0;
    if(billId!=null && bill()==null) error=R.string.bill_unavailable;
    if(bill()!=null && !billId.equals(text("loaded"))) loadDraft();
    if(billId==null && text("day").isEmpty()) {text("day","1");text("lead","3");}
    if(billId==null && text("group").isEmpty() && !data.groups.isEmpty()) group(data.groups.get(0).group.id);
    emit();
   }
   @Override public void onError(Exception failure){if(token==epoch){data=null;loading=false;error=R.string.bill_load_error;emit();}}
  });
 }
 private void loadDraft() {RecurringBill b=bill();if(b==null)return;text("title",b.title);text("amount",ExpenseAmounts.input(b.amountPaise));text("group",b.groupId);text("handler",b.handlerId==null?"":b.handlerId);text("day",String.valueOf(b.dueDay));text("lead",String.valueOf(b.reminderLeadDays));active(b.active);saved.set("participants",new ArrayList<>(b.participantIds));text("loaded",billId);}
 public void edit() {if(!busy && bill()!=null){loadDraft();editing=true;saved.set("editing",true);emit();}}
 public void consumeSaved() {savedId=null;}
 public String preview() {try {Map<String,Long> shares=Rules.equalSplit(ExpenseAmounts.parse(text("amount")),selected());StringBuilder result=new StringBuilder();for(Map.Entry<String,Long> share:shares.entrySet()) result.append(name(share.getKey())).append(": ").append(com.example.splitease.util.InrFormatter.format(share.getValue())).append('\n');return result.toString().trim();}catch(IllegalArgumentException|ArithmeticException failure){return getApplication().getString(R.string.bill_share_hint);}}
 public void changed() {emit();}
 public void save() {
  if(actor==null || busy || group()==null)return;
  final RecurringBill value;
  try {
   int day=Integer.parseInt(text("day")),lead=Integer.parseInt(text("lead"));Rules.dueDate(2026,1,day);if(lead<0||lead>31)throw new IllegalArgumentException();
   if(text("handler").isEmpty())throw new IllegalArgumentException();
   value=new RecurringBill(billId==null?UUID.randomUUID().toString():billId,text("group"),Rules.text(text("title")),ExpenseAmounts.parse(text("amount")),day,selected(),text("handler"),lead,active());Rules.equalSplit(value.amountPaise,value.participantIds);
  }catch(IllegalArgumentException|ArithmeticException failure){error=R.string.bill_invalid;emit();return;}
  int token=epoch;busy=true;error=0;emit();
  app.recurringBills().saveBill(actor,value,main,new RepositoryCallback<RecurringBill>() {
   @Override public void onSuccess(RecurringBill result){if(token==epoch){busy=false;editing=false;saved.set("editing",false);billId=result.id;loadDraft();savedId=result.id;app.reminders().refresh(true);emit();}}
   @Override public void onError(Exception failure){if(token==epoch){busy=false;error=R.string.bill_save_error;emit();}}
  });
 }
 public void paid(String cycle,String participant,boolean paid) {
  if(actor==null||busy)return;int token=epoch;busy=true;error=0;emit();
  app.recurringBills().setContributionPaid(actor,cycle,participant,paid,main,new RepositoryCallback<Boolean>() {
   @Override public void onSuccess(Boolean result){if(token==epoch){busy=false;app.reminders().refresh(true);emit();}}
   @Override public void onError(Exception failure){if(token==epoch){busy=false;error=R.string.bill_save_error;emit();}}
  });
 }
 @Override protected void onCleared(){stop();epoch++;}
}
