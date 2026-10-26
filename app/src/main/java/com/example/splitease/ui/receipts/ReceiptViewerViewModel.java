package com.example.splitease.ui.receipts;
import android.app.Application;
import android.graphics.Bitmap;
import android.os.*;
import androidx.lifecycle.*;
import com.example.splitease.*;
import com.example.splitease.auth.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import java.util.*;
import java.util.concurrent.Executor;

public final class ReceiptViewerViewModel extends AndroidViewModel {
 public static final class State {public final Bitmap image;public final boolean loading,failed;State(ReceiptViewerViewModel m){image=m.image;loading=m.loading;failed=m.failed;}}
 private final SplitEaseApplication app;private final Executor main=new Handler(Looper.getMainLooper())::post;private final MutableLiveData<State> state=new MutableLiveData<>();
 private ObservableQuery.Subscription sessions,groupWatch;private String actor,receiptId;private Bitmap image;private boolean loading=true,failed;private int epoch,request;
 public ReceiptViewerViewModel(Application application){super(application);app=(SplitEaseApplication)application;emit();}
 public void configure(String id){receiptId=id;}
 public LiveData<State> state(){return state;}
 private void emit(){state.setValue(new State(this));}
 public void start(){if(sessions!=null)return;sessions=app.auth().sessions().subscribe(main,new ObservableQuery.Observer<SessionState>(){
  public void onChanged(SessionState value){String id=value.status==SessionState.Status.SIGNED_IN?value.user.id:null;if(!Objects.equals(actor,id)){epoch++;image=null;if(groupWatch!=null)groupWatch.close();groupWatch=null;actor=id;}load();}
  public void onError(Exception error){image=null;loading=false;failed=true;emit();}
 });}
 public void load(){if(actor==null){image=null;loading=false;emit();return;}int token=epoch,version=++request;loading=true;failed=false;emit();
  app.receipts().load(actor,receiptId,main,new RepositoryCallback<ReceiptImage>(){
   public void onSuccess(ReceiptImage receipt){if(token!=epoch||version!=request)return;app.receiptCapture().decode(receipt.bytes(),false,main,new RepositoryCallback<Bitmap>(){
    public void onSuccess(Bitmap bitmap){if(token!=epoch||version!=request)return;image=bitmap;loading=false;failed=false;emit();if(groupWatch==null&&sessions!=null)watchGroup(receipt.metadata.groupId);}
    public void onError(Exception error){fail(token,version);}
   });}
   public void onError(Exception error){fail(token,version);}
  });
 }
 private void watchGroup(String group){int token=epoch;groupWatch=app.groups().observeGroupDetails(actor,group).subscribe(main,new ObservableQuery.Observer<GroupDetails>(){
  public void onChanged(GroupDetails details){if(token==epoch)load();}
  public void onError(Exception error){if(token==epoch){request++;image=null;loading=false;failed=true;emit();}}
 });}
 private void fail(int token,int version){if(token==epoch&&version==request){image=null;loading=false;failed=true;emit();}}
 public void stop(){if(sessions!=null)sessions.close();sessions=null;if(groupWatch!=null)groupWatch.close();groupWatch=null;request++;}
 @Override protected void onCleared(){stop();epoch++;}
}
