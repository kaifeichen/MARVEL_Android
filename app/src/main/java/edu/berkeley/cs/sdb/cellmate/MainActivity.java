package edu.berkeley.cs.sdb.cellmate;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.WindowManager;

import com.google.common.util.concurrent.ExecutionError;
import com.splunk.mint.Mint;

public class MainActivity extends AppCompatActivity implements PreviewFragment.StateCallback, ControlFragment.StateCallback {
    private static final String MINT_API_KEY = "76da1102";

    private static final int REQUEST_CAMERA_PERMISSION = 1;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                throw new NullPointerException("Camera no permission");
            }
        }
    }

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
            Camera.getInstance(this,size);


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
