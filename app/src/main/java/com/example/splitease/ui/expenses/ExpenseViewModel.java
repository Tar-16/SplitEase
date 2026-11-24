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
    /** One attached or staged bill photo. amountText is the user's editable, unvalidated input. */
    public static final class Bill {
        public final String id,amountText,note;
        public final boolean draft;
        public final Bitmap thumbnail;
        Bill(String id,boolean draft,String amountText,Bitmap thumbnail,String note){this.id=id;this.draft=draft;this.amountText=amountText;this.thumbnail=thumbnail;this.note=note;}
    }
    public static final class State {
        public final GroupDetails group;
        public final Expense expense;
        public final boolean loading,busy,editing,done,receiptLinkFailed;
        public final int error;
        public final List<Bill> bills;
        public final Long billsTotalPaise;
        public final int billsWithAmount;
        public final boolean canAddBill,billAmounts;
        public final String receiptDebug;
        public final int receiptMessage;
        public final Uri captureUri;
        public final boolean capturePending;
        State(ExpenseViewModel m) { group=m.group; expense=m.original; loading=m.loading; busy=m.busy||m.receiptWorking||!m.text("capture_id").isEmpty(); editing=m.editing(); done=m.done; receiptLinkFailed=m.receiptLinkFailed; error=m.error;
            receiptDebug=m.receiptDetection==null?null:m.receiptDetection.debugReport;bills=m.bills();billAmounts=!m.cloud;
            long total=0;int counted=0;Long sum=null;try{for(Bill bill:bills){Long amount=m.billAmount(bill.id);if(amount!=null){total=Math.addExact(total,amount);counted++;}}if(counted>0)sum=total;}catch(ArithmeticException overflow){sum=null;counted=0;}
            billsTotalPaise=sum;billsWithAmount=counted;canAddBill=m.cloud||bills.size()<ReceiptChange.MAX_PER_EXPENSE;
            receiptMessage=m.receiptMessage;captureUri=m.captureTarget;capturePending=!m.text("capture_id").isEmpty(); }
    }
    private final boolean cloud;
    private final AuthService auth;
    private final GroupRepository groups;
    private final ExpenseRepository expenses;
    private final SavedStateHandle saved;
    private final ReceiptStorageRepository receipts;
    private final ReceiptCaptureService capture;
    private final Map<String,Bitmap> thumbs=new HashMap<>();
    private final Map<String,String> notes=new HashMap<>();
    private final Set<String> failedThumbs=new HashSet<>();
    private List<ReceiptMetadata> linked;
    private boolean linkedLoaded;
    private int thumbPending;
    private ReceiptTotalDetector.Detection receiptDetection;
    private int receiptMessage=R.string.receipt_hint;
    private boolean receiptWorking,sessionReady;
    private Uri captureTarget;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Executor main=handler::post;
    private final MutableLiveData<State> state=new MutableLiveData<>();
    private ObservableQuery.Subscription sessionWatch,groupWatch;
    private String actor,groupId,expenseId;
    private GroupDetails group;
    private Expense original;
    private boolean loading=true,busy,done,receiptLinkFailed;
    private int epoch,error,captureVersion;
    public ExpenseViewModel(Application application,SavedStateHandle saved) {
        this(application,saved,null);
    }
    public ExpenseViewModel(Application application,SavedStateHandle saved,com.example.splitease.ui.groups.GroupDependencies dependencies){
        super(application);this.saved=saved;cloud=dependencies!=null;
        SplitEaseApplication app=(SplitEaseApplication)application;
        auth=cloud?dependencies.auth:app.auth();groups=cloud?dependencies.groups:app.groups();expenses=cloud?dependencies.expenses:app.expenses();
        receipts=cloud?dependencies.receipts:app.receipts();capture=app.receiptCapture();emit();
    }
    public boolean cloud(){return cloud;}
    public boolean receiptsAvailable(){return receipts!=null;}
    private Map<String,Long> split(long amount,List<String> ids){return cloud?Rules.equalSplitIdentities(amount,ids):Rules.equalSplit(amount,ids);}
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
    public Map<String,Long> preview() { return split(ExpenseAmounts.parse(text("amount")),selected()); }
    public void start() {
        if(sessionWatch!=null) return;
        sessionReady=false;
        sessionWatch=auth.sessions().subscribe(main,new ObservableQuery.Observer<SessionState>() {
            @Override public void onChanged(SessionState session) {
                String id=session.status==SessionState.Status.SIGNED_IN&&session.backend==(cloud?SessionState.Backend.FIREBASE:SessionState.Backend.LOCAL)?session.user.id:null;
                if(!Objects.equals(id,actor)) {
                    boolean restoringOwner=actor==null&&id!=null&&id.equals(saved.get("owner"));
                    if(!restoringOwner){releaseReceiptDraft(actor);capture.cancel(text("capture_id"));}
                    epoch++; busy=false; done=false; receiptLinkFailed=false; group=null; original=null;resetBills();receiptWorking=false;receiptDetection=null;captureTarget=null;receiptMessage=R.string.receipt_hint;
                    if(!restoringOwner){saved.remove("capture_id");saved.remove("capture_uri");saved.remove("capture_launched");saved.remove("capture_result");clearBillState();}
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
    public void refresh(){if(cloud){saved.set("initialized",false);saved.set("editing",false);}load();}
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
                if(expenseId!=null && original==null) { captureVersion++;capture.cancel(text("capture_id"));clearCapture();receiptWorking=false;resetBills();group=null; if(!busy && !done) error=R.string.expense_missing; emit(); return; }
                if(!Boolean.TRUE.equals(saved.get("initialized")) || !editing()) {
                    saved.set("revision",original==null?0L:original.revision);saved.set("description",original==null?"":original.description);
                    saved.set("title",original==null?"":original.title);
                    saved.set("amount",original==null?"":ExpenseAmounts.input(original.amountPaise));
                    saved.set("payer",original==null?actor:original.payerId);
                    saved.set("category",original==null?"Other":original.category);
                    saved.set("date",original==null?System.currentTimeMillis():original.expenseDate);
                    ArrayList<String> ids=new ArrayList<>();
                    if(original!=null) for(ExpenseSplit split:original.splits) ids.add(split.userId);
                    saved.set("participants",ids); saved.set("editing",original==null); saved.set("initialized",true);
                }
                if(!receiptWorking && receiptsNeedLoading()) loadReceipt();
                if(!text("capture_id").isEmpty() && saved.contains("capture_result")) processCapture();
                error=0; emit();
            }
            @Override public void onError(Exception failure) { if(token==epoch) { captureVersion++;capture.cancel(text("capture_id"));clearCapture();receiptWorking=false;group=null; original=null;resetBills(); loading=false; error=R.string.expense_load_error; emit(); } }
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
        saved.set("revision",original.revision);saved.set("description",original.description);
        saved.set("title",original.title); saved.set("amount",ExpenseAmounts.input(original.amountPaise)); saved.set("payer",original.payerId); saved.set("category",original.category); saved.set("date",original.expenseDate);
        ArrayList<String> ids=new ArrayList<>(); for(ExpenseSplit split:original.splits) ids.add(split.userId); saved.set("participants",ids); saved.set("initialized",true); emit(); } }
    public void save() {
        if(actor==null || group==null || busy || receiptWorking || !text("capture_id").isEmpty() || !editing()) return;
        final ReceiptChange change;
        try { change=receiptChange(); } catch(IllegalArgumentException | ArithmeticException failure) { error=R.string.bill_amount_invalid; emit(); return; }
        final Expense value;
        try {
            String title=Rules.text(text("title")); long amount=ExpenseAmounts.parse(text("amount"));
            if(!CATEGORIES.contains(text("category")) && (original==null || !original.category.equals(text("category")))) throw new IllegalArgumentException();
            if(!payerOptions().contains(text("payer")) || !participantOptions().containsAll(selected())) throw new IllegalArgumentException();
            String id=original==null?(cloud?text("draft_expense_id"):UUID.randomUUID().toString()):original.id;
            if(id.isEmpty()){id=UUID.randomUUID().toString();saved.set("draft_expense_id",id);}
            List<ExpenseSplit> splits=new ArrayList<>();
            for(Map.Entry<String,Long> share:split(amount,selected()).entrySet()) splits.add(new ExpenseSplit(id,share.getKey(),share.getValue()));
            Rules.date(date());
            value=new Expense(id,groupId,title,amount,text("payer"),original==null?actor:original.creatorId,text("category"),date(),splits,cloud?((Long)saved.get("revision")):0,cloud?text("description"):"");
        } catch(IllegalArgumentException | ArithmeticException failure) { error=R.string.expense_invalid; emit(); return; }
        int token=epoch; busy=true; error=0; emit();
        expenses.saveExpense(actor,value,change,main,new RepositoryCallback<Expense>() {
            @Override public void onSuccess(Expense expense) { if(token==epoch) { busy=false;clearBillState(); done=true; emit(); } }
            @Override public void onError(Exception failure) {
                if(token==epoch && failure instanceof ReceiptLinkException) {
                    busy=false; receiptLinkFailed=true; done=true; emit();
                } else failed(token);
            }
        });
    }
    /** Capture when the confirmation opens, so a later live update cannot authorize a newer deletion. */
    public Expense deletionTarget(){
        if(original==null)return null;
        return cloud?new Expense(original.id,original.groupId,original.title,original.amountPaise,original.payerId,original.creatorId,original.category,original.expenseDate,original.splits,((Long)saved.get("revision")),original.description):original;
    }
    public void delete() {delete(deletionTarget());}
    public void delete(Expense expected) {
        if(actor==null || original==null || expected==null || group==null || busy) return;
        int token=epoch; busy=true; error=0; emit();
        expenses.deleteExpense(actor,cloud?expected:original,main,new RepositoryCallback<Boolean>() {
            @Override public void onSuccess(Boolean value) { if(token==epoch) { busy=false; done=true; emit(); } }
            @Override public void onError(Exception failure) { failed(token); }
        });
    }
    private void failed(int token) { if(token==epoch) { busy=false; error=cloud?R.string.cloud_expense_save_error:R.string.expense_save_error; emit(); } }
    // ---- Bills (several receipt photos per expense) ----
    private ArrayList<String> savedList(String key){ArrayList<String> values=saved.get(key);return values==null?new ArrayList<>():new ArrayList<>(values);}
    /** Linked bills that are not marked for removal, then staged drafts, in attachment order. */
    private List<String> visibleIds(){
        List<String> ids=new ArrayList<>();List<String> removed=savedList("bill_removed");
        if(linked!=null)for(ReceiptMetadata metadata:linked)if(!removed.contains(metadata.id))ids.add(metadata.id);
        ids.addAll(savedList("bill_drafts"));return ids;
    }
    private String amountText(String id){
        String value=saved.get("bill_amt_"+id);if(value!=null)return value;
        if(linked!=null)for(ReceiptMetadata metadata:linked)if(metadata.id.equals(id)&&metadata.amountPaise!=null)return ExpenseAmounts.input(metadata.amountPaise);
        return "";
    }
    /** Null when blank; throws for invalid text. */
    private Long parseBill(String id){String text=amountText(id).trim();return text.isEmpty()?null:Long.valueOf(ExpenseAmounts.parse(text));}
    /** Null when blank or invalid, for display totals only; Save validates strictly. */
    private Long billAmount(String id){try{return parseBill(id);}catch(IllegalArgumentException invalid){return null;}}
    private List<Bill> bills(){
        List<Bill> result=new ArrayList<>();List<String> drafts=savedList("bill_drafts");
        for(String id:visibleIds())result.add(new Bill(id,drafts.contains(id),amountText(id),thumbs.get(id),notes.get(id)));
        return Collections.unmodifiableList(result);
    }
    public State snapshot(){return new State(this);}
    public void billAmount(String id,String text){if(editing()&&visibleIds().contains(id))saved.set("bill_amt_"+id,text);}
    /** Explicit user action only: the expense amount is never changed by scanning. */
    public boolean useBillsTotal(){
        State current=new State(this);
        if(!editing()||busy||receiptWorking||current.billsTotalPaise==null)return false;
        text("amount",ExpenseAmounts.input(current.billsTotalPaise));emit();return true;
    }
    private void resetBills(){thumbs.clear();notes.clear();failedThumbs.clear();linked=null;linkedLoaded=false;thumbPending=0;}
    private void clearBillState(){for(String key:new ArrayList<>(saved.keys()))if(key.startsWith("bill_"))saved.remove(key);}
    private ReceiptChange receiptChange(){
        if(receipts==null)return ReceiptChange.keep();
        List<String> drafts=savedList("bill_drafts"),removed=savedList("bill_removed");
        if(cloud){ // Device-local cloud receipts keep their one-bill contract and carry no amounts.
            if(!drafts.isEmpty())return ReceiptChange.replace(drafts.get(drafts.size()-1));
            return removed.isEmpty()?ReceiptChange.keep():ReceiptChange.remove();
        }
        List<ReceiptChange.Addition> additions=new ArrayList<>();for(String id:drafts)additions.add(new ReceiptChange.Addition(id,parseBill(id)));
        Map<String,Long> amounts=new LinkedHashMap<>();
        if(linked!=null)for(ReceiptMetadata metadata:linked)if(!removed.contains(metadata.id)){Long now=parseBill(metadata.id);if(!Objects.equals(now,metadata.amountPaise))amounts.put(metadata.id,now);}
        if(additions.isEmpty()&&removed.isEmpty()&&amounts.isEmpty())return ReceiptChange.keep();
        return ReceiptChange.edit(additions,removed,amounts);
    }
    private void releaseReceiptDraft(String owner){if(receipts==null||owner==null)return;for(String id:savedList("bill_drafts"))discard(owner,id);}
    private void discard(String owner,String id){receipts.discardDraft(owner,id,main,new RepositoryCallback<Boolean>(){public void onSuccess(Boolean value){}public void onError(Exception failure){/* Startup TTL cleanup retries abandoned drafts. */}});}
    private boolean receiptsNeedLoading(){
        if(receipts==null||actor==null||group==null)return false;
        if(!linkedLoaded&&expenseId!=null)return true;
        for(String id:visibleIds())if(!thumbs.containsKey(id)&&!failedThumbs.contains(id))return true;
        return false;
    }
    /** Also the Retry action: clears earlier thumbnail failures first when invoked by the user. */
    public void loadReceipt(){
        if(receipts==null||actor==null||group==null||receiptWorking)return;
        int token=epoch;
        if(!linkedLoaded&&expenseId!=null){
            receiptWorking=true;emit();
            receipts.findAllForExpense(actor,expenseId,main,new RepositoryCallback<List<ReceiptMetadata>>(){
                public void onSuccess(List<ReceiptMetadata> value){if(token!=epoch||group==null)return;linked=new ArrayList<>(value);linkedLoaded=true;
                    for(ReceiptMetadata metadata:linked)if(metadata.amountPaise!=null&&!saved.contains("bill_amt_"+metadata.id))saved.set("bill_amt_"+metadata.id,ExpenseAmounts.input(metadata.amountPaise));
                    receiptWorking=false;loadThumbnails(token);}
                public void onError(Exception failure){receiptFailed(token);}
            });
            return;
        }
        loadThumbnails(token);
    }
    public void retryReceipts(){failedThumbs.clear();loadReceipt();}
    private void loadThumbnails(int token){
        List<String> missing=new ArrayList<>();for(String id:visibleIds())if(!thumbs.containsKey(id)&&!failedThumbs.contains(id))missing.add(id);
        if(missing.isEmpty()){receiptWorking=false;if(!failedThumbs.isEmpty())receiptMessage=R.string.receipt_unavailable;else if(receiptMessage==R.string.receipt_unavailable)receiptMessage=visibleIds().isEmpty()?R.string.receipt_hint:R.string.receipt_review;emit();return;}
        receiptWorking=true;thumbPending=missing.size();emit();
        for(String id:missing)receipts.load(actor,id,main,new RepositoryCallback<ReceiptImage>(){
            public void onSuccess(ReceiptImage image){if(token!=epoch)return;capture.decode(image.bytes(),true,main,new RepositoryCallback<Bitmap>(){
                public void onSuccess(Bitmap bitmap){if(token!=epoch)return;thumbs.put(id,bitmap);thumbnailDone(token);}
                public void onError(Exception failure){if(token!=epoch)return;failedThumbs.add(id);thumbnailDone(token);}
            });}
            public void onError(Exception failure){if(token!=epoch)return;failedThumbs.add(id);thumbnailDone(token);}
        });
    }
    private void thumbnailDone(int token){
        if(token!=epoch||--thumbPending>0)return;
        thumbPending=0;receiptWorking=false;
        receiptMessage=!failedThumbs.isEmpty()?R.string.receipt_unavailable:visibleIds().isEmpty()?R.string.receipt_hint:R.string.receipt_review;emit();
    }
    private void receiptFailed(int token){if(token==epoch){receiptWorking=false;receiptMessage=R.string.receipt_unavailable;emit();}}
    public void prepareCapture(){
        if(receipts==null||!sessionReady||actor==null||group==null||busy||receiptWorking||!editing())return;
        if(!cloud&&visibleIds().size()>=ReceiptChange.MAX_PER_EXPENSE){receiptMessage=R.string.bill_limit;emit();return;}
        capture.cancel(text("capture_id"));clearCapture();
        int token=epoch,version=++captureVersion;receiptWorking=true;emit();
        capture.prepare(main,new RepositoryCallback<ReceiptCaptureService.Target>(){
            public void onSuccess(ReceiptCaptureService.Target target){if(token!=epoch||version!=captureVersion||group==null){capture.cancel(target.id);return;}saved.set("capture_id",target.id);saved.set("capture_uri",target.uri.toString());captureTarget=target.uri;receiptWorking=false;emit();}
            public void onError(Exception failure){if(token==epoch&&version==captureVersion){receiptWorking=false;receiptMessage=R.string.receipt_camera_unavailable;emit();}}
        });
    }
    public Uri consumeCapture(){if(captureTarget==null||Boolean.TRUE.equals(saved.get("capture_launched"))||!sessionReady)return null;saved.set("capture_launched",true);return captureTarget;}
    public void captureResult(boolean success){if(text("capture_id").isEmpty())return;saved.set("capture_result",success);captureTarget=null;if(!success){captureVersion++;receiptWorking=false;capture.cancel(text("capture_id"));clearCapture();receiptMessage=R.string.receipt_cancelled;emit();}else processCapture();}
    private void processCapture(){
        if(!sessionReady||actor==null||group==null||receiptWorking||text("capture_id").isEmpty())return;
        String captureId=text("capture_id"),captureOwner=actor;int token=epoch,version=captureVersion;receiptWorking=true;emit();
        capture.process(captureId,main,new RepositoryCallback<ReceiptCaptureService.Scan>(){
            public void onSuccess(ReceiptCaptureService.Scan scan){if(token!=epoch||version!=captureVersion||group==null){scan.preview.recycle();return;}
                receipts.stage(actor,groupId,scan.jpeg,main,new RepositoryCallback<ReceiptMetadata>(){
                    public void onSuccess(ReceiptMetadata metadata){if(token!=epoch||version!=captureVersion||group==null){scan.preview.recycle();receipts.discardDraft(captureOwner,metadata.id,main,ignoreReceipt());return;}
                        addDraft(metadata.id,scan);
                        receiptWorking=false;clearCapture();Long detectedPaise=scan.detection.amountPaise;
                        receiptMessage=scan.ocrFailed?R.string.receipt_ocr_failed:detectedPaise==null?(scan.detection.status==ReceiptTotalDetector.Status.AMBIGUOUS?R.string.receipt_ambiguous:R.string.receipt_no_total):R.string.receipt_detected;emit();}
                    public void onError(Exception failure){scanFailed(token,version);}
                });
            }
            public void onError(Exception failure){scanFailed(token,version);}
        });
    }
    private void addDraft(String id,ReceiptCaptureService.Scan scan){
        if(cloud)for(String existing:visibleIds())dropBill(existing); // The device-local cloud adapter stores one bill.
        ArrayList<String> drafts=savedList("bill_drafts");drafts.add(id);saved.set("bill_drafts",drafts);
        thumbs.put(id,scan.preview);receiptDetection=scan.detection;
        // Detected amounts only prefill this bill's editable field; they never change the expense amount.
        saved.set("bill_amt_"+id,scan.detection.amountPaise==null||cloud?"":ExpenseAmounts.input(scan.detection.amountPaise));
        if(scan.detection.amountPaise!=null&&!cloud)notes.put(id,getApplication().getString(scan.detection.reliability==ReceiptTotalDetector.Reliability.HIGH?R.string.receipt_evidence_high:R.string.receipt_evidence_review,scan.detection.supportingLabel));
    }
    private RepositoryCallback<Boolean> ignoreReceipt(){return new RepositoryCallback<Boolean>(){public void onSuccess(Boolean value){}public void onError(Exception failure){}};}
    private void clearCapture(){saved.remove("capture_id");saved.remove("capture_uri");saved.remove("capture_launched");saved.remove("capture_result");captureTarget=null;}
    private void scanFailed(int token,int version){if(token==epoch&&version==captureVersion){receiptWorking=false;clearCapture();receiptMessage=R.string.receipt_scan_failed;emit();}}
    public void cameraDenied(){receiptMessage=R.string.receipt_camera_denied;emit();}
    public void cameraUnavailable(){captureResult(false);receiptMessage=R.string.receipt_camera_unavailable;emit();}
    private void dropBill(String id){
        ArrayList<String> drafts=savedList("bill_drafts");
        if(drafts.remove(id)){saved.set("bill_drafts",drafts);if(actor!=null)discard(actor,id);}
        else{ArrayList<String> removed=savedList("bill_removed");if(!removed.contains(id)){removed.add(id);saved.set("bill_removed",removed);}}
        saved.remove("bill_amt_"+id);notes.remove(id);thumbs.remove(id);
    }
    public void removeBill(String id){if(!editing()||busy||receiptWorking||!visibleIds().contains(id))return;dropBill(id);receiptMessage=R.string.receipt_removed;emit();}
    public void consumeDone() { done=false; receiptLinkFailed=false; }
    @Override protected void onCleared() { releaseReceiptDraft(actor);capture.cancel(text("capture_id"));stop();epoch++;resetBills(); }

}
