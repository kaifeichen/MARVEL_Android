package edu.berkeley.cs.sdb.cellmate;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.WindowManager;

import com.splunk.mint.Mint;

public class MainActivity extends AppCompatActivity implements PreviewFragment.StateCallback, ControlFragment.StateCallback {
    private static final String MINT_API_KEY = "76da1102";





    @Override
    public void onObjectIdentified(String name, double x, double y, double size) {
        PreviewFragment previewFragment = (PreviewFragment) getFragmentManager().findFragmentById(R.id.preview_fragment);
        if(previewFragment != null) {
            previewFragment.drawHighlight(name, x, y, size);
        }

    }




    @Override
    protected void onResume() {
        super.onResume();

        Camera camera = Camera.getInstance();
        if(!camera.isOpen()) {
            camera.openCamera();
        }
    }


    @Override
    protected void onPause() {
        if(!isChangingConfigurations()) {
            Camera camera = Camera.getInstance();
            camera.closeCamera();
        }
        super.onPause();
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Mint.initAndStartSession(this, MINT_API_KEY);

        setContentView(R.layout.main_activity);

        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (savedInstanceState == null) {
            Size size = new Size(640, 480);
            Camera.getInstance(getApplicationContext(),size);


            PreviewFragment previewFragment = PreviewFragment.newInstance();
            getFragmentManager().beginTransaction().replace(R.id.preview_fragment, previewFragment).commit();

            ControlFragment controlFragment = ControlFragment.newInstance();
            getFragmentManager().beginTransaction().replace(R.id.task_fragment, controlFragment).commit();
        }

        // hide action bar
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowHomeEnabled(false);
        actionBar.setDisplayShowTitleEnabled(false);
    }



    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.settings:
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }


}
