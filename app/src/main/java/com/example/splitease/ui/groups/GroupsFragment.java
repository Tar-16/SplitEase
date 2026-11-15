package com.example.splitease.ui.groups;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.R;
import com.example.splitease.databinding.*;
import com.example.splitease.model.*;
import com.example.splitease.ui.expenses.ExpenseUi;
import com.example.splitease.util.InrFormatter;
import java.text.DateFormat;
import java.util.*;

public final class GroupsFragment extends GroupScreenFragment {
    private FragmentGroupsBinding binding;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle state) {
        binding=FragmentGroupsBinding.inflate(inflater,parent,false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,Bundle saved) {
        setup("home",null,binding.title);
        binding.newGroup.setOnClickListener(v -> NavHostFragment.findNavController(this).navigate(R.id.create_group,null,com.example.splitease.ui.Transitions.page().build()));
        binding.refresh.setOnClickListener(v -> model.retry());
        model.state().observe(getViewLifecycleOwner(),state -> {
            status(state,binding.progress,binding.error);
            binding.newGroup.setEnabled(state.ready);
            binding.refresh.setVisibility(state.error==0 ? View.GONE : View.VISIBLE);
            binding.empty.setVisibility(state.ready && !state.loading && state.error==0 && state.groups.isEmpty() ? View.VISIBLE : View.GONE);
            binding.groupCount.setText(state.ready && !state.loading && state.error==0 ? String.valueOf(state.groups.size()) : getString(R.string.home_count_placeholder));
            renderTotals(state);
            binding.groupList.removeAllViews();
            for(GroupDetails detail:state.groups) {
                ItemGroupBinding row=ItemGroupBinding.inflate(getLayoutInflater(),binding.groupList,false);
                row.name.setText(detail.group.name);
                row.meta.setText(getResources().getQuantityString(R.plurals.group_meta,detail.members.size(),getString(detail.group.type==Group.Type.FLAT?R.string.type_flat:R.string.type_trip),detail.members.size()));
                StringJoiner initials=new StringJoiner(" · ");
                for(MemberIdentity member:detail.members) if(!member.username.isEmpty()) initials.add(member.username.substring(0,member.username.offsetByCodePoints(0,1)).toUpperCase(Locale.ROOT));
                row.initials.setText(getString(R.string.member_initials,initials.toString()));
                row.created.setText(getString(R.string.group_created,DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(detail.group.createdAt))));
                row.total.setVisibility(detail.accountingAvailable?View.VISIBLE:View.GONE);row.balance.setVisibility(detail.accountingAvailable?View.VISIBLE:View.GONE);
                if(detail.accountingAvailable)row.total.setText(getString(R.string.expense_total,InrFormatter.format(detail.totalExpenses)));
                if(detail.accountingAvailable)row.balance.setText(getString(R.string.expense_your_balance,ExpenseUi.balance(requireContext(),detail.balances.getOrDefault(state.actorId,0L),true)));
                row.recent.setVisibility(detail.expenses.isEmpty()?View.GONE:View.VISIBLE);
                if(!detail.expenses.isEmpty()) row.recent.setText(getString(R.string.expense_latest,ExpenseUi.date(detail.expenses.get(detail.expenses.size()-1).expenseDate)));
                row.open.setContentDescription(getString(R.string.group_open_named,detail.group.name));
                row.open.setOnClickListener(v -> open(detail.group.id));
                row.getRoot().setOnClickListener(v -> open(detail.group.id));
                binding.groupList.addView(row.getRoot());
            }
        });
    }
    /** Display-only sums of the signed-in member's own net balances; hidden when accounting is unavailable. */
    private void renderTotals(GroupsViewModel.State state) {
        boolean available=false; long owed=0,owing=0;
        try {
            for(GroupDetails detail:state.groups) {
                if(!detail.accountingAvailable) continue;
                available=true;
                long balance=detail.balances.getOrDefault(state.actorId,0L);
                if(balance>0) owed=Math.addExact(owed,balance); else owing=Math.addExact(owing,balance);
            }
        } catch(ArithmeticException overflow) { available=false; }
        binding.balanceTiles.setVisibility(available && state.error==0 ? View.VISIBLE : View.GONE);
        if(available) { binding.owedAmount.setText(InrFormatter.magnitude(owed)); binding.oweAmount.setText(InrFormatter.magnitude(owing)); }
    }
    @Override public void onDestroyView() { super.onDestroyView(); binding=null; }
}
