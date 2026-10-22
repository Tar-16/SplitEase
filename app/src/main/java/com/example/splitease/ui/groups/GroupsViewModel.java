package com.example.splitease.ui.groups;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.*;
import com.example.splitease.R;
import com.example.splitease.SplitEaseApplication;
import com.example.splitease.auth.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Session-bound presentation. Only this layer supplies acting IDs; fragments supply user actions. */
public final class GroupsViewModel extends AndroidViewModel {
    public static final class State {
        public final List<GroupDetails> groups;
        public final List<MemberIdentity> selected;
        public final GroupDetails detail;
        public final MemberIdentity found;
        public final boolean ready, loading, busy, searching, left;
        public final int error;
        public final String actorId, createdId;
        State(GroupsViewModel m) {
            groups=m.groups; selected=Collections.unmodifiableList(new ArrayList<>(m.selected));
            detail=m.detail; found=m.found; ready=m.actorId!=null; loading=m.loading;
            busy=m.busy; searching=m.searching; left=m.left; error=m.error;
            actorId=m.actorId; createdId=m.createdId;
        }
    }
    private final AuthService auth;
    private final GroupRepository repository;
    private final SavedStateHandle saved;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Executor main=handler::post;
    private final MutableLiveData<State> state=new MutableLiveData<>();
    private ObservableQuery.Subscription sessionSubscription, dataSubscription;
    private String mode="home", groupId, actorId, createdId;
    private List<GroupDetails> groups=Collections.emptyList();
    private final List<MemberIdentity> selected=new ArrayList<>();
    private GroupDetails detail;
    private MemberIdentity found;
    private boolean loading=true, busy, searching, left;
    private int error, epoch, searchVersion;
    public GroupsViewModel(Application application, SavedStateHandle saved) {
        super(application); this.saved=saved;
        SplitEaseApplication app=(SplitEaseApplication)application;
        auth=app.auth(); repository=app.groups(); publish();
    }
    public LiveData<State> state() { return state; }
    public void configure(String mode,String groupId) { this.mode=mode; this.groupId=groupId; }
    public String name() { String value=saved.get("name"); return value==null ? "" : value; }
    public void name(String value) { saved.set("name",value); }
    public boolean trip() { return Boolean.TRUE.equals(saved.get("trip")); }
    public void trip(boolean value) { saved.set("trip",value); }
    private void publish() { state.setValue(new State(this)); }
    public void start() {
        if (sessionSubscription!=null) return;
        sessionSubscription=auth.sessions().subscribe(main,new ObservableQuery.Observer<SessionState>() {
            @Override public void onChanged(SessionState value) {
                String id=value.status==SessionState.Status.SIGNED_IN ? value.user.id : null;
                if (!Objects.equals(actorId,id)) {
                    epoch++; searchVersion++; busy=false; searching=false; found=null;
                    groups=Collections.emptyList(); detail=null; createdId=null; left=false; selected.clear();
                    String owner=saved.get("owner");
                    if (id!=null && id.equals(owner)) {
                        ArrayList<String> ids=saved.get("selected_ids"), names=saved.get("selected_names");
                        if (ids!=null && names!=null && ids.size()==names.size()) for(int i=0;i<ids.size();i++) selected.add(new MemberIdentity(ids.get(i),names.get(i)));
                    } else if (id!=null) {
                        saved.set("name",""); saved.set("trip",false); saveSelection();
                    }
                    if (id!=null) saved.set("owner",id);
                    actorId=id; error=0;
                }
                subscribeData();
            }
            @Override public void onError(Exception failure) { error=R.string.group_load_error; loading=false; publish(); }
        });
    }
    public void stop() {
        if(sessionSubscription!=null) sessionSubscription.close(); sessionSubscription=null;
        if(dataSubscription!=null) dataSubscription.close(); dataSubscription=null;
        searchVersion++; searching=false;
    }
    public void retry() { subscribeData(); }
    private void subscribeData() {
        if(dataSubscription!=null) dataSubscription.close(); dataSubscription=null;
        loading=actorId!=null && !mode.equals("create"); publish();
        if(actorId==null || mode.equals("create")) return;
        int token=epoch;
        if(mode.equals("home")) dataSubscription=repository.observeGroupSummaries(actorId).subscribe(main,new ObservableQuery.Observer<List<GroupDetails>>() {
            @Override public void onChanged(List<GroupDetails> value) { if(token==epoch) { groups=value; loading=false; error=0; publish(); } }
            @Override public void onError(Exception failure) { if(token==epoch) { groups=Collections.emptyList(); loading=false; error=R.string.group_load_error; publish(); } }
        });
        else dataSubscription=repository.observeGroupDetails(actorId,groupId).subscribe(main,new ObservableQuery.Observer<GroupDetails>() {
            @Override public void onChanged(GroupDetails value) { if(token==epoch) { detail=value; loading=false; error=0; publish(); } }
            @Override public void onError(Exception failure) { if(token==epoch) { detail=null; loading=false; if(!busy) error=R.string.group_unavailable; publish(); } }
        });
    }
    public void search(String username) {
        if(actorId==null || searching || busy) return;
        if(username.trim().isEmpty()) { error=R.string.username_required; publish(); return; }
        int token=epoch, request=++searchVersion; searching=true; found=null; error=0; publish();
        repository.findMemberByUsername(actorId,username,main,new RepositoryCallback<MemberIdentity>() {
            @Override public void onSuccess(MemberIdentity value) {
                if(token!=epoch || request!=searchVersion) return;
                searching=false;
                boolean duplicate=value.id.equals(actorId);
                for(MemberIdentity member : selected) duplicate|=member.id.equals(value.id);
                if(detail!=null) for(MemberIdentity member : detail.members) duplicate|=member.id.equals(value.id);
                if(duplicate) error=R.string.member_already_added; else found=value;
                publish();
            }
            @Override public void onError(Exception failure) {
                if(token!=epoch || request!=searchVersion) return;
                searching=false; error= failure instanceof NoSuchElementException ? R.string.member_not_found : R.string.group_search_error; publish();
            }
        });
    }
    public void selectFound() {
        if(found==null || busy || actorId==null) return;
        if(mode.equals("create")) { selected.add(found); found=null; saveSelection(); publish(); }
        else if(detail!=null && actorId.equals(detail.group.adminId)) {
            String id=found.id; begin(); repository.addMember(actorId,groupId,id,System.currentTimeMillis(),main,callback(false));
        }
    }
    public void remove(String id) { if(!busy) { selected.removeIf(member -> member.id.equals(id)); saveSelection(); publish(); } }
    private void saveSelection() {
        ArrayList<String> ids=new ArrayList<>(), names=new ArrayList<>();
        for(MemberIdentity member:selected) { ids.add(member.id); names.add(member.username); }
        saved.set("selected_ids",ids); saved.set("selected_names",names);
    }
    private void begin() { busy=true; error=0; publish(); }
    public void create() {
        if(actorId==null || busy) return;
        if(name().trim().isEmpty()) { error=R.string.group_name_required; publish(); return; }
        Group group=new Group(UUID.randomUUID().toString(),name().trim(),trip()?Group.Type.TRIP:Group.Type.FLAT,actorId,System.currentTimeMillis());
        List<String> ids=new ArrayList<>(); for(MemberIdentity member:selected) ids.add(member.id);
        int token=epoch; begin();
        repository.createGroup(actorId,group,ids,main,new RepositoryCallback<Group>() {
            @Override public void onSuccess(Group value) { if(token==epoch) { busy=false; createdId=value.id; publish(); } }
            @Override public void onError(Exception failure) { if(token==epoch) { busy=false; error=R.string.group_save_error; publish(); } }
        });
    }
    public void transfer(String memberId) {
        if(actorId==null || busy || detail==null || !actorId.equals(detail.group.adminId)) return;
        boolean current=false; for(MemberIdentity member:detail.members) current|=member.id.equals(memberId);
        if(!current || memberId.equals(actorId)) return;
        begin(); repository.transferAdmin(actorId,groupId,memberId,main,callback(false));
    }
    public void leave() {
        if(actorId==null || busy || detail==null || !detail.canLeave) return;
        begin(); repository.leaveGroup(actorId,groupId,System.currentTimeMillis(),main,callback(true));
    }
    private RepositoryCallback<Boolean> callback(boolean leaving) {
        int token=epoch;
        return new RepositoryCallback<Boolean>() {
            @Override public void onSuccess(Boolean value) { if(token==epoch) { busy=false; found=null; left=leaving; publish(); } }
            @Override public void onError(Exception failure) { if(token==epoch) { busy=false; error=R.string.group_save_error; publish(); } }
        };
    }
    public void consumeNavigation() { createdId=null; left=false; }
    @Override protected void onCleared() { stop(); epoch++; }
}
