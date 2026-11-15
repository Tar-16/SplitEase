package com.example.splitease.firebase;

import android.view.View;
import android.widget.LinearLayout;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import com.example.splitease.R;
import com.example.splitease.SplitEaseApplication;
import com.example.splitease.debug.FirebaseAuthSandbox;
import com.example.splitease.ui.MainActivity;
import java.util.concurrent.atomic.AtomicBoolean;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.auth.*;
import com.example.splitease.data.firebase.*;
import com.example.splitease.domain.RecurringSchedule;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.google.android.gms.tasks.*;
import com.google.firebase.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.example.splitease.firebase.AuthEmulatorSupport.*;

@RunWith(AndroidJUnit4.class)
public final class FirestoreRecurringEmulatorTest {
    private final List<Client> clients=new ArrayList<>();
    private final ExecutorService callbacks=Executors.newSingleThreadExecutor();
    private Client a,b,c;
    private Group group;
    private final int month=RecurringSchedule.month(System.currentTimeMillis());
    private final String suffix=UUID.randomUUID().toString().substring(0,8);

    private static <T>T task(Task<T> task)throws Exception{return Tasks.await(task,15,TimeUnit.SECONDS);}
    private final class Client implements AutoCloseable {
        final FirebaseApp app;
        final FirebaseAuth sdk;
        final FirebaseFirestore db;
        final FirebaseAuthService auth;
        final FirestoreGroupRepository groups;
        final FirestoreRecurringRepository bills;
        final String uid;
        Client(String label)throws Exception{
            FirebaseOptions options=new FirebaseOptions.Builder().setProjectId(PROJECT).setApiKey("demo-api-key")
                    .setApplicationId("1:1234567890:android:0000000000000000").build();
            app=FirebaseApp.initializeApp(ApplicationProvider.getApplicationContext(),options,"Recurring"+label+suffix);
            sdk=FirebaseAuth.getInstance(app);sdk.useEmulator("127.0.0.1",9099);
            db=FirebaseFirestore.getInstance(app);
            db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build());
            db.useEmulator("127.0.0.1",8080);
            task(sdk.createUserWithEmailAndPassword(label+suffix+"@example.test",PASSWORD));
            uid=sdk.getUid();
            FirestoreProfileRepository profiles=new FirestoreProfileRepository(db,sdk);
            await((RepositoryCallback<CloudProfile> cb)->profiles.createOwnProfile("Bill_"+label+suffix,callbacks,cb));
            auth=new FirebaseAuthService(new ProfiledFirebaseAuthBackend(new FirebaseSdkAuthBackend(sdk),profiles));
            state(auth,SessionState.Status.SIGNED_IN);
            groups=new FirestoreGroupRepository(db,auth,profiles);
            bills=new FirestoreRecurringRepository(db,auth,groups);
        }
        DocumentReference ref(String id){return db.collection("recurringBills").document(id);}
        public void close(){
            bills.close();groups.close();auth.close();sdk.signOut();
            // Firebase Auth retains emulator registrations by API key until process exit.
            // Deleting one named app makes the later fixture's useEmulator fail.
        }
    }

    @Before public void setup()throws Exception{
        Assume.assumeTrue("Explicit demo emulators required","true".equals(InstrumentationRegistry.getArguments().getString("recurringEmulator")));
        requireEmulator();
        a=client("A");b=client("B");c=client("C");
        Group value=new Group(UUID.randomUUID().toString(),"Bill test",Group.Type.FLAT,a.uid,0);
        group=await((RepositoryCallback<Group> cb)->a.groups.createGroup(a.uid,value,Collections.singletonList(b.uid),callbacks,cb));
    }
    private Client client(String id)throws Exception{Client value=new Client(id);clients.add(value);return value;}
    @After public void finish(){for(Client client:clients)client.close();callbacks.shutdown();}

    private RecurringBill input(){return new RecurringBill(UUID.randomUUID().toString(),group.id,"Internet",101,31,Arrays.asList(a.uid,b.uid),a.uid,3,true);}
    private RecurringBill edit(RecurringBill old,long amount,boolean active){
        return new RecurringBill(old.id,old.groupId,old.title,amount,20,old.participantIds,b.uid,3,active,old.revision);
    }
    private RecurringBill save(Client who,RecurringBill value)throws Exception{
        return await(cb->who.bills.saveBill(who.uid,value,callbacks,cb));
    }
    private RecurringBillCycle cycle(Client client,String id)throws Exception{
        return await(cb->client.bills.createCycle(client.uid,id,month/12,month%12+1,callbacks,cb));
    }
    private void status(Client client,String cycle,String uid,boolean paid)throws Exception{
        assertTrue(await((RepositoryCallback<Boolean> cb)->client.bills.setContributionPaid(client.uid,cycle,uid,paid,callbacks,cb)));
    }
    private void deny(Task<?> write)throws Exception{
        try{task(write);fail("Expected Rules denial");}
        catch(ExecutionException error){
            assertTrue(error.getCause() instanceof FirebaseFirestoreException);
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED,((FirebaseFirestoreException)error.getCause()).getCode());
        }
    }
    private final class Stream implements AutoCloseable {
        final BlockingQueue<RecurringSnapshot> values=new LinkedBlockingQueue<>();
        final CompletableFuture<Exception> error=new CompletableFuture<>();
        final ObservableQuery.Subscription sub;
        Stream(Client who,String bill){
            sub=who.bills.observeRecurring(who.uid,bill).subscribe(callbacks,new ObservableQuery.Observer<RecurringSnapshot>(){
                public void onChanged(RecurringSnapshot value){values.add(value);}
                public void onError(Exception failure){error.complete(failure);}
            });
        }
        RecurringSnapshot until(Predicate<RecurringSnapshot> match)throws Exception{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(18);
            while(System.nanoTime()<deadline){
                if(error.isDone())throw new AssertionError("Recurring observation failed",error.get());
                RecurringSnapshot value=values.poll(500,TimeUnit.MILLISECONDS);
                if(value!=null&&match.test(value))return value;
            }
            throw new AssertionError("Expected recurring snapshot was not confirmed");
        }
        public void close(){sub.close();}
    }

    @Test public void concurrentGenerationStatusAndTemplateEditsPreserveOneHistoricalSnapshot()throws Exception{
        Map<String,Object> before=task(a.db.collection("groups").document(group.id).get(Source.SERVER)).getData();
        RecurringBill bill=save(a,input());
        try(Stream stream=new Stream(b,bill.id)){
            stream.until(s->s.cycles.containsKey(bill.id)&&s.cycles.get(bill.id).isEmpty());
            ExecutorService threads=Executors.newFixedThreadPool(2);
            RecurringBillCycle row;
            try{
                Future<RecurringBillCycle> first=threads.submit(()->cycle(a,bill.id)),second=threads.submit(()->cycle(b,bill.id));
                row=first.get(30,TimeUnit.SECONDS);
                assertEquals(row.id,second.get(30,TimeUnit.SECONDS).id);
                assertEquals(2,row.contributions.size());
                assertEquals(51,row.contributions.get(0).sharePaise);
                assertEquals(50,row.contributions.get(1).sharePaise);
                assertTrue(row.contributions.stream().noneMatch(x->x.paid));
                assertEquals(RecurringSchedule.due(month,31),row.dueDate);
                Future<?> one=threads.submit(()->{status(a,row.id,b.uid,true);return null;});
                Future<?> two=threads.submit(()->{status(b,row.id,a.uid,true);return null;});
                one.get(30,TimeUnit.SECONDS);two.get(30,TimeUnit.SECONDS);
            }finally{threads.shutdownNow();}
            stream.until(s->s.cycles.getOrDefault(bill.id,Collections.emptyList()).stream()
                    .anyMatch(x->x.contributions.stream().allMatch(y->y.paid)));
            status(b,row.id,a.uid,false);
            RecurringBill changed=save(b,edit(bill,200,false));
            assertEquals(2,changed.revision);
            try{save(a,edit(bill,333,true));fail("Stale template edit");}catch(ExecutionException expected){/* expected */}
            save(a,edit(changed,300,true));
            RecurringBillCycle historical=cycle(a,bill.id);
            assertEquals(101,historical.amountPaise);
            assertEquals(a.uid,historical.handlerId);
            assertEquals(row.dueDate,historical.dueDate);
            assertEquals(1,historical.contributions.stream().filter(x->x.paid).count());
            assertEquals(1,task(a.ref(bill.id).collection("cycles").get(Source.SERVER)).size());
            assertEquals(month+1,task(a.ref(bill.id).get(Source.SERVER)).getLong("nextMonth").intValue());
        }
        assertEquals(before,task(a.db.collection("groups").document(group.id).get(Source.SERVER)).getData());
        assertTrue(task(a.db.collection("expenses").whereEqualTo("groupId",group.id).get(Source.SERVER)).isEmpty());
        assertTrue(task(a.db.collection("settlements").whereEqualTo("groupId",group.id).get(Source.SERVER)).isEmpty());
    }

    @Test public void rulesPairCycleAndCursorAndLimitHistoricalChangesToOneStatus()throws Exception{
        RecurringBill bill=save(a,input());
        DocumentReference ref=a.ref(bill.id),row=ref.collection("cycles").document(Integer.toString(month));
        List<String> ids=new ArrayList<>(bill.participantIds);Collections.sort(ids);
        Map<String,Object> fields=new HashMap<>();
        fields.put("amountPaise",101L);fields.put("dueDay",31L);fields.put("handlerId",a.uid);
        fields.put("participants",ids);fields.put("month",month);fields.put("paid",Collections.emptyList());
        deny(row.set(fields)); // interrupted/unpaired creation leaves neither half committed
        deny(ref.update("nextMonth",month+1));
        deny(c.ref(bill.id).get(Source.SERVER));
        deny(c.ref(bill.id).collection("cycles").get(Source.SERVER));
        Map<String,Object> bad=new HashMap<>(fields);bad.put("paid",Collections.singletonList(b.uid));
        WriteBatch batch=a.db.batch();batch.update(ref,"nextMonth",month+1);batch.set(row,bad);deny(batch.commit());
        assertFalse(task(row.get(Source.SERVER)).exists());
        assertEquals(month,task(ref.get(Source.SERVER)).getLong("nextMonth").longValue());
        RecurringBillCycle generated=cycle(b,bill.id);
        deny(row.update("handlerId",b.uid));deny(row.update("amountPaise",900L));
        deny(row.update("paid",ids));deny(row.update("paid",Collections.singletonList(c.uid)));
        deny(row.delete());deny(ref.delete());
        deny(ref.update("revision",2L,"participants",Collections.singletonList(c.uid),"shares",Collections.singletonMap(c.uid,101L)));
        deny(c.ref(bill.id).collection("cycles").document(Integer.toString(month)).update("paid",Collections.singletonList(a.uid)));
        status(b,generated.id,a.uid,true);
        assertTrue(task(row.get(Source.SERVER)).get("paid") instanceof List);
        try(Stream stream=new Stream(a,bill.id)){
            stream.until(s->s.cycles.containsKey(bill.id));
            await((RepositoryCallback<Boolean> cb)->a.auth.logout(callbacks,cb));
            assertNotNull(stream.error.get(15,TimeUnit.SECONDS));
        }
        try(Stream outsider=new Stream(c,null)){assertTrue(outsider.until(s->true).bills.isEmpty());}
    }
    private void visible(ActivityScenario<MainActivity> scenario,Predicate<MainActivity> predicate)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){
            AtomicBoolean matched=new AtomicBoolean();
            scenario.onActivity(activity->matched.set(predicate.test(activity)));
            if(matched.get())return;
            Thread.sleep(75);
        }
        fail("Cloud recurring screen did not reach expected state");
    }
    private View screen(MainActivity activity,int destination){
        NavHostFragment main=(NavHostFragment)activity.getSupportFragmentManager().findFragmentById(R.id.nav_host);
        Fragment outer=main.getChildFragmentManager().getPrimaryNavigationFragment();
        if(outer==null)return null;
        NavHostFragment host=(NavHostFragment)outer.getChildFragmentManager().findFragmentById(R.id.cloud_group_host);
        if(host==null||host.getNavController().getCurrentDestination()==null||
                host.getNavController().getCurrentDestination().getId()!=destination)return null;
        Fragment fragment=host.getChildFragmentManager().getPrimaryNavigationFragment();
        return fragment==null?null:fragment.getView();
    }
    @Test public void debugNavigationLoadsConfirmedBillAndRestoresWithoutDeviceReminders()throws Exception {
        RecurringBill bill=save(a,input());cycle(b,bill.id);
        SplitEaseApplication app=ApplicationProvider.getApplicationContext();
        await((RepositoryCallback<Boolean> cb)->app.auth().logout(callbacks,cb));
        FirebaseAuth sdk=FirebaseAuthSandbox.sdk(app);
        sdk.signOut();
        task(sdk.signInWithEmailAndPassword(a.sdk.getCurrentUser().getEmail(),PASSWORD));
        AuthService auth=FirebaseAuthSandbox.service(app);
        state(auth,SessionState.Status.SIGNED_IN);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            visible(scenario,v->v.findViewById(R.id.open_auth_sandbox)!=null);
            scenario.onActivity(v->v.findViewById(R.id.open_auth_sandbox).performClick());
            visible(scenario,v->{View button=v.findViewById(R.id.cloud_groups_open);return button!=null&&button.isShown();});
            scenario.onActivity(v->v.findViewById(R.id.cloud_groups_open).performClick());
            visible(scenario,v->{View button=v.findViewById(R.id.cloud_recurring);return button!=null&&button.isShown();});
            scenario.onActivity(v->v.findViewById(R.id.cloud_recurring).performClick());
            visible(scenario,v->{View page=screen(v,R.id.recurring);return page!=null&&((LinearLayout)page.findViewById(R.id.bills)).getChildCount()==1;});
            scenario.onActivity(v->{
                View page=screen(v,R.id.recurring);
                assertEquals(View.GONE,page.findViewById(R.id.notifications).getVisibility());
                ((LinearLayout)page.findViewById(R.id.bills)).getChildAt(0).findViewById(R.id.action).performClick();
            });
            Predicate<MainActivity> detail=v->{View page=screen(v,R.id.bill_screen);return page!=null&&((LinearLayout)page.findViewById(R.id.current)).getChildCount()>0;};
            visible(scenario,detail);
            scenario.recreate();
            visible(scenario,detail);
        }finally{await((RepositoryCallback<Boolean> cb)->auth.logout(callbacks,cb));}
    }

}
