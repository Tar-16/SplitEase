package com.example.splitease.ui.expenses;

import android.app.Application;
import android.os.*;
import android.graphics.Bitmap;
import android.net.Uri;
import com.example.splitease.platform.receipts.ReceiptCaptureService;
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
        public final Bitmap receiptPreview;
        public final String receiptId;
        public final Long detectedPaise;
        public final ReceiptTotalDetector.Reliability receiptReliability;
        public final String receiptEvidence,receiptDebug;
        public final int receiptMessage;
        public final Uri captureUri;
        public final boolean capturePending,receiptAmountApplied;
        State(ExpenseViewModel m) { group=m.group; expense=m.original; loading=m.loading; busy=m.busy||m.receiptWorking||!m.text("capture_id").isEmpty(); editing=m.editing(); done=m.done; error=m.error;
            receiptReliability=m.receiptDetection==null?null:m.receiptDetection.reliability;receiptEvidence=m.receiptDetection==null?null:m.receiptDetection.supportingLabel;receiptDebug=m.receiptDetection==null?null:m.receiptDetection.debugReport;receiptAmountApplied=m.receiptAmountApplied;receiptPreview=m.receiptPreview;receiptId=m.visibleReceiptId();detectedPaise=m.detectedPaise;receiptMessage=m.receiptMessage;captureUri=m.captureTarget;capturePending=!m.text("capture_id").isEmpty(); }
    }
    private final AuthService auth;
    private final GroupRepository groups;
    private final ExpenseRepository expenses;
    private final SavedStateHandle saved;
    private final ReceiptStorageRepository receipts;
    private final ReceiptCaptureService capture;
    private Bitmap receiptPreview;
    private ReceiptMetadata receiptMetadata;
    private Long detectedPaise;
    private ReceiptTotalDetector.Detection receiptDetection;
    private boolean receiptAmountApplied;
    private int receiptMessage=R.string.receipt_hint;
    private boolean receiptWorking,sessionReady;
    private String receiptCheckedExpense;
    private Uri captureTarget;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Executor main=handler::post;
    private final MutableLiveData<State> state=new MutableLiveData<>();
    private ObservableQuery.Subscription sessionWatch,groupWatch;
    private String actor,groupId,expenseId;
    private GroupDetails group;
    private Expense original;
    private boolean loading=true,busy,done;
    private int epoch,error,captureVersion;
    public ExpenseViewModel(Application application,SavedStateHandle saved) {
        super(application); this.saved=saved;saved.remove("receipt_loaded_instance");
        SplitEaseApplication app=(SplitEaseApplication)application; auth=app.auth(); groups=app.groups(); expenses=app.expenses();receipts=app.receipts();capture=app.receiptCapture();emit();
    }
    public void configure(String groupId,String expenseId) { this.groupId=groupId; this.expenseId=expenseId; }
    public LiveData<State> state() { return state; }
    private void emit() { state.setValue(new State(this)); }
    public boolean editing() { return Boolean.TRUE.equals(saved.get("editing")); }
    public String text(String key) { String value=saved.get(key); return value==null?"":value; }
    public void text(String key,String value) { boolean clear="amount".equals(key)&&receiptAmountApplied&&!value.equals(text(key));saved.set(key,value);if(clear){receiptAmountApplied=false;emit();} }
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
        sessionReady=false;
        sessionWatch=auth.sessions().subscribe(main,new ObservableQuery.Observer<SessionState>() {
            @Override public void onChanged(SessionState session) {
                String id=session.status==SessionState.Status.SIGNED_IN?session.user.id:null;
                if(!Objects.equals(id,actor)) {
                    boolean restoringOwner=actor==null&&id!=null&&id.equals(saved.get("owner"));
                    if(!restoringOwner){releaseReceiptDraft(actor);capture.cancel(text("capture_id"));}
                    epoch++; busy=false; done=false; group=null; original=null;receiptPreview=null;receiptMetadata=null;receiptWorking=false;receiptCheckedExpense=null;detectedPaise=null;receiptDetection=null;receiptAmountApplied=false;captureTarget=null;receiptMessage=R.string.receipt_hint;
                    if(!restoringOwner){saved.remove("capture_id");saved.remove("capture_uri");saved.remove("capture_launched");saved.remove("capture_result");saved.remove("receipt_draft");saved.remove("receipt_remove");}
                    else if(!text("capture_id").isEmpty()&&!text("capture_uri").isEmpty()&&!Boolean.TRUE.equals(saved.get("capture_launched")))captureTarget=Uri.parse(text("capture_uri"));
                    if(id!=null && !id.equals(saved.get("owner"))) {
                        for(String key:new ArrayList<>(saved.keys())) saved.remove(key);
                        saved.set("owner",id);
                    }
                    actor=id;
                }
                sessionReady=id!=null;load();
            }
            @Override public void onError(Exception failure) { group=null; loading=false; error=R.string.expense_load_error; emit(); }
        });
    }
    public void stop() { sessionReady=false;if(sessionWatch!=null) sessionWatch.close(); sessionWatch=null; if(groupWatch!=null) groupWatch.close(); groupWatch=null; }
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
                if(expenseId!=null && original==null) { captureVersion++;capture.cancel(text("capture_id"));clearCapture();receiptWorking=false;receiptPreview=null;group=null; if(!busy && !done) error=R.string.expense_missing; emit(); return; }
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
                if(!receiptWorking && (!Boolean.TRUE.equals(saved.get("receipt_loaded_instance")) || !Objects.equals(receiptCheckedExpense,expenseId)||receiptPreview==null && !text("receipt_draft").isEmpty())) loadReceipt();
                if(!text("capture_id").isEmpty() && saved.contains("capture_result")) processCapture();
                error=0; emit();
            }
            @Override public void onError(Exception failure) { if(token==epoch) { captureVersion++;capture.cancel(text("capture_id"));clearCapture();receiptWorking=false;group=null; original=null;receiptPreview=null; loading=false; error=R.string.expense_load_error; emit(); } }
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
        if(actor==null || group==null || busy || receiptWorking || !text("capture_id").isEmpty() || !editing()) return;
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
        expenses.saveExpense(actor,value,receiptChange(),main,new RepositoryCallback<Expense>() {
            @Override public void onSuccess(Expense expense) { if(token==epoch) { busy=false;saved.remove("receipt_draft"); done=true; emit(); } }
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
    public String visibleReceiptId() {
        if(Boolean.TRUE.equals(saved.get("receipt_remove")))return "";
        return !text("receipt_draft").isEmpty()?text("receipt_draft"):receiptMetadata==null?"":receiptMetadata.id;
    }
    private ReceiptChange receiptChange(){return Boolean.TRUE.equals(saved.get("receipt_remove"))?ReceiptChange.remove():text("receipt_draft").isEmpty()?ReceiptChange.keep():ReceiptChange.replace(text("receipt_draft"));}
    private void releaseReceiptDraft(String owner){String id=text("receipt_draft");if(owner!=null&&!id.isEmpty())receipts.discardDraft(owner,id,main,new RepositoryCallback<Boolean>(){public void onSuccess(Boolean value){}public void onError(Exception failure){/* Startup TTL cleanup retries abandoned drafts. */}});}
    public void loadReceipt() {
        if(actor==null||group==null||receiptWorking)return;
        receiptCheckedExpense=expenseId;saved.set("receipt_loaded_instance",true);
        if(Boolean.TRUE.equals(saved.get("receipt_remove")))return;
        int token=epoch;String draft=text("receipt_draft");receiptWorking=true;emit();
        if(!draft.isEmpty()){loadReceiptImage(draft,token);return;}
        if(expenseId==null){receiptWorking=false;emit();return;}
        receipts.findForExpense(actor,expenseId,main,new RepositoryCallback<Optional<ReceiptMetadata>>(){
            public void onSuccess(Optional<ReceiptMetadata> value){if(token!=epoch||group==null)return;receiptMetadata=value.orElse(null);if(value.isPresent())loadReceiptImage(value.get().id,token);else{receiptWorking=false;receiptPreview=null;receiptMessage=R.string.receipt_hint;emit();}}
            public void onError(Exception failure){receiptFailed(token);}
        });
    }
    private void loadReceiptImage(String id,int token){
        receipts.load(actor,id,main,new RepositoryCallback<ReceiptImage>(){
            public void onSuccess(ReceiptImage image){if(token!=epoch||group==null)return;capture.decode(image.bytes(),true,main,new RepositoryCallback<Bitmap>(){
                public void onSuccess(Bitmap bitmap){if(token!=epoch||group==null)return;receiptPreview=bitmap;receiptWorking=false;receiptMessage=R.string.receipt_review;emit();}
                public void onError(Exception failure){receiptFailed(token);}
            });}
            public void onError(Exception failure){receiptFailed(token);}
        });
    }
    private void receiptFailed(int token){if(token==epoch){receiptWorking=false;receiptPreview=null;receiptMessage=R.string.receipt_unavailable;emit();}}
    public void prepareCapture(){
        if(!sessionReady||actor==null||group==null||busy||receiptWorking||!editing())return;
        capture.cancel(text("capture_id"));clearCapture();
        int token=epoch,version=++captureVersion;receiptWorking=true;emit();
        capture.prepare(main,new RepositoryCallback<ReceiptCaptureService.Target>(){
            public void onSuccess(ReceiptCaptureService.Target target){if(token!=epoch||version!=captureVersion||group==null){capture.cancel(target.id);return;}saved.set("capture_id",target.id);saved.set("capture_uri",target.uri.toString());captureTarget=target.uri;receiptWorking=false;emit();}
            public void onError(Exception failure){if(token==epoch&&version==captureVersion){receiptWorking=false;receiptMessage=R.string.receipt_camera_unavailable;emit();}}
        });
    }
    public Uri consumeCapture(){if(captureTarget==null||Boolean.TRUE.equals(saved.get("capture_launched"))||!sessionReady)return null;saved.set("capture_launched",true);return captureTarget;}
    public void captureResult(boolean success){saved.set("capture_result",success);captureTarget=null;if(!success){captureVersion++;receiptWorking=false;capture.cancel(text("capture_id"));clearCapture();receiptMessage=R.string.receipt_cancelled;emit();}else processCapture();}
    private void processCapture(){
        if(!sessionReady||actor==null||group==null||receiptWorking||text("capture_id").isEmpty())return;
        String captureId=text("capture_id"),captureOwner=actor;int token=epoch,version=captureVersion;receiptWorking=true;emit();
        capture.process(captureId,main,new RepositoryCallback<ReceiptCaptureService.Scan>(){
            public void onSuccess(ReceiptCaptureService.Scan scan){if(token!=epoch||version!=captureVersion||group==null){scan.preview.recycle();return;}
                receipts.stage(actor,groupId,scan.jpeg,main,new RepositoryCallback<ReceiptMetadata>(){
                    public void onSuccess(ReceiptMetadata metadata){if(token!=epoch||version!=captureVersion||group==null){scan.preview.recycle();receipts.discardDraft(captureOwner,metadata.id,main,ignoreReceipt());return;}
                        releaseReceiptDraft(actor);saved.set("receipt_draft",metadata.id);saved.set("receipt_remove",false);receiptPreview=scan.preview;detectedPaise=scan.detection.amountPaise;receiptDetection=scan.detection;receiptAmountApplied=false;
                        receiptWorking=false;clearCapture();receiptMessage=scan.ocrFailed?R.string.receipt_ocr_failed:detectedPaise==null?(scan.detection.status==ReceiptTotalDetector.Status.AMBIGUOUS?R.string.receipt_ambiguous:R.string.receipt_no_total):R.string.receipt_detected;emit();}
                    public void onError(Exception failure){scanFailed(token,version);}
                });
            }
            public void onError(Exception failure){scanFailed(token,version);}
        });
    }
    private RepositoryCallback<Boolean> ignoreReceipt(){return new RepositoryCallback<Boolean>(){public void onSuccess(Boolean value){}public void onError(Exception failure){}};}
    private void clearCapture(){saved.remove("capture_id");saved.remove("capture_uri");saved.remove("capture_launched");saved.remove("capture_result");captureTarget=null;}
    private void scanFailed(int token,int version){if(token==epoch&&version==captureVersion){receiptWorking=false;clearCapture();receiptMessage=R.string.receipt_scan_failed;emit();}}
    public void cameraDenied(){receiptMessage=R.string.receipt_camera_denied;emit();}
    public void cameraUnavailable(){captureResult(false);receiptMessage=R.string.receipt_camera_unavailable;emit();}
    public void useDetectedAmount(){if(editing()&&!busy&&!receiptWorking&&detectedPaise!=null&&!receiptAmountApplied){text("amount",ExpenseAmounts.input(detectedPaise));receiptAmountApplied=true;emit();}}
    public void removeReceipt(){if(!editing()||busy||receiptWorking)return;releaseReceiptDraft(actor);saved.remove("receipt_draft");saved.set("receipt_remove",true);receiptPreview=null;detectedPaise=null;receiptDetection=null;receiptAmountApplied=false;receiptMessage=R.string.receipt_removed;emit();}
    public void consumeDone() { done=false; }
    @Override protected void onCleared() { releaseReceiptDraft(actor);capture.cancel(text("capture_id"));stop();epoch++; }

}
