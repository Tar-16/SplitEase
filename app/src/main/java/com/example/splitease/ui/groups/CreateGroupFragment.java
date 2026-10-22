package com.example.splitease.ui.groups;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.navigation.*;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.R;
import com.example.splitease.databinding.*;
import com.example.splitease.model.MemberIdentity;

public final class CreateGroupFragment extends GroupScreenFragment {
    private FragmentCreateGroupBinding binding;
    private boolean draftBound;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle state) {
        binding=FragmentCreateGroupBinding.inflate(inflater,parent,false); draftBound=false; return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,Bundle saved) {
        setup("create",null,binding.title);
        binding.back.setOnClickListener(v -> back());
        binding.groupName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { if(draftBound) model.name(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        binding.type.setOnCheckedChangeListener((group,id) -> { if(draftBound) model.trip(id==R.id.trip); });
        wireSearch(binding.memberSearch,false);
        binding.create.setOnClickListener(v -> model.create());
        model.state().observe(getViewLifecycleOwner(),state -> {
            if(state.ready && !draftBound) {
                binding.groupName.setText(model.name()); binding.type.check(model.trip()?R.id.trip:R.id.flat); draftBound=true;
            }
            status(state,binding.progress,binding.error); renderSearch(binding.memberSearch,state);
            binding.create.setEnabled(state.ready && !state.busy);
            binding.groupName.setEnabled(!state.busy); binding.flat.setEnabled(!state.busy); binding.trip.setEnabled(!state.busy);
            binding.selectedMembers.removeAllViews();
            for(MemberIdentity member:state.selected) {
                ItemMemberBinding row=ItemMemberBinding.inflate(getLayoutInflater(),binding.selectedMembers,false);
                row.name.setText(member.username); row.role.setVisibility(View.GONE);
                row.action.setText(R.string.member_remove); row.action.setEnabled(!state.busy);
                row.action.setOnClickListener(v -> model.remove(member.id)); binding.selectedMembers.addView(row.getRoot());
            }
            if(state.createdId!=null && isResumed()) {
                Bundle args=new Bundle(); args.putString("groupId",state.createdId); model.consumeNavigation();
                NavHostFragment.findNavController(this).navigate(R.id.group_detail,args,new NavOptions.Builder().setPopUpTo(R.id.create_group,true).build());
            }
        });
    }
    @Override public void onResume() { super.onResume();
        GroupsViewModel.State state=model.state().getValue();
        if(state!=null && state.createdId!=null) {
            Bundle args=new Bundle(); args.putString("groupId",state.createdId); model.consumeNavigation();
            NavHostFragment.findNavController(this).navigate(R.id.group_detail,args,new NavOptions.Builder().setPopUpTo(R.id.create_group,true).build());
        }
    }
    @Override public void onDestroyView() { super.onDestroyView(); binding=null; }
}
