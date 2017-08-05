package edu.berkeley.cs.sdb.cellmate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;

import com.splunk.mint.Mint;

import java.util.List;

public class MainActivity extends AppCompatActivity implements ActivityCompat.OnRequestPermissionsResultCallback,  IdentificationFragment.StateCallback, PreviewFragment.StateCallback {
    private static final String MINT_API_KEY = "76da1102";

    private static final int REQUEST_CAMERA_PERMISSION = 1;

    public enum PermissionState {
        NOT_GRANTED,
        GRANTED
    }

    PermissionState mPermissionState;

    public enum Mode {
        NULL,
        CALIBRATION,
        CONTROL,
        PREVIEW
    }
    Mode mMode = Mode.NULL;


    private static final String TAG_CALIBRATION_FRAGMENT = "CalibrationFragment";
    CalibrationFragment mCalibrationFragment;

    private static final String FRAGMENT_DIALOG = "dialog";


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i("MainActivity", "Permission granted");
                createDefaultFragments(mSavedInstanceState);
                Camera camera = Camera.getInstance();
                if(!camera.isOpen()) {
                    camera.openCamera();
                }
                mPermissionState = PermissionState.GRANTED;
            } else {
                this.finish();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onObjectIdentified(List<String> name, List<Double> x, List<Double> y, List<Double> size, double width, double height) {
        PreviewFragment previewFragment = (PreviewFragment) getFragmentManager().findFragmentById(R.id.preview_fragment);
        if(previewFragment != null) {
            previewFragment.drawHighlight(name, x, y, size, width, height);
        }

    }

    @Override
    public void previewOnClicked(boolean isTargeting,String target) {
        if(mMode == Mode.CALIBRATION) {
            return;
        }
        if(isTargeting) {
            FragmentManager fm = getFragmentManager();
            FragmentTransaction ft = fm.beginTransaction();
            ControlFragment controlFragment = (ControlFragment) getFragmentManager().findFragmentById(R.id.task_fragment);
            if(controlFragment != null) {
                ft.remove(controlFragment);
            }
            controlFragment = new ControlFragment();
            controlFragment.setTarget(target);
            ft.replace(R.id.task_fragment, controlFragment);
            ft.commit();
        } else {
            FragmentManager fm = getFragmentManager();
            FragmentTransaction ft = fm.beginTransaction();
            ControlFragment controlFragment = (ControlFragment) getFragmentManager().findFragmentById(R.id.task_fragment);
            if(controlFragment != null) {
                ft.remove(controlFragment);
                ft.commit();
            }
        }
    }





    @Override
    protected void onResume() {
        super.onResume();
        Log.i("MainActivity", "onResume");
        if (mPermissionState == PermissionState.GRANTED) {
            Camera camera = Camera.getInstance();
            if (!camera.isOpen()) {
                camera.openCamera();
            }
        }
    }


    @Override
    protected void onPause() {
        Log.i("MainActivity", "onPause");
        if (mPermissionState == PermissionState.GRANTED) {
            Camera camera = Camera.getInstance();
            if(camera.isOpen()) {
                camera.closeCamera();
            }
        }

        super.onPause();
    }

    private void requestCameraPermission() {
            // No explanation needed, we can request the permission.
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_CAMERA_PERMISSION);
    }





    Bundle mSavedInstanceState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i("MainActivity", "onCreate");
        super.onCreate(savedInstanceState);

        mSavedInstanceState = savedInstanceState;
        Mint.initAndStartSession(this, MINT_API_KEY);

        // hide action bar
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowHomeEnabled(false);
        actionBar.setDisplayShowTitleEnabled(false);


        setContentView(R.layout.main_activity);




//        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i("MainActivity", "onCreate askPermission");
            mPermissionState = PermissionState.NOT_GRANTED;
            requestCameraPermission();
        } else {
            Log.i("MainActivity", "onCreate not askPermission");
            mPermissionState = PermissionState.GRANTED;
            createDefaultFragments(savedInstanceState);
        }
    }

    public void createDefaultFragments(Bundle savedInstanceState){
        Log.i("MainActivity", "createDefaultFragments");
        if (savedInstanceState == null) {
            Camera.getInstance(this);


            PreviewFragment previewFragment = PreviewFragment.newInstance();
            getFragmentManager().beginTransaction().replace(R.id.preview_fragment, previewFragment).commit();

            Log.i("MainActivity", "after createDefaultFragments");
            mMode = Mode.PREVIEW;

            IdentificationFragment identificationFragment = IdentificationFragment.newInstance();
            getFragmentManager().beginTransaction().replace(R.id.identification_fragment, identificationFragment).commit();

            mMode = Mode.CONTROL;


        }
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
            case R.id.calibration: {
                PreviewFragment previewFragment = (PreviewFragment) getFragmentManager().findFragmentById(R.id.preview_fragment);

                if(previewFragment != null) {
                    previewFragment.clearHighlight();
                }
                FragmentManager fm = getFragmentManager();
                FragmentTransaction ft = fm.beginTransaction();
                if(mMode == Mode.CONTROL) {
                    ControlFragment controlFragment = (ControlFragment) getFragmentManager().findFragmentById(R.id.task_fragment);
                    if(controlFragment != null) {
                        ft.remove(controlFragment);
                    }
                } else {
                    CalibrationFragment calibrationFragment = (CalibrationFragment) getFragmentManager().findFragmentById(R.id.task_fragment);
                    if(calibrationFragment != null) {
                        ft.remove(calibrationFragment);
                    }
                }
                CalibrationFragment calibrationFragment = new CalibrationFragment();
                ft.replace(R.id.task_fragment, calibrationFragment);
                IdentificationFragment identificationFragment = (IdentificationFragment) getFragmentManager().findFragmentById(R.id.identification_fragment);
                if(identificationFragment != null) {
                    ft.remove(identificationFragment);
                }
                ft.commit();
                mMode = Mode.CALIBRATION;
                return true;
            }
            case R.id.control: {
                if(mMode == Mode.CONTROL) {
                    return true;
                }
                FragmentManager fm2 = getFragmentManager();
                FragmentTransaction ft2 = fm2.beginTransaction();
                IdentificationFragment identificationFragment2 = IdentificationFragment.newInstance();
                CalibrationFragment calibrationFragment2 = (CalibrationFragment) getFragmentManager().findFragmentById(R.id.task_fragment);
                if(calibrationFragment2 != null) {
                    ft2.remove(calibrationFragment2);
                }
                ft2.replace(R.id.identification_fragment, identificationFragment2, TAG_CALIBRATION_FRAGMENT);
                ft2.commit();
                mMode = Mode.CONTROL;
                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                              View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
