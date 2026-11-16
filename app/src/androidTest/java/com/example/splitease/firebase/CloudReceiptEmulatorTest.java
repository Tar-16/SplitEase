package com.example.splitease.firebase;

import android.content.Context;
import android.graphics.*;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.splitease.auth.*;
import com.example.splitease.data.DeviceCloudReceipts;
import com.example.splitease.data.firebase.*;
import com.example.splitease.model.*;
import com.example.splitease.repository.*;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static com.example.splitease.firebase.AuthEmulatorSupport.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class CloudReceiptEmulatorTest {
    private final Context context=ApplicationProvider.getApplicationContext();
    private final String suffix=UUID.randomUUID().toString().substring(0,8);

    private final class Client implements AutoCloseable {
        final FirebaseAuth sdk;
        final FirebaseFirestore db;
        final FirebaseAuthService auth;
        final FirestoreExpenseRepository expenses;
        final FirestoreGroupRepository groups;
        final String email, uid;
        Client(String tag) throws Exception {
            FirebaseOptions options=new FirebaseOptions.Builder().setProjectId(PROJECT).setApiKey("demo-api-key")
                    .setApplicationId("1:1234567890:android:0000000000000000").build();
            FirebaseApp app=FirebaseApp.initializeApp(context,options,"Receipt"+tag+suffix);
            sdk=FirebaseAuth.getInstance(app);
            sdk.useEmulator("127.0.0.1",9099);
            db=FirebaseFirestore.getInstance(app);
            db.setFirestoreSettings(new FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build());
            db.useEmulator("127.0.0.1",8080);
            email="receipt"+tag+suffix+"@example.test";
            Tasks.await(sdk.createUserWithEmailAndPassword(email,PASSWORD),12,TimeUnit.SECONDS);
            uid=sdk.getUid();
            FirestoreProfileRepository profiles=new FirestoreProfileRepository(db,sdk);
            await((RepositoryCallback<CloudProfile> cb)->profiles.createOwnProfile("Receipt"+tag+suffix,Runnable::run,cb));
            auth=new FirebaseAuthService(new ProfiledFirebaseAuthBackend(new FirebaseSdkAuthBackend(sdk),profiles));
            state(auth,SessionState.Status.SIGNED_IN);
            expenses=new FirestoreExpenseRepository(db,auth);
            groups=new FirestoreGroupRepository(db,auth,profiles);
        }
        public void close() { groups.close(); expenses.close(); auth.close(); sdk.signOut(); }
    }

    private Group group(Client client,String... members) throws Exception {
        Group draft=new Group(UUID.randomUUID().toString(),"Receipt test",Group.Type.FLAT,client.uid,0);
        return await(cb->client.groups.createGroup(client.uid,draft,Arrays.asList(members),Runnable::run,cb));
    }

    private Expense expense(Group group,String actor) {
        String id=UUID.randomUUID().toString();
        return new Expense(id,group.id,"Lunch",101,actor,actor,"Food",0,
                Collections.singletonList(new ExpenseSplit(id,actor,101)),0,"Test");
    }

    private Expense edit(Expense row) {
        return new Expense(row.id,row.groupId,"Edited",row.amountPaise,row.payerId,row.creatorId,row.category,
                row.expenseDate,row.splits,row.revision,row.description);
    }

    private static byte[] jpeg(int color) {
        Bitmap image=Bitmap.createBitmap(80,100,Bitmap.Config.ARGB_8888);
        image.eraseColor(color);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        assertTrue(image.compress(Bitmap.CompressFormat.JPEG,80,out));
        image.recycle();
        return out.toByteArray();
    }

    private ReceiptMetadata stage(DeviceCloudReceipts repo,String actor,String group,int color) throws Exception {
        return await(cb->repo.stage(actor,group,jpeg(color),Runnable::run,cb));
    }

    private Expense save(DeviceCloudReceipts repo,String actor,Expense row,ReceiptChange change) throws Exception {
        return await(cb->repo.saveExpense(actor,row,change,Runnable::run,cb));
    }

    private Optional<ReceiptMetadata> find(DeviceCloudReceipts repo,String actor,Expense row) throws Exception {
        return await(cb->repo.findForExpense(actor,row.id,Runnable::run,cb));
    }

    private ReceiptImage load(DeviceCloudReceipts repo,String actor,String id) throws Exception {
        return await(cb->repo.load(actor,id,Runnable::run,cb));
    }

    private void denied(Call<?> call) throws Exception {
        try { await(call); fail("Expected operation to fail"); }
        catch (ExecutionException expected) { assertNotNull(expected.getCause()); }
    }

    private File image(String id) {
        String namespace=UUID.nameUUIDFromBytes("firebase-demo-device-receipts-v1".getBytes(StandardCharsets.UTF_8)).toString();
        return new File(new File(context.getNoBackupFilesDir(),"receipts-"+namespace),id+".jpg");
    }

    @Test public void cloudLinksArePrivateDurableAndCleanedWithoutRemoteReceiptMetadata() throws Exception {
        Assume.assumeTrue("Explicit demo emulator required","true".equals(InstrumentationRegistry.getArguments().getString("receiptCloud")));
        requireEmulator();
        try(Client a=new Client("A");Client b=new Client("B")) {
            Group shared=group(a,b.uid), unrelated=group(a);
            Expense original;
            ReceiptMetadata first;
            try(DeviceCloudReceipts repo=new DeviceCloudReceipts(context,a.db,a.auth,a.expenses)) {
                first=stage(repo,a.uid,shared.id,Color.WHITE);
                Expense other=expense(unrelated,a.uid);
                denied((RepositoryCallback<Expense> cb)->repo.saveExpense(a.uid,other,ReceiptChange.replace(first.id),Runnable::run,cb));
                assertFalse(Tasks.await(a.db.collection("expenses").document(other.id).get(Source.SERVER),8,TimeUnit.SECONDS).exists());
                original=save(repo,a.uid,expense(shared,a.uid),ReceiptChange.replace(first.id));
                assertEquals(original.id,load(repo,a.uid,first.id).metadata.expenseId);
            }
            try(DeviceCloudReceipts reopened=new DeviceCloudReceipts(context,a.db,a.auth,a.expenses)) {
                assertEquals(first.id,find(reopened,a.uid,original).get().id);
                assertArrayEquals(jpeg(Color.WHITE),load(reopened,a.uid,first.id).bytes());
                Expense updated=save(reopened,a.uid,edit(original),ReceiptChange.keep());
                assertEquals(first.id,find(reopened,a.uid,updated).get().id);
                ReceiptMetadata replacement=stage(reopened,a.uid,shared.id,Color.LTGRAY);
                denied((RepositoryCallback<Expense> cb)->reopened.saveExpense(a.uid,edit(original),ReceiptChange.replace(replacement.id),Runnable::run,cb));
                assertEquals(first.id,find(reopened,a.uid,updated).get().id);
                Expense finalRow=save(reopened,a.uid,edit(updated),ReceiptChange.replace(replacement.id));
                assertFalse(image(first.id).exists());
                assertEquals(replacement.id,find(reopened,a.uid,finalRow).get().id);
                Map<String,Object> remote=Tasks.await(a.db.collection("expenses").document(finalRow.id).get(Source.SERVER),8,TimeUnit.SECONDS).getData();
                assertFalse(remote.toString().contains(replacement.id));
                assertFalse(remote.keySet().stream().anyMatch(key->key.toLowerCase(Locale.ROOT).contains("receipt")));

                Tasks.await(a.sdk.signInWithEmailAndPassword(b.email,PASSWORD),12,TimeUnit.SECONDS);
                awaitCloudUid(a.auth,b.uid);
                assertFalse(find(reopened,b.uid,finalRow).isPresent());
                denied((RepositoryCallback<ReceiptImage> cb)->reopened.load(b.uid,replacement.id,Runnable::run,cb));
                denied((RepositoryCallback<ReceiptImage> cb)->reopened.load(a.uid,replacement.id,Runnable::run,cb));
                Tasks.await(a.sdk.signInWithEmailAndPassword(a.email,PASSWORD),12,TimeUnit.SECONDS);
                awaitCloudUid(a.auth,a.uid);

                assertTrue(image(replacement.id).delete());
                denied((RepositoryCallback<ReceiptImage> cb)->reopened.load(a.uid,replacement.id,Runnable::run,cb));
                assertEquals(replacement.id,find(reopened,a.uid,finalRow).get().id);
                ReceiptMetadata repaired=stage(reopened,a.uid,shared.id,Color.WHITE);
                Expense attached=save(reopened,a.uid,edit(finalRow),ReceiptChange.replace(repaired.id));
                // Delete from another client, then verify the local index purges on a server-confirmed tombstone.
                await((RepositoryCallback<Boolean> cb)->b.expenses.deleteExpense(b.uid,attached,Runnable::run,cb));
                assertFalse(find(reopened,a.uid,attached).isPresent());
                assertFalse(image(repaired.id).exists());
            }
        }
    }

    private void awaitCloudUid(AuthService auth,String uid) throws Exception {
        CompletableFuture<Boolean> ready=new CompletableFuture<>();
        ObservableQuery.Subscription listener=auth.sessions().subscribe(Runnable::run,new ObservableQuery.Observer<SessionState>() {
            public void onChanged(SessionState s) { if(s.status==SessionState.Status.SIGNED_IN && uid.equals(s.user.id)) ready.complete(true); }
            public void onError(Exception error) { ready.completeExceptionally(error); }
        });
        try { ready.get(18,TimeUnit.SECONDS); }
        finally { listener.close(); }
    }
}
