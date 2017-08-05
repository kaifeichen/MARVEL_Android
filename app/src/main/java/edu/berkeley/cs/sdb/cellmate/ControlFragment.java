package edu.berkeley.cs.sdb.cellmate;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Created by tongli on 7/12/17.
 */

public class ControlFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        if(mTarget.toLowerCase().contains("thermostat")) {
            return inflater.inflate(R.layout.thermostat, container, false);
        } else {
            return inflater.inflate(R.layout.control_fragment, container, false);
        }

    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextView = (TextView) view.findViewById(R.id.controlText);
        //setTarget function is called before the fragment is created
        mTextView.setText(mTarget);
    }

    String mTarget;
    TextView mTextView;

    public void setTarget(String traget) {
        mTarget = traget;
        if(mTextView != null) {
            //happens when setTarget function called after the fragment is created
            mTextView.setText(mTarget);
        }
    }
}
