package edu.berkeley.cs.sdb.cellmate;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import com.splunk.mint.Mint;

public class MainActivity extends AppCompatActivity {
    private static final String MINT_API_KEY = "76da1102";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Mint.initAndStartSession(this, MINT_API_KEY);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, CameraFragment.newInstance())
                    .commit();
        }
    }

}
