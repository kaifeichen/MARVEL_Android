package edu.berkeley.cs.sdb.cellmate.algo.Localizer;

import android.app.Fragment;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import edu.berkeley.cs.sdb.cellmate.data.Transform;

import static java.lang.Math.abs;

public class LocTracker implements SensorEventListener {
    private static final float ACC_THRESHOLD = 0.5f;
    private static final long NS2S = 1000000000;
    private static final int IGNORE_EVENT_NUM = 100;
    private static final int STANCE_THRES = 100;

    private List<Long> mTimestamps;
    private List<float[]> mLinearAccs;
    private float[] mLinearAcc;
    private float[] mRotVec;
    private float[] mRotMat;
    private List<float[]> mVelocities;
    private List<float[]> mPositions;
    private float[] mPosition;
    private int zeroCount;
    private int eventCount = 0;

    StateCallback mStateCallback = null;
    private List<ImuPose> posesRecord;



    public LocTracker() {
        posesRecord = new ArrayList<>();
        mTimestamps = new ArrayList<>();
        mLinearAccs = new ArrayList<>();
        mVelocities = new ArrayList<>();
        mPositions = new ArrayList<>();
        mPosition = new float[3];

        reset();
    }

    private static float[] rotateVec(float[] rotMat, float[] vec) {
        float[] result = new float[3];

        result[0] = rotMat[0] * vec[0] + rotMat[1] * vec[1] + rotMat[2] * vec[2];
        result[1] = rotMat[3] * vec[0] + rotMat[4] * vec[1] + rotMat[5] * vec[2];
        result[2] = rotMat[6] * vec[0] + rotMat[7] * vec[1] + rotMat[8] * vec[2];

        return result;
    }

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do something here if sensor accuracy changes.
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        eventCount++;
        // ignore first IGNORE_EVENT_NUM readings, because we observed that first few readings is really off when the app just started
        if (eventCount < IGNORE_EVENT_NUM) {
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            Long time = System.nanoTime();
            // Android reuses events, so you probably want a copy
            System.arraycopy(event.values, 0, mLinearAcc, 0, event.values.length);

            mTimestamps.add(time);
            mLinearAcc = rotateVec(mRotMat, mLinearAcc);

            for (int i = 0; i < mLinearAcc.length; i++) {
                if (abs(mLinearAcc[i]) < ACC_THRESHOLD) {
                    mLinearAcc[i] = 0.0f;
                }
            }

            mLinearAccs.add(mLinearAcc);

            updateIMU();
        } else if (event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            // Android reuses events, so you probably want a copy
            System.arraycopy(event.values, 0, mRotVec, 0, event.values.length);

            SensorManager.getRotationMatrixFromVector(mRotMat, mRotVec);
        }
    }

    public class ImuPose {
        public long time;
        public Transform pose;
        public ImuPose(long t, Transform p) {
            time = t;
            pose = p;
        }
        public float[] getPosition() {
            float[] position = new float[3];
            position[0] = pose.x();
            position[1] = pose.y();
            position[2] = pose.z();
            return position;
        }
        public void setPosition(float[] position) {
            pose.setX(position[0]);
            pose.setY(position[1]);
            pose.setZ(position[2]);
        }
    }

    public void reset() {
        mTimestamps.clear();
        mLinearAccs.clear();
        mVelocities.clear();
        posesRecord.clear();

        mLinearAcc = new float[4];
        mRotVec = new float[4];
        mRotMat = new float[9];

        float[] velocitiy = new float[4];
        mTimestamps.add(System.nanoTime());
        mLinearAccs.add(mLinearAcc);
        mVelocities.add(velocitiy);
        ImuPose currentPose = new ImuPose(System.currentTimeMillis(),
                new Transform(mRotMat[0],mRotMat[1],mRotMat[2],mPosition[0],
                              mRotMat[3],mRotMat[4],mRotMat[5],mPosition[1],
                              mRotMat[6],mRotMat[7],mRotMat[8],mPosition[2]));
        posesRecord.add(currentPose);
        zeroCount = 0;
    }

    public ImuPose getLatestPoseAndTime() {
        return posesRecord.get(posesRecord.size()-1);
    }

    private void doCorrection() {
        int residualPos = mLinearAccs.size() - STANCE_THRES - 1;
        float[] residualVel = mVelocities.get(residualPos);
        long t0 = mTimestamps.get(0);
        long movingTotalTime = mTimestamps.get(residualPos) - t0;
        float[] downValue = new float[3];
        float[] mPosition = posesRecord.get(0).getPosition();
        float[] preVelocity = new float[]{0,0,0};
        for(int i = 1; i <= residualPos; i++) {
            long movingCurrentTime = mTimestamps.get(i) - t0;
            long dt = mTimestamps.get(i) - mTimestamps.get(i-1);
            posesRecord.get(i);
            float[] currentVelocity = mVelocities.get(i);
            for(int j = 0; j < 3; j++) {
                downValue[j] = residualVel[j] * (movingCurrentTime/movingTotalTime);
                currentVelocity[j] = currentVelocity[j] - downValue[j];
                mPosition[j] = mPosition[j] + preVelocity[j] * dt + (currentVelocity[j] - preVelocity[j]) * dt / 2;
            }
            posesRecord.get(i).setPosition(mPosition);
            preVelocity = currentVelocity;
        }
        for(int i = residualPos+1; i < posesRecord.size(); i++) {
            posesRecord.get(i).setPosition(mPosition);
        }


        if(mStateCallback != null) {
            mStateCallback.onStancePhaseCorrection(posesRecord);
        }
    }

    private void updateIMU() {
        if (inStancePhase()) {
            doCorrection();
            reset();
            return;
        }


        Long lastTimestamp = mTimestamps.get(mTimestamps.size() - 2);
        Long curTimestamp = mTimestamps.get(mTimestamps.size() - 1);
        float[] lastAcc = mLinearAccs.get(mLinearAccs.size() - 2);
        float[] curAcc = mLinearAccs.get(mLinearAccs.size() - 1);
        float[] lastVel = mVelocities.get(mVelocities.size() - 1);

        // get current velocity and position
        float deltaTime = (float) (curTimestamp - lastTimestamp) / NS2S;

        float[] curVel = new float[3];
        for (int i = 0; i < 3; i++) {
            curVel[i] = lastVel[i] + (curAcc[i] + lastAcc[i]) * deltaTime / 2;
            mPosition[i] = mPosition[i] + (curVel[i] + lastVel[i]) * deltaTime / 2;
        }
        mVelocities.add(curVel);
        Transform currentPose = new Transform(mRotMat[0],mRotMat[1],mRotMat[2],mPosition[0],
                                              mRotMat[3],mRotMat[4],mRotMat[5],mPosition[1],
                                              mRotMat[6],mRotMat[7],mRotMat[8],mPosition[2]);
        posesRecord.add(new ImuPose(System.currentTimeMillis(), currentPose));

    }

    private boolean inStancePhase() {
        float[] curAcc = mLinearAccs.get(mLinearAccs.size() - 1);

        // check stance phase
        if (curAcc[0] == 0.0f && curAcc[1] == 0.0f && curAcc[2] == 0.0f) {
            zeroCount += 1;
            if (zeroCount >= STANCE_THRES) {
                return true;
            }
        } else {
            zeroCount = 0;
        }

        return false;
    }

    public interface StateCallback {
        void onStancePhaseCorrection(List<ImuPose> posesRecord);
    }

    public void setStateCallback(Fragment fragment) {
        mStateCallback = (StateCallback) fragment;
    }
}
