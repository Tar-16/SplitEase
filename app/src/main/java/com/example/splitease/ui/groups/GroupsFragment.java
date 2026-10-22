package com.example.splitease.ui.groups;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.R;
import com.example.splitease.databinding.*;
import com.example.splitease.model.*;
import java.text.DateFormat;
import java.util.*;

public final class GroupsFragment extends GroupScreenFragment {
    private FragmentGroupsBinding binding;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle state) {
        binding=FragmentGroupsBinding.inflate(inflater,parent,false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,Bundle saved) {
        setup("home",null,binding.title);
        binding.newGroup.setOnClickListener(v -> NavHostFragment.findNavController(this).navigate(R.id.create_group));
        binding.refresh.setOnClickListener(v -> model.retry());
        model.state().observe(getViewLifecycleOwner(),state -> {
            status(state,binding.progress,binding.error);
            binding.newGroup.setEnabled(state.ready);
            binding.refresh.setVisibility(state.error==0 ? View.GONE : View.VISIBLE);
            binding.empty.setVisibility(state.ready && !state.loading && state.error==0 && state.groups.isEmpty() ? View.VISIBLE : View.GONE);
            binding.groupList.removeAllViews();
            for(GroupDetails detail:state.groups) {
                ItemGroupBinding row=ItemGroupBinding.inflate(getLayoutInflater(),binding.groupList,false);
                row.name.setText(detail.group.name);
                row.meta.setText(getResources().getQuantityString(R.plurals.group_meta,detail.members.size(),getString(detail.group.type==Group.Type.FLAT?R.string.type_flat:R.string.type_trip),detail.members.size()));
                StringJoiner initials=new StringJoiner(" · ");
                for(MemberIdentity member:detail.members) if(!member.username.isEmpty()) initials.add(member.username.substring(0,member.username.offsetByCodePoints(0,1)).toUpperCase(Locale.ROOT));
                row.initials.setText(getString(R.string.member_initials,initials.toString()));
                row.created.setText(getString(R.string.group_created,DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(detail.group.createdAt))));
                row.open.setContentDescription(getString(R.string.group_open_named,detail.group.name));
                row.open.setOnClickListener(v -> open(detail.group.id));
                binding.groupList.addView(row.getRoot());
            }
        });
    }
    @Override public void onDestroyView() { super.onDestroyView(); binding=null; }
}
