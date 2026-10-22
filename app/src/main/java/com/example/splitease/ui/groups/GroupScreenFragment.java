package com.example.splitease.ui.groups;

import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.core.view.ViewCompat;
import androidx.appcompat.app.AlertDialog;
import com.example.splitease.R;
import com.example.splitease.databinding.ViewMemberSearchBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Small shared lifecycle/view helpers. No storage work. */
abstract class GroupScreenFragment extends Fragment {
    protected GroupsViewModel model;
    private AlertDialog confirmation;
    protected void setup(String mode,String id,TextView title) {
        model=new ViewModelProvider(this).get(GroupsViewModel.class);
        model.configure(mode,id);
        ViewCompat.setAccessibilityHeading(title,true);
    }
    @Override public void onStart() { super.onStart(); model.start(); }
    @Override public void onStop() { model.stop(); super.onStop(); }
    protected void back() { NavHostFragment.findNavController(this).popBackStack(); }
    protected void open(String id) {
        Bundle args=new Bundle(); args.putString("groupId",id);
        NavHostFragment.findNavController(this).navigate(R.id.group_detail,args);
    }
    protected void status(GroupsViewModel.State state,View progress,TextView error) {
        progress.setVisibility(state.loading || state.busy || state.searching ? View.VISIBLE : View.GONE);
        error.setVisibility(state.error==0 ? View.GONE : View.VISIBLE);
        if(state.error!=0) error.setText(state.error);
    }
    protected void wireSearch(ViewMemberSearchBinding search,boolean confirm) {
        search.search.setOnClickListener(v -> model.search(search.query.getText().toString()));
        search.query.setOnEditorActionListener((v,action,event) -> {
            if(action==EditorInfo.IME_ACTION_SEARCH) { search.search.performClick(); return true; } return false;
        });
        search.add.setOnClickListener(v -> {
            GroupsViewModel.State state=model.state().getValue();
            if(state==null || state.found==null) return;
            if(confirm) confirm(getString(R.string.group_add_confirm,state.found.username),model::selectFound);
            else model.selectFound();
        });
    }
    protected void renderSearch(ViewMemberSearchBinding search,GroupsViewModel.State state) {
        search.search.setEnabled(state.ready && !state.busy && !state.searching);
        search.query.setEnabled(!state.busy && !state.searching);
        search.result.setVisibility(state.found==null ? View.GONE : View.VISIBLE);
        search.add.setVisibility(state.found==null ? View.GONE : View.VISIBLE);
        search.add.setEnabled(!state.busy);
        if(state.found!=null) search.result.setText(state.found.username);
    }
    protected void confirm(String message,Runnable action) {
        if(confirmation!=null) confirmation.dismiss();
        confirmation=new MaterialAlertDialogBuilder(requireContext()).setMessage(message)
                .setNegativeButton(R.string.group_cancel,null)
                .setPositiveButton(R.string.group_confirm,(dialog,which) -> action.run()).show();
    }
    @Override public void onDestroyView() {
        if(confirmation!=null) confirmation.dismiss(); confirmation=null;
        super.onDestroyView();
    }
}
