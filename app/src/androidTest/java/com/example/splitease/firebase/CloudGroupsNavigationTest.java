package com.example.splitease.firebase;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.navigation.*;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.*;
import com.example.splitease.R;
import com.example.splitease.auth.*;
import com.example.splitease.data.firebase.*;
import com.example.splitease.debug.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.example.splitease.ui.MainActivity;
import com.google.android.gms.tasks.*;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.*;
import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static com.example.splitease.firebase.AuthEmulatorSupport.*;

@RunWith(AndroidJUnit4.class)
public class CloudGroupsNavigationTest {
 private interface Condition{boolean ready(MainActivity activity);}
 private void waitFor(ActivityScenario<MainActivity> screen,Condition condition)throws Exception{waitFor(screen,"cloud navigation",condition);}
 private void waitFor(ActivityScenario<MainActivity> screen,String expected,Condition condition)throws Exception{
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
  while(System.nanoTime()<end){AtomicBoolean ready=new AtomicBoolean();screen.onActivity(a->{try{ready.set(condition.ready(a));}catch(NullPointerException transitioningView){/* Navigation may have committed before the new view is attached. */}});if(ready.get())return;Thread.sleep(50);}
  AtomicReference<String> actual=new AtomicReference<>();screen.onActivity(a->{
   String value="destination="+destination(a);View view=current(a);
   if(view!=null&&destination(a)==R.id.expense_screen){
    value+=", form="+view.findViewById(R.id.form).getVisibility()+", progress="+view.findViewById(R.id.progress).getVisibility()+", error="+text(view,R.id.error)+", errorVisibility="+view.findViewById(R.id.error).getVisibility()+", amount="+text(view,R.id.expense_amount)+", deleteEnabled="+view.findViewById(R.id.delete).isEnabled();
    Fragment fragment=child(a).getChildFragmentManager().getPrimaryNavigationFragment();
    com.example.splitease.ui.expenses.ExpenseViewModel.State state=new androidx.lifecycle.ViewModelProvider(fragment).get(com.example.splitease.ui.expenses.ExpenseViewModel.class).state().getValue();
    if(state!=null)value+=", loading="+state.loading+", busy="+state.busy+", done="+state.done+", revision="+(state.expense==null?"missing":state.expense.revision);
   }
   actual.set(value);
  });
  SplitEaseApplication app=ApplicationProvider.getApplicationContext();
  try(FileOutputStream out=new FileOutputStream(new File(app.getCacheDir(),"m9e-ui-state-failure.txt"))){out.write(("Expected: "+expected+"\nActual: "+actual.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  screenshot(app,"m9e-ui-state-failure.png");fail("Expected "+expected+"; actual "+actual.get());
 }
 private NavController root(MainActivity a){return ((NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host)).getNavController();}
 private NavHostFragment child(MainActivity a){NavHostFragment main=(NavHostFragment)a.getSupportFragmentManager().findFragmentById(R.id.nav_host);Fragment outer=main.getChildFragmentManager().getPrimaryNavigationFragment();return outer instanceof CloudGroupsFragment?(NavHostFragment)outer.getChildFragmentManager().findFragmentById(R.id.cloud_group_host):null;}
 private int destination(MainActivity a){NavHostFragment host=child(a);return host==null||host.getNavController().getCurrentDestination()==null?0:host.getNavController().getCurrentDestination().getId();}
 private View current(MainActivity a){NavHostFragment host=child(a);Fragment screen=host==null?null:host.getChildFragmentManager().getPrimaryNavigationFragment();return screen==null?null:screen.getView();}
 private void set(View view,int id,String text){((EditText)view.findViewById(id)).setText(text);}
 private String text(View view,int id){return ((TextView)view.findViewById(id)).getText().toString();}
 private void login(FirebaseAuth sdk,String email)throws Exception{try{Tasks.await(sdk.signInWithEmailAndPassword(email,PASSWORD),20,TimeUnit.SECONDS);}catch(ExecutionException missing){Tasks.await(sdk.createUserWithEmailAndPassword(email,PASSWORD),20,TimeUnit.SECONDS);}}
 private String seedOther(String suffix)throws Exception{FirebaseOptions options=new FirebaseOptions.Builder().setProjectId(PROJECT).setApplicationId("1:1234567890:android:0000000000000000").setApiKey("demo-api-key").build();FirebaseApp named=FirebaseApp.initializeApp(ApplicationProvider.getApplicationContext(),options,"CloudGroupUi"+suffix);FirebaseAuth sdk=FirebaseAuth.getInstance(named);sdk.useEmulator("127.0.0.1",9099);FirebaseFirestore db=FirebaseFirestore.getInstance(named);db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build());db.useEmulator("127.0.0.1",8080);login(sdk,"groups-ui-"+suffix.toLowerCase(Locale.ROOT)+"@example.test");FirestoreProfileRepository profiles=new FirestoreProfileRepository(db,sdk);await((RepositoryCallback<CloudProfile> cb)->profiles.createOwnProfile("Ui_Group"+suffix,Runnable::run,cb));return sdk.getUid();}
 private void screenshot(SplitEaseApplication app,String file)throws Exception{InstrumentationRegistry.getInstrumentation().waitForIdleSync();Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(app.getCacheDir(),file))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}}
 @Test public void reusedCloudScreensCreateTransferSwitchAccountsAndExcludeLocalAccounting()throws Exception{
  Assume.assumeTrue("Explicit demo group emulator test","true".equals(InstrumentationRegistry.getArguments().getString("groupsEmulator")));requireEmulator();java.net.HttpURLConnection clear=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:8080/emulator/v1/projects/"+PROJECT+"/databases/(default)/documents").openConnection();clear.setRequestMethod("DELETE");clear.setConnectTimeout(5000);clear.setReadTimeout(5000);try{assertEquals(200,clear.getResponseCode());}finally{clear.disconnect();}SplitEaseApplication app=ApplicationProvider.getApplicationContext();await((RepositoryCallback<Boolean> cb)->app.auth().logout(Runnable::run,cb));
  String member=seedOther("Member"),third=seedOther("Third");FirebaseAuth sdk=FirebaseAuthSandbox.sdk(app);sdk.signOut();login(sdk,"groups-ui-owner@example.test");await((RepositoryCallback<CloudProfile> cb)->FirebaseAuthSandbox.profiles(app).createOwnProfile("Ui_GroupOwner",Runnable::run,cb));AuthService cloud=FirebaseAuthSandbox.service(app);state(cloud,SessionState.Status.SIGNED_IN);GroupRepository repository=CloudGroups.owner(app).groups;state(cloud,SessionState.Status.SIGNED_IN);String owner=sdk.getUid();
  Group privateInput=new Group(UUID.randomUUID().toString(),"Owner private group",Group.Type.FLAT,owner,0);await((RepositoryCallback<Group> cb)->repository.createGroup(owner,privateInput,Collections.emptyList(),Runnable::run,cb));AtomicReference<String> shared=new AtomicReference<>();boolean keep="true".equals(InstrumentationRegistry.getArguments().getString("keepCloudGroupFixture"));
  try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
   waitFor(screen,a->root(a).getCurrentDestination().getId()==R.id.login&&a.findViewById(R.id.open_auth_sandbox)!=null);screen.onActivity(a->a.findViewById(R.id.open_auth_sandbox).performClick());waitFor(screen,a->a.findViewById(R.id.cloud_groups_open)!=null&&a.findViewById(R.id.cloud_groups_open).getVisibility()==View.VISIBLE);screen.onActivity(a->a.findViewById(R.id.cloud_groups_open).performClick());
   waitFor(screen,a->destination(a)==R.id.groups&&current(a)!=null&&current(a).findViewById(R.id.new_group).isEnabled());screen.onActivity(a->current(a).findViewById(R.id.new_group).performClick());waitFor(screen,a->destination(a)==R.id.create_group&&current(a)!=null&&current(a).findViewById(R.id.group_name)!=null);
   screen.onActivity(a->{View v=current(a);set(v,R.id.group_name,"Cloud shared trip");v.findViewById(R.id.trip).performClick();set(v,R.id.query,"ui_groupmember");v.findViewById(R.id.search).performClick();});waitFor(screen,a->current(a).findViewById(R.id.add).getVisibility()==View.VISIBLE);screen.onActivity(a->{current(a).findViewById(R.id.add).performClick();current(a).findViewById(R.id.create).performClick();});
   waitFor(screen,a->destination(a)==R.id.group_detail&&current(a)!=null&&current(a).findViewById(R.id.content).getVisibility()==View.VISIBLE);screen.onActivity(a->{Fragment f=child(a).getChildFragmentManager().getPrimaryNavigationFragment();shared.set(f.requireArguments().getString("groupId"));assertEquals(View.GONE,current(a).findViewById(R.id.total).getVisibility());assertEquals(View.GONE,current(a).findViewById(R.id.your_balance).getVisibility());assertFalse(current(a).findViewById(R.id.leave).isEnabled());assertEquals(a.getString(R.string.cloud_leaving_unavailable),text(current(a),R.id.leave_reason));LinearLayout members=current(a).findViewById(R.id.members);assertEquals(2,members.getChildCount());boolean clicked=false;for(int i=0;i<members.getChildCount();i++){View row=members.getChildAt(i);if(text(row,R.id.name).equals("Ui_GroupMember")){assertTrue(row.findViewById(R.id.action).isEnabled());clicked=row.findViewById(R.id.action).performClick();}}assertTrue("Registered member transfer action must exist",clicked);});
   clickConfirmation();waitFor(screen,a->{View v=current(a);return v!=null&&v.findViewById(R.id.member_search).getVisibility()==View.GONE;});
   screen.recreate();waitFor(screen,a->destination(a)==R.id.group_detail&&current(a)!=null&&current(a).findViewById(R.id.content).getVisibility()==View.VISIBLE);
   screen.onActivity(a->{View v=current(a);((TabLayout)v.findViewById(R.id.tabs)).selectTab(((TabLayout)v.findViewById(R.id.tabs)).getTabAt(1));assertEquals(View.VISIBLE,v.findViewById(R.id.add_expense).getVisibility());assertEquals(a.getString(R.string.expenses_empty),text(v,R.id.expenses_empty));((TabLayout)v.findViewById(R.id.tabs)).selectTab(((TabLayout)v.findViewById(R.id.tabs)).getTabAt(2));assertEquals(View.GONE,v.findViewById(R.id.settle_up).getVisibility());try{child(a).getNavController().navigate(R.id.settlements_screen);fail("No settlement destination in cloud graph");}catch(IllegalArgumentException expected){}});
   screen.onActivity(a->{View v=current(a);((TabLayout)v.findViewById(R.id.tabs)).selectTab(((TabLayout)v.findViewById(R.id.tabs)).getTabAt(1));v.findViewById(R.id.add_expense).performClick();});
   waitFor(screen,a->destination(a)==R.id.expense_screen&&current(a)!=null&&current(a).findViewById(R.id.form).getVisibility()==View.VISIBLE);
   screen.onActivity(a->{View v=current(a);assertEquals(View.GONE,v.findViewById(R.id.scan_receipt).getVisibility());set(v,R.id.expense_title,"Cloud dinner");set(v,R.id.expense_description,"Only the emulator group");set(v,R.id.expense_amount,"1.01");LinearLayout choices=v.findViewById(R.id.participants);for(int i=0;i<choices.getChildCount();i++)((android.widget.CompoundButton)choices.getChildAt(i)).setChecked(true);});
   screen.recreate();waitFor(screen,a->destination(a)==R.id.expense_screen&&current(a)!=null&&text(current(a),R.id.expense_amount).equals("1.01"));
   screen.onActivity(a->current(a).findViewById(R.id.save).performClick());waitFor(screen,a->destination(a)==R.id.group_detail&&current(a)!=null&&((LinearLayout)current(a).findViewById(R.id.expense_list)).getChildCount()>1);
   screen.onActivity(a->{LinearLayout list=current(a).findViewById(R.id.expense_list);list.getChildAt(1).findViewById(R.id.open).performClick();});waitFor(screen,a->destination(a)==R.id.expense_screen&&current(a)!=null&&current(a).findViewById(R.id.form).getVisibility()==View.VISIBLE&&current(a).findViewById(R.id.progress).getVisibility()==View.GONE&&current(a).findViewById(R.id.edit).isShown());
   screen.onActivity(a->{View v=current(a);assertEquals("Cloud dinner",text(v,R.id.expense_title));assertEquals("Only the emulator group",text(v,R.id.expense_description));v.findViewById(R.id.edit).performClick();set(v,R.id.expense_amount,"2.03");v.findViewById(R.id.save).performClick();});waitFor(screen,a->destination(a)==R.id.group_detail&&current(a)!=null&&((LinearLayout)current(a).findViewById(R.id.expense_list)).getChildCount()>1);
   screen.onActivity(a->{LinearLayout list=current(a).findViewById(R.id.expense_list);list.getChildAt(1).findViewById(R.id.open).performClick();});waitFor(screen,a->destination(a)==R.id.expense_screen&&current(a)!=null&&text(current(a),R.id.expense_amount).equals("2.03"));screen.onActivity(a->current(a).findViewById(R.id.delete).performClick());
   FirebaseFirestore other=FirebaseFirestore.getInstance(FirebaseApp.getInstance("CloudGroupUiMember"));DocumentSnapshot before=Tasks.await(other.collection("expenses").whereEqualTo("groupId",shared.get()).get(Source.SERVER),20,TimeUnit.SECONDS).getDocuments().get(0);
   @SuppressWarnings("unchecked") List<String> splitIds=(List<String>)before.get("participants");Map<String,Long> newShares=com.example.splitease.domain.Rules.equalSplitIdentities(205,splitIds);Tasks.await(before.getReference().update("amountPaise",205L,"shares",newShares,"revision",before.getLong("revision")+1,"updatedAt",FieldValue.serverTimestamp()),20,TimeUnit.SECONDS);
   waitFor(screen,a->text(current(a),R.id.expense_amount).equals("2.05"));clickDeleteConfirmation();waitFor(screen,a->destination(a)==R.id.expense_screen&&current(a).findViewById(R.id.error).getVisibility()==View.VISIBLE);assertFalse(Tasks.await(before.getReference().get(Source.SERVER),20,TimeUnit.SECONDS).getBoolean("deleted"));
   screen.onActivity(a->current(a).findViewById(R.id.retry).performClick());
   waitFor(screen,"refreshed expense ready for deletion",a->destination(a)==R.id.expense_screen&&current(a)!=null&&current(a).findViewById(R.id.form).isShown()&&current(a).findViewById(R.id.progress).getVisibility()==View.GONE&&current(a).findViewById(R.id.error).getVisibility()==View.GONE&&current(a).findViewById(R.id.delete).isEnabled());
   screen.onActivity(a->current(a).findViewById(R.id.delete).performClick());clickDeleteConfirmation();
   waitFor(screen,"group detail with no expenses after confirmed deletion",a->destination(a)==R.id.group_detail&&current(a)!=null&&current(a).findViewById(R.id.content).isShown()&&((LinearLayout)current(a).findViewById(R.id.expense_list)).getChildCount()==0);
   screen.onActivity(a->a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));waitFor(screen,a->a.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE&&current(a)!=null&&current(a).findViewById(R.id.content).getVisibility()==View.VISIBLE);screen.onActivity(a->AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));waitFor(screen,a->(a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES&&current(a)!=null&&current(a).findViewById(R.id.content).getVisibility()==View.VISIBLE);screenshot(app,"cloud-groups-dark-landscape.png");
   screen.onActivity(a->a.getOnBackPressedDispatcher().onBackPressed());waitFor(screen,a->destination(a)==R.id.groups&&current(a)!=null);screen.onActivity(a->a.findViewById(R.id.cloud_back).performClick());waitFor(screen,a->root(a).getCurrentDestination().getId()==R.id.firebase_auth_sandbox&&a.findViewById(R.id.submit)!=null&&a.findViewById(R.id.submit).isEnabled());screen.onActivity(a->a.findViewById(R.id.submit).performClick());state(cloud,SessionState.Status.SIGNED_OUT);
   waitFor(screen,a->a.findViewById(R.id.switch_screen)!=null&&a.findViewById(R.id.switch_screen).getVisibility()==View.VISIBLE);screen.onActivity(a->{if(((TextView)a.findViewById(R.id.submit)).getText().toString().equals(a.getString(R.string.signup_action)))a.findViewById(R.id.switch_screen).performClick();set(a.getWindow().getDecorView(),R.id.email,"groups-ui-member@example.test");set(a.getWindow().getDecorView(),R.id.password,PASSWORD);a.findViewById(R.id.submit).performClick();});state(cloud,SessionState.Status.SIGNED_IN);waitFor(screen,a->a.findViewById(R.id.cloud_groups_open).getVisibility()==View.VISIBLE);screen.onActivity(a->a.findViewById(R.id.cloud_groups_open).performClick());
   waitFor(screen,a->destination(a)==R.id.groups&&current(a)!=null&&((LinearLayout)current(a).findViewById(R.id.group_list)).getChildCount()==1);screen.onActivity(a->{LinearLayout list=current(a).findViewById(R.id.group_list);assertEquals("Cloud shared trip",text(list.getChildAt(0),R.id.name));assertEquals(View.GONE,list.getChildAt(0).findViewById(R.id.balance).getVisibility());list.getChildAt(0).findViewById(R.id.open).performClick();});waitFor(screen,"switched admin detail ready",a->memberSearchReady(a,member));
   screen.onActivity(a->{set(current(a),R.id.query,"Ui_GroupThird");current(a).findViewById(R.id.search).performClick();});waitFor(screen,"exact member search completed",a->memberFound(a,third));screen.onActivity(a->current(a).findViewById(R.id.add).performClick());clickConfirmation();waitFor(screen,a->((LinearLayout)current(a).findViewById(R.id.members)).getChildCount()==3);
   assertEquals(member,state(cloud,SessionState.Status.SIGNED_IN).user.id);assertEquals(SessionState.Backend.LOCAL,state(app.auth(),SessionState.Status.SIGNED_OUT).backend);
   if(keep){try(FileOutputStream out=new FileOutputStream(new File(app.getCacheDir(),"cloud-group-fixture.json"))){out.write(new JSONObject().put("uid",member).put("groupId",shared.get()).put("pid",android.os.Process.myPid()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
   else{await((RepositoryCallback<Boolean> cb)->cloud.logout(Runnable::run,cb));waitFor(screen,a->root(a).getCurrentDestination().getId()==R.id.firebase_auth_sandbox);}
  }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));if(!keep)await((RepositoryCallback<Boolean> cb)->cloud.logout(Runnable::run,cb));}
 }
 /** Isolates the account switch and member-add path; no expense journey is executed. */
 @Test public void switchedAdminAddsMemberOnlyAfterDetailAndSearchAreReady()throws Exception{
  Assume.assumeTrue("Explicit demo group emulator test","true".equals(InstrumentationRegistry.getArguments().getString("groupsEmulator")));
  requireEmulator();
  java.net.HttpURLConnection clear=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:8080/emulator/v1/projects/"+PROJECT+"/databases/(default)/documents").openConnection();
  clear.setRequestMethod("DELETE");clear.setConnectTimeout(5000);clear.setReadTimeout(5000);try{assertEquals(200,clear.getResponseCode());}finally{clear.disconnect();}
  SplitEaseApplication app=ApplicationProvider.getApplicationContext();await((RepositoryCallback<Boolean> cb)->app.auth().logout(Runnable::run,cb));
  String member=seedOther("Member"),third=seedOther("Third");
  FirebaseAuth sdk=FirebaseAuthSandbox.sdk(app);sdk.signOut();login(sdk,"groups-ui-owner@example.test");
  await((RepositoryCallback<CloudProfile> cb)->FirebaseAuthSandbox.profiles(app).createOwnProfile("Ui_GroupOwner",Runnable::run,cb));
  AuthService auth=FirebaseAuthSandbox.service(app);String owner=state(auth,SessionState.Status.SIGNED_IN).user.id;
  GroupRepository repository=CloudGroups.owner(app).groups;state(auth,SessionState.Status.SIGNED_IN);
  Group group=await((RepositoryCallback<Group> cb)->repository.createGroup(owner,new Group(UUID.randomUUID().toString(),"Isolated switch",Group.Type.FLAT,owner,0),Collections.singletonList(member),Runnable::run,cb));
  await((RepositoryCallback<Boolean> cb)->repository.transferAdmin(owner,group.id,member,Runnable::run,cb));
  await((RepositoryCallback<Boolean> cb)->auth.logout(Runnable::run,cb));state(auth,SessionState.Status.SIGNED_OUT);
  await((RepositoryCallback<User> cb)->auth.login("groups-ui-member@example.test",PASSWORD.toCharArray(),Runnable::run,cb));assertEquals(member,state(auth,SessionState.Status.SIGNED_IN).user.id);
  try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
   waitFor(screen,a->root(a).getCurrentDestination().getId()==R.id.login&&a.findViewById(R.id.open_auth_sandbox)!=null);
   screen.onActivity(a->a.findViewById(R.id.open_auth_sandbox).performClick());
   waitFor(screen,a->a.findViewById(R.id.cloud_groups_open)!=null&&a.findViewById(R.id.cloud_groups_open).isShown());
   screen.onActivity(a->a.findViewById(R.id.cloud_groups_open).performClick());
   waitFor(screen,a->destination(a)==R.id.groups&&current(a)!=null&&((LinearLayout)current(a).findViewById(R.id.group_list)).getChildCount()==1);
   screen.onActivity(a->{
    ((LinearLayout)current(a).findViewById(R.id.group_list)).getChildAt(0).findViewById(R.id.open).performClick();
    child(a).getChildFragmentManager().executePendingTransactions();
    assertEquals(R.id.group_detail,destination(a));View view=current(a);
    com.example.splitease.ui.groups.GroupsViewModel.State initial=groupState(a);
    // Before the queued AuthService delivery, XML children satisfy the old waits,
    // but their parent is hidden and the ViewModel correctly rejects the actions.
    assertEquals(View.VISIBLE,view.findViewById(R.id.member_search).getVisibility());
    assertEquals(View.VISIBLE,view.findViewById(R.id.add).getVisibility());
    assertFalse(view.findViewById(R.id.content).isShown());assertFalse(initial.ready);assertNull(initial.found);
    set(view,R.id.query,"Ui_GroupThird");view.findViewById(R.id.search).performClick();view.findViewById(R.id.add).performClick();
    assertNull(groupState(a).found);assertFalse(groupState(a).searching);
    System.out.println("REPRODUCED: old child-visibility waits passed while content hidden, actor not ready, found null; premature search/add ignored");
   });
   waitFor(screen,"switched admin detail ready",a->memberSearchReady(a,member));
   screen.onActivity(a->{set(current(a),R.id.query,"Ui_GroupThird");current(a).findViewById(R.id.search).performClick();});
   waitFor(screen,"exact member search completed",a->memberFound(a,third));
   screen.onActivity(a->current(a).findViewById(R.id.add).performClick());clickConfirmation();
   waitFor(screen,"three members after confirmed addition",a->groupState(a).detail!=null&&groupState(a).detail.members.size()==3&&!groupState(a).busy);
   DocumentSnapshot committed=Tasks.await(FirebaseFirestore.getInstance(sdk.getApp()).collection("groups").document(group.id).get(Source.SERVER),20,TimeUnit.SECONDS);
   assertEquals(member,committed.getString("adminId"));assertEquals(owner,committed.getString("creatorId"));
   @SuppressWarnings("unchecked") List<String> ids=(List<String>)committed.get("memberIds");assertEquals(3,ids.size());assertTrue(ids.contains(third));
   assertEquals(SessionState.Backend.LOCAL,state(app.auth(),SessionState.Status.SIGNED_OUT).backend);
   System.out.println("VERIFIED: switched admin identity, exact username result, confirmation and three server-confirmed memberships");
  }finally{await((RepositoryCallback<Boolean> cb)->auth.logout(Runnable::run,cb));}
 }
 private com.example.splitease.ui.groups.GroupsViewModel.State groupState(MainActivity activity){
  Fragment fragment=child(activity).getChildFragmentManager().getPrimaryNavigationFragment();
  return new androidx.lifecycle.ViewModelProvider(fragment).get(com.example.splitease.ui.groups.GroupsViewModel.class).state().getValue();
 }
 private boolean memberSearchReady(MainActivity activity,String expectedAdmin){
  if(destination(activity)!=R.id.group_detail||current(activity)==null)return false;
  com.example.splitease.ui.groups.GroupsViewModel.State state=groupState(activity);
  return state!=null&&state.ready&&!state.loading&&!state.busy&&state.detail!=null&&expectedAdmin.equals(state.actorId)&&expectedAdmin.equals(state.detail.group.adminId)&&current(activity).findViewById(R.id.content).isShown()&&current(activity).findViewById(R.id.search).isEnabled();
 }
 private boolean memberFound(MainActivity activity,String expectedId){
  com.example.splitease.ui.groups.GroupsViewModel.State state=groupState(activity);
  return state!=null&&!state.searching&&state.error==0&&state.found!=null&&expectedId.equals(state.found.id)&&current(activity).findViewById(R.id.add).isShown()&&current(activity).findViewById(R.id.add).isEnabled();
 }
 private void clickDeleteConfirmation()throws Exception{clickDialog("Delete expense");}
 private void clickConfirmation()throws Exception{clickDialog("Confirm");}
 private void clickDialog(String label)throws Exception{
  android.app.UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
  android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;automation.setServiceInfo(info);
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
  while(System.nanoTime()<end){
   for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows()){
    android.view.accessibility.AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
    // The editor also has a "Delete expense" button. Only the dialog's positive
    // button confirms; matching text across windows can reopen the dialog instead.
    for(android.view.accessibility.AccessibilityNodeInfo button:root.findAccessibilityNodeInfosByViewId("android:id/button1"))if(button.isVisibleToUser()&&button.isClickable()&&label.contentEquals(button.getText())&&button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))return;
   }Thread.sleep(50);
  }
  screenshot(ApplicationProvider.getApplicationContext(),"m9e-dialog-failure.png");fail("Confirmation dialog missing: "+label);
 }
}
