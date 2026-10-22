package com.example.splitease.ui.expenses;

import android.app.DatePickerDialog;
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
import com.example.splitease.util.InrFormatter;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

public final class ExpenseFragment extends Fragment {
    private FragmentExpenseBinding binding;
    private ExpenseViewModel model;
    private boolean rendering;
    private AlertDialog confirmation;
    private DatePickerDialog dateDialog;
    @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle state) {
        binding=FragmentExpenseBinding.inflate(inflater,parent,false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view,Bundle saved) {
        model=new ViewModelProvider(this).get(ExpenseViewModel.class);
        model.configure(requireArguments().getString("groupId"),requireArguments().getString("expenseId"));
        ViewCompat.setAccessibilityHeading(binding.heading,true);
        binding.error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        binding.back.setOnClickListener(v -> back()); binding.retry.setOnClickListener(v -> model.load());
        watch(binding.expenseTitle,"title"); watch(binding.expenseAmount,"amount");
        binding.save.setOnClickListener(v -> model.save()); binding.edit.setOnClickListener(v -> model.edit());
        binding.delete.setOnClickListener(v -> {
            confirmation=new MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.expense_delete_confirm)
                .setNegativeButton(R.string.group_cancel,null).setPositiveButton(R.string.expense_delete,(dialog,which) -> model.delete()).show();
        });
        binding.date.setOnClickListener(v -> {
            Calendar date=Calendar.getInstance(TimeZone.getTimeZone("UTC")); date.setTimeInMillis(model.date());
            dateDialog=new DatePickerDialog(requireContext(),(picker,y,m,d) -> {
                Calendar selected=Calendar.getInstance(TimeZone.getTimeZone("UTC")); selected.clear(); selected.set(y,m,d); model.date(selected.getTimeInMillis());
            },date.get(Calendar.YEAR),date.get(Calendar.MONTH),date.get(Calendar.DAY_OF_MONTH));
            dateDialog.getDatePicker().setMinDate(0); dateDialog.show();
        });
        model.state().observe(getViewLifecycleOwner(),state -> {
            rendering=true;
            binding.progress.setVisibility(state.loading || state.busy?View.VISIBLE:View.GONE);
            binding.error.setVisibility(state.error==0?View.GONE:View.VISIBLE); if(state.error!=0) binding.error.setText(state.error);
            binding.retry.setVisibility(state.error==0?View.GONE:View.VISIBLE);
            binding.form.setVisibility(state.group==null?View.GONE:View.VISIBLE);
            if(state.group!=null) {
                boolean edit=state.editing && !state.busy;
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
                preview();
            }
            rendering=false;
            if(state.done && isResumed()) { model.consumeDone(); back(); }
        });
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
    private void back() { NavHostFragment.findNavController(this).popBackStack(); }
    @Override public void onStart() { super.onStart(); model.start(); }
    @Override public void onStop() { model.stop(); super.onStop(); }
    @Override public void onResume() { super.onResume(); ExpenseViewModel.State state=model.state().getValue(); if(state!=null && state.done) { model.consumeDone(); back(); } }
    @Override public void onDestroyView() { if(confirmation!=null) confirmation.dismiss(); if(dateDialog!=null) dateDialog.dismiss(); confirmation=null; dateDialog=null; super.onDestroyView(); binding=null; }
}
