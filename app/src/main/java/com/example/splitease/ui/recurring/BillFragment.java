package com.example.splitease.ui.recurring;
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
import com.example.splitease.*;
import com.example.splitease.databinding.*;
import com.example.splitease.domain.RecurringSchedule;
import com.example.splitease.model.*;
import com.example.splitease.ui.expenses.ExpenseUi;
import com.example.splitease.util.InrFormatter;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

public final class BillFragment extends Fragment {
 private FragmentBillBinding binding;private RecurringViewModel model;private boolean rendering;private AlertDialog dialog;
 private final List<String> groupIds=new ArrayList<>(),handlerIds=new ArrayList<>();
 @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle saved){binding=FragmentBillBinding.inflate(inflater,parent,false);return binding.getRoot();}
 @Override public void onViewCreated(@NonNull View view,Bundle saved){
  model=new ViewModelProvider(this).get(RecurringViewModel.class);String id=requireArguments().getString("billId");model.configure(id,id==null);ViewCompat.setAccessibilityHeading(binding.title,true);
  binding.back.setOnClickListener(v->NavHostFragment.findNavController(this).popBackStack());binding.refresh.setOnClickListener(v->model.refresh());binding.edit.setOnClickListener(v->model.edit());
  watch(binding.name,"title",false);watch(binding.amount,"amount",true);watch(binding.day,"day",false);watch(binding.lead,"lead",false);
  binding.active.setOnCheckedChangeListener((b,checked)->{if(!rendering)model.active(checked);});
  binding.group.setOnItemSelectedListener(listener(groupIds,true));binding.handler.setOnItemSelectedListener(listener(handlerIds,false));
  binding.save.setOnClickListener(v->confirm(R.string.bill_edit_confirm,model::save));model.state().observe(getViewLifecycleOwner(),this::render);
 }
 private AdapterView.OnItemSelectedListener listener(List<String> ids,boolean group){return new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int position,long id){if(!rendering&&position<ids.size()){String value=ids.get(position);if(group){if(!value.equals(model.text("group")))model.group(value);}else model.handler(value);}}};}
 private void watch(EditText field,String key,boolean preview){field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){if(!rendering){model.text(key,s.toString());if(preview)model.changed();}}});}
 private void field(EditText field,String value,boolean enabled){if(!field.getText().toString().equals(value))field.setText(value);field.setEnabled(enabled);}
 private void options(Spinner spinner,List<String> stored,List<String> ids,List<String> labels,String selected){
  if(!stored.equals(ids)||!labels.equals(spinner.getTag())){stored.clear();stored.addAll(ids);spinner.setTag(new ArrayList<>(labels));spinner.setAdapter(new ArrayAdapter<>(requireContext(),android.R.layout.simple_spinner_dropdown_item,labels));}
  int index=stored.indexOf(selected);if(index>=0&&spinner.getSelectedItemPosition()!=index)spinner.setSelection(index);
 }
 private void render(RecurringViewModel.State state){
  rendering=true;binding.progress.setVisibility(state.loading||state.busy?View.VISIBLE:View.GONE);binding.error.setVisibility(state.error==0?View.GONE:View.VISIBLE);if(state.error!=0)binding.error.setText(state.error);
  boolean available=state.data!=null&&(model.bill()!=null||state.editing&&getArguments().getString("billId")==null);
  binding.content.setVisibility(available?View.VISIBLE:View.GONE);
  if(available){
   RecurringBill bill=model.bill();binding.title.setText(bill==null?getString(R.string.bill_new):bill.title);
   binding.form.setVisibility(state.editing?View.VISIBLE:View.GONE);binding.edit.setVisibility(!state.editing&&bill!=null?View.VISIBLE:View.GONE);binding.edit.setEnabled(!state.busy);
   List<String> ids=new ArrayList<>(),labels=new ArrayList<>();for(GroupDetails g:state.data.groups){ids.add(g.group.id);labels.add(g.group.name);}options(binding.group,groupIds,ids,labels,model.text("group"));binding.group.setEnabled(bill==null&&!state.busy);
   GroupDetails group=model.group();Set<String> current=new HashSet<>();ids=new ArrayList<>();labels=new ArrayList<>();if(group!=null)for(MemberIdentity m:group.members){ids.add(m.id);labels.add(m.username);current.add(m.id);}
   if(model.text("handler").isEmpty()&&!ids.isEmpty())model.handler(ids.get(0));
   // Retain departed template entries so a bill can always be deactivated; active saves require current identities.
   if(!model.text("handler").isEmpty()&&!ids.contains(model.text("handler"))){ids.add(model.text("handler"));labels.add(getString(R.string.expense_former,model.name(model.text("handler"))));}
   options(binding.handler,handlerIds,ids,labels,model.text("handler"));binding.handler.setEnabled(!state.busy);
   field(binding.name,model.text("title"),!state.busy);field(binding.amount,model.text("amount"),!state.busy);field(binding.day,model.text("day"),!state.busy);field(binding.lead,model.text("lead"),!state.busy);binding.active.setChecked(model.active());binding.active.setEnabled(!state.busy);
   binding.participants.removeAllViews();Set<String> participants=new LinkedHashSet<>(current);participants.addAll(model.selected());
   for(String id:participants){MaterialCheckBox box=new MaterialCheckBox(requireContext());box.setText(current.contains(id)?model.name(id):getString(R.string.expense_former,model.name(id)));box.setChecked(model.selected().contains(id));box.setEnabled(!state.busy);box.setOnCheckedChangeListener((v,checked)->{if(!rendering)model.select(id,checked);});binding.participants.addView(box);}
   binding.shares.setText(model.preview());binding.save.setEnabled(!state.busy&&group!=null);
   boolean invalid=bill!=null&&(!current.containsAll(bill.participantIds)||bill.handlerId!=null&&!current.contains(bill.handlerId));binding.needsMembers.setVisibility(invalid&&bill.active?View.VISIBLE:View.GONE);
   binding.current.removeAllViews();binding.history.removeAllViews();List<RecurringBillCycle> history=bill==null?Collections.emptyList():state.data.cycles.get(bill.id);int month=RecurringSchedule.month(System.currentTimeMillis()),old=0;boolean found=false;
   for(RecurringBillCycle cycle:history){boolean now=cycle.year*12+cycle.month-1==month;if(now)found=true;else old++;cycle(now?binding.current:binding.history,cycle,state.busy);}
   binding.currentLabel.setVisibility(bill==null?View.GONE:View.VISIBLE);binding.noCurrent.setVisibility(bill!=null&&!found?View.VISIBLE:View.GONE);binding.historyLabel.setVisibility(bill==null?View.GONE:View.VISIBLE);binding.noCycles.setVisibility(bill!=null&&old==0?View.VISIBLE:View.GONE);
  }
  rendering=false;if(state.savedId!=null){model.consumeSaved();
   if(getArguments().getString("billId")==null){Bundle args=new Bundle();args.putString("billId",state.savedId);NavHostFragment.findNavController(this).navigate(R.id.bill_screen,args,new androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.bill_screen,true).build());}
   else binding.scroll.smoothScrollTo(0,0);}
 }
 private void cycle(LinearLayout parent,RecurringBillCycle cycle,boolean busy){
  int paid=0;for(RecurringBillContribution c:cycle.contributions)if(c.paid)paid++;
  TextView title=new TextView(requireContext());title.setTextAppearance(R.style.TextAppearance_SplitEase_CardTitle);title.setPadding(0,24,0,12);title.setText(getString(R.string.bill_cycle,cycle.year,cycle.month,ExpenseUi.date(cycle.dueDate),paid,cycle.contributions.size()));ViewCompat.setAccessibilityHeading(title,true);parent.addView(title);
  TextView handler=new TextView(requireContext());handler.setTextAppearance(R.style.TextAppearance_SplitEase_Body);handler.setText(getString(R.string.bill_cycle_handler,cycle.handlerId==null?getString(R.string.bill_unknown_handler):model.name(cycle.handlerId),InrFormatter.format(cycle.amountPaise)));parent.addView(handler);
  for(RecurringBillContribution contribution:cycle.contributions){ItemMemberBinding row=ItemMemberBinding.inflate(getLayoutInflater(),parent,false);row.name.setText(model.name(contribution.userId));row.role.setText(getString(R.string.bill_contribution,InrFormatter.format(contribution.sharePaise),getString(contribution.paid?R.string.bill_paid:R.string.bill_unpaid)));row.action.setText(contribution.paid?R.string.bill_mark_unpaid:R.string.bill_mark_paid);row.action.setEnabled(!busy);row.action.setOnClickListener(v->confirm(R.string.bill_status_confirm,()->model.paid(cycle.id,contribution.userId,!contribution.paid)));parent.addView(row.getRoot());}
 }
 private void confirm(int message,Runnable action){if(dialog!=null)dialog.dismiss();dialog=new MaterialAlertDialogBuilder(requireContext()).setMessage(message).setNegativeButton(R.string.group_cancel,null).setPositiveButton(R.string.group_confirm,(d,w)->action.run()).show();}
 @Override public void onStart(){super.onStart();model.start();}
 @Override public void onStop(){model.stop();super.onStop();}
 @Override public void onDestroyView(){if(dialog!=null)dialog.dismiss();dialog=null;super.onDestroyView();binding=null;}
}
