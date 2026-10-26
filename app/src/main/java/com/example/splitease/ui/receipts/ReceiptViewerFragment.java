package com.example.splitease.ui.receipts;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.example.splitease.databinding.FragmentReceiptViewerBinding;

public final class ReceiptViewerFragment extends Fragment {
 private FragmentReceiptViewerBinding binding;private ReceiptViewerViewModel model;
 @Override public View onCreateView(@NonNull LayoutInflater inflater,ViewGroup parent,Bundle saved){binding=FragmentReceiptViewerBinding.inflate(inflater,parent,false);return binding.getRoot();}
 @Override public void onViewCreated(@NonNull View view,Bundle saved){model=new ViewModelProvider(this).get(ReceiptViewerViewModel.class);model.configure(requireArguments().getString("receiptId"));binding.back.setOnClickListener(v->NavHostFragment.findNavController(this).popBackStack());binding.retry.setOnClickListener(v->model.load());binding.zoomIn.setOnClickListener(v->binding.image.zoomBy(1.5f));binding.zoomOut.setOnClickListener(v->binding.image.zoomBy(1/1.5f));model.state().observe(getViewLifecycleOwner(),state->{binding.progress.setVisibility(state.loading?View.VISIBLE:View.GONE);binding.error.setVisibility(state.failed?View.VISIBLE:View.GONE);binding.retry.setVisibility(state.failed?View.VISIBLE:View.GONE);binding.image.image(state.image);binding.image.setVisibility(state.image==null?View.INVISIBLE:View.VISIBLE);binding.zoomIn.setEnabled(state.image!=null);binding.zoomOut.setEnabled(state.image!=null);});}
 @Override public void onStart(){super.onStart();model.start();}
 @Override public void onStop(){model.stop();super.onStop();}
 @Override public void onDestroyView(){super.onDestroyView();binding=null;}
}
