package org.briarproject.briar.android.remotewipe.activate;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.fragment.BaseFragment;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

public class ActivateRemoteWipeExplainerFragment extends
		BaseFragment {

	public static final String TAG =
			ActivateRemoteWipeExplainerFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private ActivateRemoteWipeViewModel viewModel;

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(ActivateRemoteWipeViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_activate_remote_wipe_explainer,
				container, false);

		TextView titleText = view.findViewById(R.id.textView);
	    titleText.setText(String.format(getString(R.string.remote_wipe_activate_explain_short), viewModel.getContactName()));

		TextView explainText = view.findViewById(R.id.textViewExplain);
		explainText.setText(String.format(getString(R.string.remote_wipe_activate_explain_long), viewModel.getContactName()));

		Button cancelButton = view.findViewById(R.id.button_cancel);
		cancelButton.setOnClickListener(e -> viewModel.onCancelClicked());

		Button confirmButton = view.findViewById(R.id.button_confirm);
		confirmButton.setOnClickListener(e -> viewModel.onConfirmClicked());
		return view;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}
