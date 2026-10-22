package com.example.splitease.ui.settlements;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.R;
import com.example.splitease.databinding.*;
import com.example.splitease.model.*;
import com.example.splitease.ui.expenses.ExpenseUi;
import com.example.splitease.util.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

public final class SettlementsFragment extends Fragment {
    private FragmentSettlementsBinding binding;
    private SettlementsViewModel model;
    private AlertDialog dialog;
    private DatePickerDialog dateDialog;
    private boolean rendering;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle state) {
        binding=FragmentSettlementsBinding.inflate(inflater,parent,false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,Bundle state) {
        model=new ViewModelProvider(this).get(SettlementsViewModel.class); model.configure(requireArguments().getString("groupId"));
        ViewCompat.setAccessibilityHeading(binding.title,true);
        binding.error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        binding.back.setOnClickListener(v -> NavHostFragment.findNavController(this).popBackStack());
        binding.refresh.setOnClickListener(v -> model.refresh());
        binding.amount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { if(!rendering) model.amount(s.toString()); }
            @Override public void afterTextChanged(Editable e) {}
        });
        binding.date.setOnClickListener(v -> {
            Calendar calendar=Calendar.getInstance(TimeZone.getTimeZone("UTC")); calendar.setTimeInMillis(model.date());
            dateDialog=new DatePickerDialog(requireContext(),(picker,y,m,d) -> {
                Calendar selected=Calendar.getInstance(TimeZone.getTimeZone("UTC")); selected.clear(); selected.set(y,m,d); model.date(selected.getTimeInMillis());
            },calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH),calendar.get(Calendar.DAY_OF_MONTH)); dateDialog.getDatePicker().setMinDate(0); dateDialog.show();
        });
        binding.record.setOnClickListener(v -> confirm(R.string.settlement_confirm,R.string.group_confirm,() -> model.save(false)));
        binding.pay.setOnClickListener(v -> {
            try { if(!UpiPayment.launch(requireContext(),model.paymentUri(),getString(R.string.upi_chooser))) model.paymentUnavailable(); }
            catch(IllegalArgumentException | ArithmeticException failure) { model.paymentUnavailable(); }
        });
        model.state().observe(getViewLifecycleOwner(),this::render);
    }
    private void render(SettlementsViewModel.State state) {
        rendering=true;
        binding.progress.setVisibility(state.busy || state.loading?View.VISIBLE:View.GONE);
        binding.error.setVisibility(state.error==0?View.GONE:View.VISIBLE); if(state.error!=0) binding.error.setText(state.error);
        binding.content.setVisibility(state.details==null?View.GONE:View.VISIBLE);
        if(state.details!=null) {
            SettlementDetails details=state.details;
            binding.form.setVisibility(state.selected?View.VISIBLE:View.GONE);
            if(state.selected) {
                binding.parties.setText(parties(details,model.text("payer"),model.text("payee")));
                long limit=model.limit(); binding.limit.setText(getString(R.string.settlement_limit,InrFormatter.format(limit)));
                binding.kind.setText(state.editing?getString(R.string.settlement_edit):getString(R.string.settlement_suggested_amount,InrFormatter.format(limit)));
                binding.editNote.setVisibility(state.editing?View.VISIBLE:View.GONE);
                binding.upiAddress.setVisibility(state.editing?View.GONE:View.VISIBLE); binding.upiNotice.setVisibility(state.editing?View.GONE:View.VISIBLE);
                binding.upiAddress.setText(model.upi()==null?getString(R.string.upi_missing):getString(R.string.upi_address,model.upi()));
                binding.pay.setVisibility(state.editing?View.GONE:View.VISIBLE);
                binding.pay.setText(getString(R.string.upi_pay,InrFormatter.format(limit)));
                binding.pay.setEnabled(!state.busy && state.actor.equals(model.text("payer")) && model.upi()!=null && limit>0);
                if(!binding.amount.getText().toString().equals(model.text("amount"))) binding.amount.setText(model.text("amount"));
                binding.amount.setEnabled(!state.busy && model.mayRecord()); binding.date.setEnabled(!state.busy && model.mayRecord());
                binding.date.setText(getString(R.string.settlement_date,ExpenseUi.date(model.date())));
                binding.record.setText(state.editing?R.string.settlement_save:R.string.settlement_record);
                binding.record.setEnabled(!state.busy && model.mayRecord() && limit>0);
                binding.partyOnly.setVisibility(model.mayRecord()?View.GONE:View.VISIBLE);
            }
            binding.suggestions.removeAllViews(); binding.records.removeAllViews();
            binding.empty.setVisibility(details.suggestions.isEmpty()?View.VISIBLE:View.GONE);
            binding.noRecords.setVisibility(details.records.isEmpty()?View.VISIBLE:View.GONE);
            for(Transfer transfer:details.suggestions) {
                ItemMemberBinding row=ItemMemberBinding.inflate(getLayoutInflater(),binding.suggestions,false);
                row.name.setText(parties(details,transfer.payerId,transfer.payeeId)); row.role.setText(getString(R.string.settlement_suggested_amount,InrFormatter.format(transfer.amountPaise)));
                row.action.setText(R.string.settlement_view); row.action.setEnabled(!state.busy);
                row.action.setOnClickListener(v -> { model.select(transfer); binding.scroll.smoothScrollTo(0,0); }); binding.suggestions.addView(row.getRoot());
            }
            List<Settlement> newest=new ArrayList<>(details.records); Collections.reverse(newest);
            for(Settlement record:newest) {
                ItemMemberBinding row=ItemMemberBinding.inflate(getLayoutInflater(),binding.records,false);
                row.name.setText(parties(details,record.payerId,record.payeeId)); row.role.setText(getString(R.string.settlement_recorded_amount,InrFormatter.format(record.amountPaise),ExpenseUi.date(record.settlementDate)));
                row.action.setText(R.string.settlement_edit); row.action.setEnabled(!state.busy);
                row.action.setOnClickListener(v -> { model.select(record); binding.scroll.smoothScrollTo(0,0); }); binding.records.addView(row.getRoot());
            }
        }
        rendering=false;
        if(state.duplicate) { model.consumeDuplicate(); confirm(R.string.settlement_duplicate,R.string.settlement_proceed,() -> model.save(true)); }
    }
    private String parties(SettlementDetails details,String payer,String payee) {
        // A stale selection can disappear after another record changes the suggested pairing.
        String a=details.group.names.containsKey(payer)?ExpenseUi.name(requireContext(),details.group,payer):payer;
        String b=details.group.names.containsKey(payee)?ExpenseUi.name(requireContext(),details.group,payee):payee;
        return getString(R.string.settlement_parties,a,b);
    }
    private void confirm(int message,int positive,Runnable action) {
        if(dialog!=null) dialog.dismiss();
        dialog=new MaterialAlertDialogBuilder(requireContext()).setMessage(message).setNegativeButton(R.string.group_cancel,null).setPositiveButton(positive,(d,w) -> action.run()).show();
    }
    @Override public void onStart() { super.onStart(); model.start(); }
    @Override public void onStop() { model.stop(); super.onStop(); }
    @Override public void onDestroyView() { if(dialog!=null) dialog.dismiss(); if(dateDialog!=null) dateDialog.dismiss(); dialog=null; dateDialog=null; super.onDestroyView(); binding=null; }
}
