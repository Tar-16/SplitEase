package com.example.splitease.debug;

import android.content.Context;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.R;
import com.example.splitease.data.firebase.FirestoreGroupRepository;
import com.example.splitease.ui.groups.GroupDependencies;
import com.google.firebase.firestore.FirebaseFirestore;

/** Graph-scoped injection. SQLite screens never receive this owner. */
public final class CloudGroups {
    private static GroupDependencies owner;
    private CloudGroups(){}
    public static synchronized GroupDependencies owner(Context context){
        if(owner==null){com.example.splitease.auth.AuthService auth=FirebaseAuthSandbox.service(context);owner=new GroupDependencies(auth,new FirestoreGroupRepository(FirebaseFirestore.getInstance(FirebaseAuthSandbox.sdk(context).getApp()),auth,FirebaseAuthSandbox.profiles(context)));}return owner;
    }
    public static GroupDependencies dependencies(Fragment fragment){return NavHostFragment.findNavController(fragment).getGraph().getId()==R.id.cloud_groups_graph?owner(fragment.requireContext()):null;}
}
