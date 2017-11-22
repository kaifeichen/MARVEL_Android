package edu.berkeley.cs.sdb.cellmate;

import android.Manifest;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.splunk.mint.Mint;

import org.opencv.core.Point;

import java.util.LinkedList;
import java.util.List;

import edu.berkeley.cs.sdb.cellmate.algo.Localizer.LocTracker;
import edu.berkeley.cs.sdb.cellmate.data.Transform;

public class MainActivity extends AppCompatActivity implements ActivityCompat.OnRequestPermissionsResultCallback, IdentificationFragment.StateCallback, PreviewFragment.StateCallback {
    private static final String MINT_API_KEY = "76da1102";

    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final int REQUEST_EXTERNAL_PERMISSION = 2;
    private static final String TAG_CALIBRATION_FRAGMENT = "CalibrationFragment";
    private static final String FRAGMENT_DIALOG = "dialog";
    //IMU section
    PermissionState mPermissionState;
    Mode mMode = Mode.NULL;
    Bundle mSavedInstanceState;
    int mFirstTime = 0;
    private SensorManager mSensorManager;
    private Sensor mAccSensor;
    private Sensor mGyroSensor;
    private Sensor mRotationSensor;

    private LocTracker mLocTracker;

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i("MainActivity", "Permission granted");
                createDefaultFragments(mSavedInstanceState);
                Camera camera = Camera.getInstance();
                if (!camera.isOpen()) {
                    camera.openCamera();
                }
                mPermissionState = PermissionState.GRANTED;
            } else {
                this.finish();
            }
        } else if (requestCode == REQUEST_EXTERNAL_PERMISSION) {

        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onObjectIdentified(List<String> name, List<Float> x, List<Float> y, List<Float> size) {
        PreviewFragment previewFragment = (PreviewFragment) getFragmentManager().findFragmentById(R.id.preview_fragment);
        if (previewFragment != null) {
            previewFragment.drawHighlight(name, x, y, size);
        }

    }

    @Override
    public void resetLocTrackerLinearMoveCount() {
        mLocTracker.resetLinearMoveCount();
    }

    @Override
    public int getLocTrackerLinearMoveCount() {
        return mLocTracker.getLinearMoveCount();
    }

    @Override
    public void previewOnClicked(boolean isTargeting, String target) {
        if (mMode == Mode.CALIBRATION) {
            return;
        }
        if (isTargeting) {
            FragmentManager fm = getFragmentManager();
            FragmentTransaction ft = fm.beginTransaction();
            ControlFragment controlFragment = (ControlFragment) getFragmentManager().findFragmentById(R.id.task_fragment);
            if (controlFragment != null) {
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
            if (controlFragment != null) {
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

        mLocTracker.reset();

        mSensorManager.registerListener(mLocTracker, mAccSensor, SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(mLocTracker, mRotationSensor, SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(mLocTracker, mGyroSensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        Log.i("MainActivity", "onPause");
        if (mPermissionState == PermissionState.GRANTED) {
            Camera camera = Camera.getInstance();
            if (camera.isOpen()) {
                camera.closeCamera();
            }
        }

        mSensorManager.unregisterListener(mLocTracker);

        super.onPause();
    }

    private void requestCameraPermission() {
        // No explanation needed, we can request the permission.
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_CAMERA_PERMISSION);
    }

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

        //LocTracker need to be create before fragments because Identification fragment need data froms LocTracker
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mAccSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        mRotationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        mGyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        mLocTracker = new LocTracker();


//        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i("MainActivity", "onCreate askPermission");
            mPermissionState = PermissionState.NOT_GRANTED;
            requestCameraPermission();
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_EXTERNAL_PERMISSION);
        } else {
            Log.i("MainActivity", "onCreate not askPermission");
            mPermissionState = PermissionState.GRANTED;
            createDefaultFragments(savedInstanceState);
        }

        System.out.println("OnCreate done");
    }

    public void createDefaultFragments(Bundle savedInstanceState) {
        Log.i("MainActivity", "createDefaultFragments");
        if (savedInstanceState == null) {
            Camera.getInstance(this);


            PreviewFragment previewFragment = PreviewFragment.newInstance();
            getFragmentManager().beginTransaction().replace(R.id.preview_fragment, previewFragment).commit();

            Log.i("MainActivity", "after createDefaultFragments");
            mMode = Mode.PREVIEW;

            IdentificationFragment identificationFragment = IdentificationFragment.newInstance();
            getFragmentManager().beginTransaction().replace(R.id.identification_fragment, identificationFragment).commit();
            mLocTracker.setStateCallback(identificationFragment);

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

                if (previewFragment != null) {
                    previewFragment.clearHighlight();
                }
                FragmentManager fm = getFragmentManager();
                FragmentTransaction ft = fm.beginTransaction();
                if (mMode == Mode.CONTROL) {
                    ControlFragment controlFragment = (ControlFragment) getFragmentManager().findFragmentById(R.id.task_fragment);
                    if (controlFragment != null) {
                        ft.remove(controlFragment);
                    }
                } else {
                    CalibrationFragment calibrationFragment = (CalibrationFragment) getFragmentManager().findFragmentById(R.id.task_fragment);
                    if (calibrationFragment != null) {
                        ft.remove(calibrationFragment);
                    }
                }
                CalibrationFragment calibFragment = new CalibrationFragment();
                ft.replace(R.id.task_fragment, calibFragment);
                IdentificationFragment identFragment = (IdentificationFragment) getFragmentManager().findFragmentById(R.id.identification_fragment);
                if (identFragment != null) {
                    ft.remove(identFragment);
                }
                ft.commit();
                mMode = Mode.CALIBRATION;
                return true;
            }
            case R.id.control: {
                if (mMode == Mode.CONTROL) {
                    return true;
                }
                FragmentManager fm = getFragmentManager();
                FragmentTransaction ft = fm.beginTransaction();
                IdentificationFragment identFragment = IdentificationFragment.newInstance();
                CalibrationFragment calibFragment = (CalibrationFragment) getFragmentManager().findFragmentById(R.id.task_fragment);
                if (calibFragment != null) {
                    ft.remove(calibFragment);
                }
                ft.replace(R.id.identification_fragment, identFragment, TAG_CALIBRATION_FRAGMENT);
                ft.commit();
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

    public LocTracker.ImuPose getNearestPoseAndTime(long time) {
        return mLocTracker.getNearestPoseAndTime(time);
    }

    public LocTracker.ImuPose getNearestPoseAndTimeForOF(long time) {
        return mLocTracker.getNearestPoseAndTimeForOF(time);
    }

    public LocTracker.ImuPose getLatestPoseAndTime() {
        return mLocTracker.getLatestPoseAndTime();
    }

    public enum PermissionState {
        NOT_GRANTED,
        GRANTED
    }

    public enum Mode {
        NULL,
        CALIBRATION,
        CONTROL,
        PREVIEW
    }

}
