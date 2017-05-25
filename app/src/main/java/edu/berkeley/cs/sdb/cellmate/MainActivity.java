package edu.berkeley.cs.sdb.cellmate;

import android.app.Activity;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

import com.splunk.mint.Mint;

public class MainActivity extends Activity {

    private static final String MINT_API_KEY = "76da1102";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Mint.initAndStartSession(this.getApplication(), MINT_API_KEY);

        setContentView(R.layout.main);
        if (null == savedInstanceState) {
            getFragmentManager().beginTransaction()
                    .replace(R.id.container, CameraFragment.newInstance())
                    .commit();
        }
    }

}
