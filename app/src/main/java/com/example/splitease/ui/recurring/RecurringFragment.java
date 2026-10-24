package com.example.splitease.ui.recurring;
import android.Manifest;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.*;
import com.example.splitease.databinding.*;
import com.example.splitease.model.*;
import com.example.splitease.util.InrFormatter;
import java.util.*;

public final class RecurringFragment extends Fragment {
 private FragmentRecurringBinding binding;private RecurringViewModel model;private boolean rendering;
 private final List<String> filters=new ArrayList<>();
 private final ActivityResultLauncher<String> permission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{if(binding!=null) {model.text("notification_requested","yes");notificationState();}((SplitEaseApplication)requireActivity().getApplication()).reminders().refresh(false);});
 @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle saved){binding=FragmentRecurringBinding.inflate(inflater,parent,false);return binding.getRoot();}
 @Override public void onViewCreated(@NonNull View view,Bundle saved){
  model=new ViewModelProvider(this).get(RecurringViewModel.class);ViewCompat.setAccessibilityHeading(binding.title,true);
  binding.refresh.setOnClickListener(v->model.refresh());binding.create.setOnClickListener(v->open(null));
  binding.notifications.setOnClickListener(v->{if(Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED && model.text("notification_requested").isEmpty()) permission.launch(Manifest.permission.POST_NOTIFICATIONS);
   else {android.content.Intent settings=Build.VERSION.SDK_INT>=26?new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,requireContext().getPackageName()):new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+requireContext().getPackageName()));startActivity(settings);}});
  binding.filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(!rendering && position<filters.size() && !filters.get(position).equals(model.text("filter"))){model.text("filter",filters.get(position));model.changed();}}});
  model.state().observe(getViewLifecycleOwner(),this::render);
 }
 private void notificationState(){boolean enabled=((SplitEaseApplication)requireActivity().getApplication()).reminders().permitted();binding.notifications.setText(enabled||!model.text("notification_requested").isEmpty()?R.string.bill_notification_settings:R.string.bill_notifications);binding.notificationStatus.setText(enabled?R.string.bill_notification_hint:R.string.bill_notification_denied);}
 private void render(RecurringViewModel.State state){rendering=true;notificationState();binding.progress.setVisibility(state.loading?View.VISIBLE:View.GONE);binding.error.setVisibility(state.error==0?View.GONE:View.VISIBLE);if(state.error!=0)binding.error.setText(state.error);binding.content.setVisibility(state.data==null?View.GONE:View.VISIBLE);
  if(state.data!=null){
   binding.create.setEnabled(!state.data.groups.isEmpty());
   List<String> ids=new ArrayList<>(),labels=new ArrayList<>();ids.add("");labels.add(getString(R.string.bill_all_groups));for(GroupDetails g:state.data.groups){ids.add(g.group.id);labels.add(g.group.name);}
   if(!ids.equals(filters)){filters.clear();filters.addAll(ids);binding.filter.setAdapter(new ArrayAdapter<>(requireContext(),android.R.layout.simple_spinner_dropdown_item,labels));}
   int index=filters.indexOf(model.text("filter"));binding.filter.setSelection(Math.max(0,index));
   binding.bills.removeAllViews();int count=0;
   for(RecurringBill b:state.data.bills){if(!model.text("filter").isEmpty()&&!b.groupId.equals(model.text("filter")))continue;count++;ItemMemberBinding row=ItemMemberBinding.inflate(getLayoutInflater(),binding.bills,false);row.name.setText(b.title);row.role.setText(getString(R.string.bill_summary,model.groupName(b.groupId),InrFormatter.format(b.amountPaise),b.dueDay,getString(b.active?R.string.bill_active:R.string.bill_inactive)));row.action.setText(R.string.bill_open);row.action.setOnClickListener(v->open(b.id));binding.bills.addView(row.getRoot());}
   binding.empty.setVisibility(count==0?View.VISIBLE:View.GONE);binding.empty.setText(state.data.groups.isEmpty()?R.string.bill_no_groups:R.string.bill_empty);
  }rendering=false;
 }
 private void open(String id){Bundle args=new Bundle();args.putString("billId",id);NavHostFragment.findNavController(this).navigate(R.id.bill_screen,args);}
 @Override public void onStart(){super.onStart();model.start();}
 @Override public void onStop(){model.stop();super.onStop();}
 @Override public void onDestroyView(){super.onDestroyView();binding=null;}
}
