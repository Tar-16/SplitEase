package com.example.splitease.ui.groups;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import com.example.splitease.R;
import com.example.splitease.databinding.*;
import com.example.splitease.model.*;

public final class GroupDetailFragment extends GroupScreenFragment {
    private FragmentGroupDetailBinding binding;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle state) {
        binding=FragmentGroupDetailBinding.inflate(inflater,parent,false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,Bundle saved) {
        setup("detail",requireArguments().getString("groupId"),binding.title);
        binding.back.setOnClickListener(v -> back()); binding.refresh.setOnClickListener(v -> model.retry());
        wireSearch(binding.memberSearch,true);
        binding.leave.setOnClickListener(v -> confirm(getString(R.string.group_leave_confirm),model::leave));
        model.state().observe(getViewLifecycleOwner(),state -> {
            status(state,binding.progress,binding.error);
            binding.refresh.setVisibility(state.error==0?View.GONE:View.VISIBLE);
            binding.content.setVisibility(state.detail==null ? View.GONE : View.VISIBLE);
            binding.members.removeAllViews();
            if(state.detail!=null) {
                GroupDetails detail=state.detail; boolean admin=detail.group.adminId.equals(state.actorId);
                binding.title.setText(detail.group.name);
                binding.meta.setText(getResources().getQuantityString(R.plurals.group_meta,detail.members.size(),getString(detail.group.type==Group.Type.FLAT?R.string.type_flat:R.string.type_trip),detail.members.size()));
                binding.memberSearch.getRoot().setVisibility(admin?View.VISIBLE:View.GONE);
                renderSearch(binding.memberSearch,state);
                for(MemberIdentity member:detail.members) {
                    ItemMemberBinding row=ItemMemberBinding.inflate(getLayoutInflater(),binding.members,false);
                    row.name.setText(member.username);
                    String role=getString(member.id.equals(detail.group.adminId)?R.string.member_admin:R.string.member_current);
                    if(member.id.equals(state.actorId)) role=getString(R.string.member_role_you,role);
                    row.role.setText(role); row.action.setText(R.string.member_transfer);
                    row.action.setVisibility(admin && !member.id.equals(state.actorId)?View.VISIBLE:View.GONE);
                    row.action.setEnabled(!state.busy);
                    row.action.setOnClickListener(v -> confirm(getString(R.string.group_transfer_confirm,member.username),() -> model.transfer(member.id)));
                    binding.members.addView(row.getRoot());
                }
                binding.leave.setEnabled(detail.canLeave && !state.busy);
                binding.leaveReason.setText(admin ? R.string.group_leave_admin : R.string.group_leave_balance);
            } else { binding.title.setText(R.string.tab_groups); binding.meta.setText(""); }
            if(state.left && isResumed()) { model.consumeNavigation(); back(); }
        });
    }
    @Override public void onResume() { super.onResume();
        GroupsViewModel.State state=model.state().getValue();
        if(state!=null && state.left) { model.consumeNavigation(); back(); }
    }
    @Override public void onDestroyView() { super.onDestroyView(); binding=null; }
}
