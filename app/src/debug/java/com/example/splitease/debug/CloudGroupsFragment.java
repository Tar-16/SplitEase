package com.example.splitease.debug;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.*;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.R;
import com.example.splitease.auth.SessionState;
import com.example.splitease.databinding.FragmentCloudGroupsBinding;
import com.example.splitease.ui.auth.AuthViewModel;
import java.util.Objects;

/** Contains only the reused group graph; cloud financial/authenticated local destinations do not exist here. */
public final class CloudGroupsFragment extends Fragment {
    private FragmentCloudGroupsBinding binding;
    private String identity;private boolean exit;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup container,Bundle state){binding=FragmentCloudGroupsBinding.inflate(inflater,container,false);return binding.getRoot();}
    @Override public void onViewCreated(@NonNull View view,Bundle state){
        identity=state==null?null:state.getString("cloud_owner");
        if(getChildFragmentManager().findFragmentById(R.id.cloud_group_host)==null){NavHostFragment host=NavHostFragment.create(R.navigation.cloud_groups_graph);getChildFragmentManager().beginTransaction().replace(R.id.cloud_group_host,host).setPrimaryNavigationFragment(host).commitNow();}
        binding.cloudBack.setOnClickListener(v->NavHostFragment.findNavController(this).popBackStack());
        AuthViewModel model=new ViewModelProvider(this,new ViewModelProvider.Factory(){@NonNull @Override public <T extends ViewModel>T create(@NonNull Class<T> type){return type.cast(new AuthViewModel(requireActivity().getApplication(),CloudGroups.owner(requireContext()).auth));}}).get(AuthViewModel.class);
        model.session().observe(getViewLifecycleOwner(),session->{
            boolean ready=session.backend==SessionState.Backend.FIREBASE&&session.status==SessionState.Status.SIGNED_IN;
            binding.cloudGroupHost.setVisibility(ready?View.VISIBLE:View.INVISIBLE);binding.cloudGate.setVisibility(ready?View.GONE:View.VISIBLE);
            if(ready){if(!Objects.equals(identity,session.user.id)){NavHostFragment host=(NavHostFragment)getChildFragmentManager().findFragmentById(R.id.cloud_group_host);host.getNavController().setGraph(R.navigation.cloud_groups_graph);identity=session.user.id;}exit=false;}
            else{binding.cloudGate.setText(session.status==SessionState.Status.UNAVAILABLE?R.string.session_unavailable:R.string.restoring_session);if(session.status==SessionState.Status.SIGNED_OUT||session.status==SessionState.Status.PROFILE_REQUIRED)exit=true;}
            leaveIfNeeded();
        });
    }
    private void leaveIfNeeded(){if(exit&&isResumed()){exit=false;NavHostFragment.findNavController(this).popBackStack();}}
    @Override public void onResume(){super.onResume();leaveIfNeeded();}
    @Override public void onSaveInstanceState(@NonNull Bundle state){state.putString("cloud_owner",identity);super.onSaveInstanceState(state);}
    @Override public void onDestroyView(){binding=null;super.onDestroyView();}
}
