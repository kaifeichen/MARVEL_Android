package edu.berkeley.cs.sdb.cellmate.algo.Localizer;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.ArrayList;
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


    public LocTracker() {
        mTimestamps = new ArrayList<>();
        mLinearAccs = new ArrayList<>();
        mVelocities = new ArrayList<>();
        mPositions = new ArrayList<>();

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

            updatePosition();
        } else if (event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            // Android reuses events, so you probably want a copy
            System.arraycopy(event.values, 0, mRotVec, 0, event.values.length);

            SensorManager.getRotationMatrixFromVector(mRotMat, mRotVec);
        }
    }

    public void reset() {
        mTimestamps.clear();
        mLinearAccs.clear();
        mVelocities.clear();
        mPositions.clear();

        mLinearAcc = new float[4];
        mRotVec = new float[4];
        mRotMat = new float[9];

        zeroCount = 0;
    }

    public Transform getPose() {
        return new Transform(mRotMat[0], mRotMat[1], mRotMat[2], mPosition[0],
                mRotMat[3], mRotMat[4], mRotMat[5], mPosition[1],
                mRotMat[6], mRotMat[7], mRotMat[8], mPosition[2]);
    }

    private void updatePosition() {
        if (inStancePhase()) {
            reset();
            return;
        }

        if (mTimestamps.size() < 2) {
            mVelocities.add(new float[]{0, 0, 0});
            mPosition = new float[]{0, 0, 0};
        } else {
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
        }
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
}
