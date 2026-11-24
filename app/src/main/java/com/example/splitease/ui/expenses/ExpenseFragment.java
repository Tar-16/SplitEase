package com.example.splitease.ui.expenses;

import android.app.DatePickerDialog;
import android.Manifest;
import android.net.Uri;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.R;
import com.example.splitease.databinding.FragmentExpenseBinding;
import com.example.splitease.databinding.ItemBillBinding;
import com.example.splitease.util.InrFormatter;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

public final class ExpenseFragment extends Fragment {
    private FragmentExpenseBinding binding;
    private ExpenseViewModel model;
    private boolean rendering,showReceiptDebug;
    private String billSignature;
    private AlertDialog confirmation;
    private DatePickerDialog dateDialog;
    private final ActivityResultLauncher<Uri> camera=registerForActivityResult(new ActivityResultContracts.TakePicture(),success->{model.captureResult(success);});
    private final ActivityResultLauncher<String> cameraPermission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{if(granted)model.prepareCapture();else model.cameraDenied();});
    @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle state) {
        binding=FragmentExpenseBinding.inflate(inflater,parent,false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,Bundle saved) {
        com.example.splitease.ui.groups.GroupDependencies dependencies=com.example.splitease.debug.CloudGroups.dependencies(this);
        if(dependencies==null)model=new ViewModelProvider(this).get(ExpenseViewModel.class);
        else model=new ViewModelProvider(this,new androidx.lifecycle.AbstractSavedStateViewModelFactory(this,getArguments()){
            @Override protected <T extends androidx.lifecycle.ViewModel>T create(String key,Class<T> type,androidx.lifecycle.SavedStateHandle handle){return type.cast(new ExpenseViewModel(requireActivity().getApplication(),handle,dependencies));}
        }).get(ExpenseViewModel.class);
        model.configure(requireArguments().getString("groupId"),requireArguments().getString("expenseId"));
        ViewCompat.setAccessibilityHeading(binding.heading,true);ViewCompat.setAccessibilityHeading(binding.receiptHeading,true);binding.receiptMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        binding.receiptDeviceOnly.setVisibility(model.cloud()&&model.receiptsAvailable()?View.VISIBLE:View.GONE);
        binding.error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        binding.back.setOnClickListener(v -> back()); binding.retry.setOnClickListener(v -> model.refresh());
        watch(binding.expenseDescription,"description");watch(binding.expenseTitle,"title"); watch(binding.expenseAmount,"amount");
        binding.save.setOnClickListener(v -> model.save()); binding.edit.setOnClickListener(v -> model.edit());
        binding.delete.setOnClickListener(v -> {
            com.example.splitease.model.Expense expected=model.deletionTarget();
            confirmation=new MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.expense_delete_confirm)
                .setNegativeButton(R.string.group_cancel,null).setPositiveButton(R.string.expense_delete,(dialog,which) -> model.delete(expected)).show();
        });
        binding.date.setOnClickListener(v -> {
            Calendar date=Calendar.getInstance(TimeZone.getTimeZone("UTC")); date.setTimeInMillis(model.date());
            dateDialog=new DatePickerDialog(requireContext(),(picker,y,m,d) -> {
                Calendar selected=Calendar.getInstance(TimeZone.getTimeZone("UTC")); selected.clear(); selected.set(y,m,d); model.date(selected.getTimeInMillis());
            },date.get(Calendar.YEAR),date.get(Calendar.MONTH),date.get(Calendar.DAY_OF_MONTH));
            dateDialog.getDatePicker().setMinDate(0); dateDialog.show();
        });
        binding.scanReceipt.setOnClickListener(v->{if(ContextCompat.checkSelfPermission(requireContext(),Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)model.prepareCapture();else cameraPermission.launch(Manifest.permission.CAMERA);});
        binding.receiptCameraSettings.setOnClickListener(v->startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+requireContext().getPackageName()))));
        binding.receiptRetry.setOnClickListener(v->model.retryReceipts());
        binding.receiptDebugToggle.setOnClickListener(v->{showReceiptDebug=!showReceiptDebug;binding.receiptDebug.setVisibility(showReceiptDebug?View.VISIBLE:View.GONE);});
        binding.billsUseTotal.setOnClickListener(v->{if(model.useBillsTotal())Toast.makeText(requireContext(),R.string.bills_total_applied,Toast.LENGTH_SHORT).show();});
        binding.receiptCancelCapture.setOnClickListener(v->model.captureResult(false));
        model.state().observe(getViewLifecycleOwner(),state -> {
            rendering=true;
            binding.progress.setVisibility(state.loading || state.busy?View.VISIBLE:View.GONE);
            binding.error.setVisibility(state.error==0?View.GONE:View.VISIBLE); if(state.error!=0) binding.error.setText(state.error);
            binding.retry.setVisibility(state.error==0?View.GONE:View.VISIBLE);
            binding.form.setVisibility(state.group==null?View.GONE:View.VISIBLE);
            if(state.group!=null) {
                boolean edit=state.editing && !state.busy;
                binding.expenseDescriptionLayout.setVisibility(model.cloud()?View.VISIBLE:View.GONE);
                setText(binding.expenseDescription,model.text("description"));binding.expenseDescription.setEnabled(edit);
                binding.heading.setText(state.editing ? (state.expense==null?R.string.expense_add:R.string.expense_edit):R.string.expense_details);
                setText(binding.expenseTitle,model.text("title")); setText(binding.expenseAmount,model.text("amount"));
                binding.expenseTitle.setEnabled(edit); binding.expenseAmount.setEnabled(edit);
                List<String> ids=model.payerOptions(), names=new ArrayList<>();
                for(String id:ids) names.add(ExpenseUi.name(requireContext(),state.group,id));
                spinner(binding.payer,names,ids,model.text("payer"),"payer",edit);
                List<String> categories=new ArrayList<>(ExpenseViewModel.CATEGORIES);
                if(!categories.contains(model.text("category"))) categories.add(model.text("category"));
                spinner(binding.category,categories,categories,model.text("category"),"category",edit);
                binding.date.setText(getString(R.string.expense_date,ExpenseUi.date(model.date()))); binding.date.setEnabled(edit);
                binding.participants.removeAllViews();
                for(String id:model.participantOptions()) {
                    MaterialCheckBox checkbox=new MaterialCheckBox(requireContext());
                    checkbox.setText(ExpenseUi.name(requireContext(),state.group,id)); checkbox.setTag(id);
                    checkbox.setChecked(model.selected().contains(id)); checkbox.setEnabled(edit);
                    checkbox.setOnCheckedChangeListener((button,checked) -> { model.participant(id,checked); preview(); });
                    binding.participants.addView(checkbox);
                }
                binding.save.setVisibility(state.editing?View.VISIBLE:View.GONE); binding.save.setEnabled(!state.busy);
                binding.edit.setVisibility(state.editing?View.GONE:View.VISIBLE); binding.edit.setEnabled(!state.busy);
                binding.delete.setVisibility(state.expense==null?View.GONE:View.VISIBLE); binding.delete.setEnabled(!state.busy);
                binding.scanReceipt.setVisibility(state.editing?View.VISIBLE:View.GONE);binding.scanReceipt.setEnabled(edit&&state.canAddBill);
                binding.scanReceipt.setText(state.bills.isEmpty()?R.string.receipt_scan:model.cloud()?R.string.bill_replace:R.string.receipt_retake);
                boolean debug=com.example.splitease.BuildConfig.DEBUG&&state.receiptDebug!=null;
                binding.receiptDebugToggle.setVisibility(debug?View.VISIBLE:View.GONE);
                binding.receiptDebug.setText(debug?state.receiptDebug:"");binding.receiptDebug.setVisibility(debug&&showReceiptDebug?View.VISIBLE:View.GONE);
                binding.receiptMessage.setVisibility(View.VISIBLE);binding.receiptMessage.setText(state.receiptMessage);
                renderBills(state,edit);
                binding.receiptRetry.setVisibility(state.receiptMessage==R.string.receipt_unavailable?View.VISIBLE:View.GONE);binding.receiptRetry.setEnabled(!state.busy);
                binding.receiptCameraSettings.setVisibility(state.receiptMessage==R.string.receipt_camera_denied?View.VISIBLE:View.GONE);
                binding.receiptCancelCapture.setVisibility(state.capturePending?View.VISIBLE:View.GONE);
                preview();
            }
            if(!model.receiptsAvailable())for(View receiptView:Arrays.asList(binding.receiptHeading,binding.billsSummary,binding.billsList,binding.receiptMessage,binding.receiptDebugToggle,binding.receiptDebug,binding.scanReceipt,binding.receiptCameraSettings,binding.receiptCancelCapture,binding.receiptRetry))receiptView.setVisibility(View.GONE);
            rendering=false;launchCapture();
            if(state.done && isResumed()) {
                if(state.receiptLinkFailed)Toast.makeText(requireContext(),R.string.receipt_device_link_failed,Toast.LENGTH_LONG).show();
                model.consumeDone(); back();
            }
        });
    }
    private void renderBills(ExpenseViewModel.State state,boolean edit) {
        binding.billsSummary.setVisibility(state.billAmounts&&!state.bills.isEmpty()?View.VISIBLE:View.GONE);
        binding.billsUseTotal.setVisibility(state.editing?View.VISIBLE:View.GONE);
        showBillTotals(state);
        StringBuilder signature=new StringBuilder().append(edit).append(state.editing).append(state.billAmounts);
        for(ExpenseViewModel.Bill bill:state.bills)signature.append('|').append(bill.id).append(bill.draft).append(bill.thumbnail!=null).append(bill.note);
        if(signature.toString().equals(billSignature))return; // Keep rows (and any focused amount field) while unrelated state changes.
        billSignature=signature.toString();
        binding.billsList.removeAllViews();
        int index=0;
        for(ExpenseViewModel.Bill bill:state.bills) {
            ItemBillBinding row=ItemBillBinding.inflate(getLayoutInflater(),binding.billsList,false);
            row.billTitle.setText(getString(R.string.bill_row_title,++index));
            row.billThumbnail.setImageBitmap(bill.thumbnail);
            row.billAmountLayout.setVisibility(state.billAmounts?View.VISIBLE:View.GONE);
            row.billAmount.setText(bill.amountText);row.billAmount.setEnabled(edit);
            row.billNote.setVisibility(bill.note==null?View.GONE:View.VISIBLE);row.billNote.setText(bill.note);
            row.billAmount.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
                @Override public void onTextChanged(CharSequence s,int start,int before,int count) { model.billAmount(bill.id,s.toString());showBillTotals(model.snapshot()); }
                @Override public void afterTextChanged(Editable editable) {}
            });
            row.billView.setEnabled(!state.busy);
            row.billView.setOnClickListener(v->{Bundle args=new Bundle();args.putString("receiptId",bill.id);NavHostFragment.findNavController(this).navigate(R.id.receipt_viewer,args,com.example.splitease.ui.Transitions.page().build());});
            row.billRemove.setVisibility(state.editing?View.VISIBLE:View.GONE);row.billRemove.setEnabled(edit);
            row.billRemove.setOnClickListener(v->{confirmation=new MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.bill_remove_confirm).setNegativeButton(R.string.group_cancel,null).setPositiveButton(R.string.bill_remove_action,(d,w)->model.removeBill(bill.id)).show();});
            binding.billsList.addView(row.getRoot());
        }
    }
    private void showBillTotals(ExpenseViewModel.State state) {
        if(binding==null)return;
        boolean has=state.billsTotalPaise!=null;
        binding.billsTotal.setText(has?InrFormatter.format(state.billsTotalPaise):getString(R.string.bills_total_none));
        binding.billsTotalNote.setText(!has?getString(R.string.bills_total_none_note):state.billsWithAmount==state.bills.size()?getResources().getQuantityString(R.plurals.bills_with_amount,state.billsWithAmount,state.billsWithAmount):getString(R.string.bills_total_note_some,state.billsWithAmount,state.bills.size()));
        binding.billsUseTotal.setEnabled(has);
    }
    private void watch(EditText field,String key) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { if(!rendering) { model.text(key,s.toString()); preview(); } }
            @Override public void afterTextChanged(Editable editable) {}
        });
    }
    private void setText(EditText field,String value) { if(!field.getText().toString().equals(value)) field.setText(value); }
    private void spinner(Spinner spinner,List<String> names,List<String> values,String selected,String key,boolean enabled) {
        spinner.setOnItemSelectedListener(null);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(requireContext(),android.R.layout.simple_spinner_item,names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0,values.indexOf(selected))); spinner.setEnabled(enabled);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) { if(!rendering && enabled && position<values.size()) model.text(key,values.get(position)); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    private void preview() {
        ExpenseViewModel.State state=model.state().getValue(); if(state==null || state.group==null) return;
        try {
            StringJoiner lines=new StringJoiner("\n");
            for(Map.Entry<String,Long> entry:model.preview().entrySet()) {
                if(!state.group.names.containsKey(entry.getKey())) throw new IllegalArgumentException();
                lines.add(getString(R.string.expense_share,ExpenseUi.name(requireContext(),state.group,entry.getKey()),InrFormatter.format(entry.getValue())));
            }
            binding.shares.setText(lines.toString());
        } catch(IllegalArgumentException | ArithmeticException failure) { binding.shares.setText(R.string.expense_preview_hint); }
    }
    private void launchCapture(){if(!isResumed())return;Uri uri=model.consumeCapture();if(uri!=null)try{camera.launch(uri);}catch(ActivityNotFoundException|SecurityException unavailable){model.cameraUnavailable();}}
    private void back() { NavHostFragment.findNavController(this).popBackStack(); }
    @Override public void onStart() { super.onStart(); model.start(); }
    @Override public void onStop() { model.stop(); super.onStop(); }
    @Override public void onResume() { super.onResume();launchCapture(); ExpenseViewModel.State state=model.state().getValue(); if(state!=null && state.done) { model.consumeDone(); back(); } }
    @Override public void onDestroyView() { if(confirmation!=null) confirmation.dismiss(); if(dateDialog!=null) dateDialog.dismiss(); confirmation=null; dateDialog=null; super.onDestroyView(); binding=null; }
}
