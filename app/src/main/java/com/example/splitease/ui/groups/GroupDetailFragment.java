package com.example.splitease.ui.groups;

import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import androidx.navigation.fragment.NavHostFragment;
import androidx.core.view.ViewCompat;
import com.google.android.material.tabs.TabLayout;
import com.example.splitease.ui.expenses.ExpenseUi;
import com.example.splitease.util.InrFormatter;
import com.example.splitease.domain.ExpenseAmounts;
import java.util.*;
import androidx.annotation.NonNull;
import com.example.splitease.R;
import com.example.splitease.databinding.*;
import com.example.splitease.model.*;

public final class GroupDetailFragment extends GroupScreenFragment {
    private FragmentGroupDetailBinding binding;
    private int selectedTab;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle state) {
        binding=FragmentGroupDetailBinding.inflate(inflater,parent,false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,Bundle saved) {
        setup("detail",requireArguments().getString("groupId"),binding.title);
        binding.back.setOnClickListener(v -> back()); binding.refresh.setOnClickListener(v -> model.retry());
        wireSearch(binding.memberSearch,true);
        if(saved!=null) selectedTab=saved.getInt("group_tab",0);
        binding.tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { selectedTab=tab.getPosition(); showTab(); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        binding.tabs.selectTab(binding.tabs.getTabAt(selectedTab));
        binding.settleUp.setOnClickListener(v -> {
            Bundle args=new Bundle(); args.putString("groupId",requireArguments().getString("groupId"));
            NavHostFragment.findNavController(this).navigate(R.id.settlements_screen,args);
        });
        binding.addExpense.setOnClickListener(v -> openExpense(null));
        if(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE) binding.groupAppBar.setExpanded(false,false);
        binding.leave.setOnClickListener(v -> confirm(getString(R.string.group_leave_confirm),model::leave));
        model.state().observe(getViewLifecycleOwner(),state -> {
            status(state,binding.progress,binding.error);
            binding.refresh.setVisibility(state.error==0?View.GONE:View.VISIBLE);
            binding.content.setVisibility(state.detail==null ? View.GONE : View.VISIBLE);
            binding.summary.setVisibility(state.detail==null?View.GONE:View.VISIBLE);
            binding.tabs.setVisibility(state.detail==null?View.GONE:View.VISIBLE);
            if(state.error!=0) binding.groupAppBar.setExpanded(true);
            binding.members.removeAllViews();
            if(state.detail!=null) {
                GroupDetails detail=state.detail; boolean admin=detail.group.adminId.equals(state.actorId);
                binding.title.setText(detail.group.name);
                binding.total.setText(getString(R.string.expense_total,InrFormatter.format(detail.totalExpenses)));
                binding.yourBalance.setText(getString(R.string.expense_your_balance,ExpenseUi.balance(requireContext(),detail.balances.getOrDefault(state.actorId,0L),true)));
                renderAccounting(detail,state.actorId);
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
            showTab();
            if(state.left && isResumed()) { model.consumeNavigation(); back(); }
        });
    }
    private void showTab() {
        binding.membersPanel.setVisibility(selectedTab==0?View.VISIBLE:View.GONE);
        binding.expensesPanel.setVisibility(selectedTab==1?View.VISIBLE:View.GONE);
        binding.balancesPanel.setVisibility(selectedTab==2?View.VISIBLE:View.GONE);
        GroupsViewModel.State state=model.state().getValue();
        binding.addExpense.setVisibility(selectedTab==1 && state!=null && state.detail!=null?View.VISIBLE:View.GONE);
    }
    private void openExpense(String id) {
        Bundle args=new Bundle(); args.putString("groupId",requireArguments().getString("groupId")); args.putString("expenseId",id);
        NavHostFragment.findNavController(this).navigate(R.id.expense_screen,args);
    }
    private void renderAccounting(GroupDetails detail,String actor) {
        binding.expenseList.removeAllViews(); binding.balanceList.removeAllViews();
        binding.expensesEmpty.setVisibility(detail.expenses.isEmpty()?View.VISIBLE:View.GONE);
        String previous=null;
        List<Expense> newest=new ArrayList<>(detail.expenses); Collections.reverse(newest);
        for(Expense expense:newest) {
            String date=ExpenseUi.date(expense.expenseDate);
            if(!date.equals(previous)) {
                TextView heading=new TextView(requireContext()); heading.setTextAppearance(R.style.TextAppearance_SplitEase_CardTitle);
                heading.setText(date); ViewCompat.setAccessibilityHeading(heading,true); binding.expenseList.addView(heading); previous=date;
            }
            ItemExpenseBinding row=ItemExpenseBinding.inflate(getLayoutInflater(),binding.expenseList,false);
            row.categoryIcon.setText(ExpenseUi.icon(expense.category)); row.categoryIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.title.setText(expense.title); row.payer.setText(getString(R.string.expense_card_payer,expense.category,ExpenseUi.name(requireContext(),detail,expense.payerId)));
            row.amount.setText(InrFormatter.format(expense.amountPaise));
            row.count.setText(getResources().getQuantityString(R.plurals.expense_people,expense.splits.size(),expense.splits.size()));
            row.share.setText(getString(R.string.expense_card_share,InrFormatter.format(ExpenseAmounts.share(expense,actor)),InrFormatter.format(ExpenseAmounts.impact(expense,actor))));
            row.open.setContentDescription(getString(R.string.expense_open_named,expense.title));
            row.open.setOnClickListener(v -> openExpense(expense.id)); binding.expenseList.addView(row.getRoot());
        }
        for(Map.Entry<String,String> identity:detail.names.entrySet()) {
            ItemMemberBinding row=ItemMemberBinding.inflate(getLayoutInflater(),binding.balanceList,false);
            row.name.setText(ExpenseUi.name(requireContext(),detail,identity.getKey()));
            row.role.setText(ExpenseUi.balance(requireContext(),detail.balances.getOrDefault(identity.getKey(),0L),identity.getKey().equals(actor)));
            row.action.setVisibility(View.GONE); binding.balanceList.addView(row.getRoot());
        }
    }
    @Override public void onSaveInstanceState(@NonNull Bundle state) { state.putInt("group_tab",selectedTab); super.onSaveInstanceState(state); }
    @Override public void onResume() { super.onResume();
        GroupsViewModel.State state=model.state().getValue();
        if(state!=null && state.left) { model.consumeNavigation(); back(); }
    }
    @Override public void onDestroyView() { super.onDestroyView(); binding=null; }
}
