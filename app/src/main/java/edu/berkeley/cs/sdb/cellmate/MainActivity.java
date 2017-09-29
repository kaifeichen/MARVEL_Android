package edu.berkeley.cs.sdb.cellmate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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
import android.widget.TextView;

import com.splunk.mint.Mint;

import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.abs;

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

        mLinearAcc = new float[3];

        mLinearAccTimeOld = 0;

        dtsX = new ArrayList<>();
        dtsX.add(0.0f);
        dtsY = new ArrayList<>();
        dtsY.add(0.0f);
        dtsZ = new ArrayList<>();
        dtsZ.add(0.0f);

        mLinearAccsX = new ArrayList<>();
        mLinearAccsX.add(0.0f);
        mLinearAccsY = new ArrayList<>();
        mLinearAccsY.add(0.0f);
        mLinearAccsZ = new ArrayList<>();
        mLinearAccsZ.add(0.0f);

        mVelocitysX = new ArrayList<>();
        mVelocitysX.add(0.0f);
        mVelocitysY = new ArrayList<>();
        mVelocitysY.add(0.0f);
        mVelocitysZ = new ArrayList<>();
        mVelocitysZ.add(0.0f);

        mVelocitysX_cor = new ArrayList<>();
        mVelocitysY_cor = new ArrayList<>();
        mVelocitysZ_cor = new ArrayList<>();

        mPositionsX = new ArrayList<>();
        mPositionsX.add(0.0f);
        mPositionsY = new ArrayList<>();
        mPositionsY.add(0.0f);
        mPositionsZ = new ArrayList<>();
        mPositionsZ.add(0.0f);

        mRotVec = new float[4];
        mRotMat = new float[9];

        mSensorManager.registerListener(mSensorEventListener, mAccSensor, SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(mSensorEventListener, mGyroSensor, SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(mSensorEventListener, mRotationSensor, SensorManager.SENSOR_DELAY_FASTEST);

        mPhaseX = Phase.STANCE;
        mPhaseY = Phase.STANCE;
        mPhaseZ = Phase.STANCE;
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

        mSensorManager.unregisterListener(mSensorEventListener);

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


        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mAccSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        mGyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        mRotationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
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


    //IMU section
    private static final long NS2S = 1000000000;
    private static final float ACC_THRESHOLD = 0.5f;

    private SensorManager mSensorManager;
    private Sensor mAccSensor;
    private Sensor mGyroSensor;
    private Sensor mRotationSensor;

    private long mLinearAccTimeOld;
    private ArrayList<Float> dtsX;
    private ArrayList<Float> dtsY;
    private ArrayList<Float> dtsZ;

    private ArrayList<Float> mLinearAccsX;
    private ArrayList<Float> mLinearAccsY;
    private ArrayList<Float> mLinearAccsZ;

    private long mLinearAccTime;
    private float[] mLinearAcc;

    private float[] mRotVec;
    private float[] mRotMat;

    private ArrayList<Float> mVelocitysX;
    private ArrayList<Float> mVelocitysY;
    private ArrayList<Float> mVelocitysZ;

    private ArrayList<Float> mPositionsX;
    private ArrayList<Float> mPositionsY;
    private ArrayList<Float> mPositionsZ;

    private float mPositionX = 0;
    private float mPositionY = 0;
    private float mPositionZ = 0;

    private ArrayList<Float> mVelocitysX_cor;
    private ArrayList<Float> mVelocitysY_cor;
    private ArrayList<Float> mVelocitysZ_cor;



    enum Phase {
        STANCE,
        Moving
    }

    boolean mFirst = true;
    private Handler mHandler;

    int mFirstTime = 0;

    private Phase mPhaseX;
    private Phase mPhaseY;
    private Phase mPhaseZ;

    private int zeroCountX = 0;
    private int zeroCountY = 0;
    private int zeroCountZ = 0;
    private int confidentCount = 100;

    private int first100 = 100;

    private SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public final void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Do something here if sensor accuracy changes.
        }

        @Override
        public final void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                //Makesure ignore first 100 readings, because we observed that first few readings is really off when the app just started
                if(first100-- > 0) {
                    return;
                }

                mLinearAccTimeOld = mLinearAccTime;
                mLinearAccTime = System.nanoTime();

                if (mFirst) {
                    mFirst = false;
                    return;
                }

                // Android reuses events, so you probably want a copy
                System.arraycopy(event.values, 0, mLinearAcc, 0, event.values.length);

                mLinearAcc = RotateVec(mRotMat, mLinearAcc);

                for (int i = 0; i < mLinearAcc.length; i++) {
                    if (abs(mLinearAcc[i]) < ACC_THRESHOLD) {
                        mLinearAcc[i] = 0.0f;
                    }
                }

                float dt = (float) (mLinearAccTime - mLinearAccTimeOld) / NS2S;

                updateXDirection(dt);
                updateYDirection(dt);
                updateZDirection(dt);
            } else if (event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                // Android reuses events, so you probably want a copy
                System.arraycopy(event.values, 0, mRotVec, 0, event.values.length);

                SensorManager.getRotationMatrixFromVector(mRotMat, mRotVec);

//                if(mFirstTime < 100) {
//                    Log.d("log", mRotMat[0] + " " + mRotMat[1] + " " + mRotMat[2] + "\n"
//                            +mRotMat[3] + " " + mRotMat[4] + " " + mRotMat[5] + "\n"
//                            +mRotMat[6] + " " + mRotMat[7] + " " + mRotMat[8] + "\n");
//                    mFirstTime += 1;
//                }

            }
        }
    };
    float timeInterval(ArrayList<Float> dts, int start, int end) {
        float total = 0.0f;
        for(int i = start; i <= end; i++) {
            total += dts.get(i);
        }
        return total;
    }

    private float[] RotateVec(float[] rotMat, float[] vec) {
        float[] result = new float[3];

        result[0] = rotMat[0] * vec[0] + rotMat[1] * vec[1] + rotMat[2] * vec[2];
        result[1] = rotMat[3] * vec[0] + rotMat[4] * vec[1] + rotMat[5] * vec[2];
        result[2] = rotMat[6] * vec[0] + rotMat[7] * vec[1] + rotMat[8] * vec[2];

        return result;
    }

    private int findFirstZeroAtTail(ArrayList<Float> target) {
        if(target.get(target.size()-1) != 0) {
            throw new IllegalStateException("findFirstZeroAtTail input invaild");
        }
        for(int i = target.size() - 1; i >= 0; i--) {
            if(target.get(i) != 0.0f) {
                return i+1;
            }
        }
        throw new IllegalStateException("findFirstZeroAtTail input invaild: nothing found");
    }

    private int findFirstNoneZeroAtHead(ArrayList<Float> target) {
        if(target.get(0) != 0) {
            throw new IllegalStateException("findFirstNoneZeroAtHead input invaild");
        }
        for(int i = 0; i < target.size(); i++) {
            if(target.get(i) != 0.0f) {
                return i;
            }
        }
        throw new IllegalStateException("findFirstNoneZeroAtHead input invaild: nothing found");
    }

    //Assuming the accs array is consists of {stance phase 0s, Moving with nonozeros, 100 zeros of next stance phase or paused}
    private void doCorrection(ArrayList<Float> accs, ArrayList<Float> vels, ArrayList<Float> velsCorrected, ArrayList<Float> positions, ArrayList<Float> dts) {
        if(accs.size() != vels.size() || accs.size() != dts.size()) {
            throw new IllegalStateException("doCorrection illegal state");
        }

        int newStancePhasesStart = findFirstZeroAtTail(accs);
        int oldStancePhaseEnd = findFirstNoneZeroAtHead(accs);

        float prePosition = positions.get(positions.size()-1);
        //update stancePhase velocity to zero
        for(int j = 0; j < oldStancePhaseEnd; j++) {
            velsCorrected.add(0.0f);
            positions.add(prePosition);
        }

        //correct movingPhase velocity
        float residualValue = vels.get(newStancePhasesStart);
        float movingTotalTime = timeInterval(dts ,oldStancePhaseEnd, newStancePhasesStart);
        for(int j = oldStancePhaseEnd; j < newStancePhasesStart; j++) {
            float movingCurrentTime = timeInterval(dts, oldStancePhaseEnd, j);
            float downValue = residualValue * (movingCurrentTime/movingTotalTime);
//            System.out.println(downValue);
            velsCorrected.add(vels.get(j) - downValue);
            prePosition = prePosition + velsCorrected.get(j-1) * dts.get(j) + (velsCorrected.get(j) - velsCorrected.get(j-1)) * dts.get(j) / 2;
            positions.add(prePosition);
        }


        //newAccs should be just 100 or less of zeros
        ArrayList<Float> newAccs = new ArrayList<Float>(accs.subList(newStancePhasesStart, accs.size()));
        accs.clear();
        accs.addAll(newAccs);
        vels.clear();
        vels.addAll(newAccs);
        ArrayList<Float> newDts = new ArrayList<Float>(dts.subList(newStancePhasesStart, dts.size()));
        dts.clear();
        dts.addAll(newDts);
        velsCorrected.clear();
    }

    private void updateXDirection(float dt) {
        dtsX.add(dt);
        float preVel = mVelocitysX.get(mVelocitysX.size()-1);
        float preAcc = mLinearAccsX.get(mLinearAccsX.size()-1);
        float curVel = preVel + preAcc * dt + (mLinearAcc[0] - preVel) * dt / 2;
        mLinearAccsX.add(mLinearAcc[0]);
        mVelocitysX.add(curVel);
        mPositionX = mPositionX + preVel * dt + (curVel - preVel) * dt / 2;
        if (mPhaseX == Phase.STANCE) {
            if(mLinearAcc[0] != 0.0f) {
                mPhaseX = Phase.Moving;
            }
        } else if(mPhaseX == Phase.Moving) {
            if(mLinearAcc[0] == 0.0f) {
                zeroCountX += 1;
                if(zeroCountX >= confidentCount) {
                    zeroCountX = 0;
                    mPhaseX = Phase.STANCE;
                    doCorrection(mLinearAccsX, mVelocitysX, mVelocitysX_cor, mPositionsX, dtsX);
                    mPositionX = mPositionsX.get(mPositionsX.size()-1);
                }
            }
        } else {
            throw new IllegalStateException("onSensorChanged in illegal State");
        }
    }

    private void updateYDirection(float dt) {
        dtsY.add(dt);
        float preVel = mVelocitysY.get(mVelocitysY.size()-1);
        float preAcc = mLinearAccsY.get(mLinearAccsY.size()-1);
        float curVel = preVel + preAcc * dt + (mLinearAcc[1] - preVel) * dt / 2;
        mLinearAccsY.add(mLinearAcc[1]);
        mVelocitysY.add(curVel);
        mPositionY = mPositionY + preVel * dt + (curVel - preVel) * dt / 2;
        if(mPhaseY == Phase.STANCE) {
            if(mLinearAcc[1] != 0.0f) {
                mPhaseY = Phase.Moving;
            }
        } else if(mPhaseY == Phase.Moving) {
            if(mLinearAcc[1] == 0.0f) {
                zeroCountY += 1;
                if(zeroCountY >= confidentCount) {
                    zeroCountY = 0;
                    mPhaseY = Phase.STANCE;
                    doCorrection(mLinearAccsY, mVelocitysY, mVelocitysY_cor, mPositionsY, dtsY);
                    mPositionY = mPositionsY.get(mPositionsY.size()-1);
                }
            }
        } else {
            throw new IllegalStateException("onSensorChanged in illegal State");
        }
    }

    private void updateZDirection(float dt) {
        dtsZ.add(dt);
        float preVel = mVelocitysZ.get(mVelocitysZ.size()-1);
        float preAcc = mLinearAccsZ.get(mLinearAccsZ.size()-1);
        float curVel = preVel + preAcc * dt + (mLinearAcc[2] - preVel) * dt / 2;
        mLinearAccsZ.add(mLinearAcc[2]);
        mVelocitysZ.add(curVel);
        mPositionZ = mPositionZ + preVel * dt + (curVel - preVel) * dt / 2;
        if (mPhaseZ == Phase.STANCE) {
            if(mLinearAcc[2] != 0.0f) {
                mPhaseZ = Phase.Moving;
            }
        } else if(mPhaseZ == Phase.Moving) {
            if(mLinearAcc[2] == 0.0f) {
                zeroCountZ += 1;
                if(zeroCountZ >= confidentCount) {
                    zeroCountZ = 0;
                    mPhaseZ = Phase.STANCE;
                    doCorrection(mLinearAccsZ, mVelocitysZ, mVelocitysZ_cor, mPositionsZ, dtsZ);
                    mPositionZ = mPositionsZ.get(mPositionsZ.size()-1);
                }
            }
        } else {
            throw new IllegalStateException("onSensorChanged in illegal State");
        }
    }

    public Transform getPose() {
        return new Transform(mRotMat[0],  mRotMat[1],  mRotMat[2],  mPositionX,
                             mRotMat[3],  mRotMat[4],  mRotMat[5],  mPositionY,
                             mRotMat[6],  mRotMat[7],  mRotMat[8],  mPositionZ);
    }
}
