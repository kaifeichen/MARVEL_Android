package edu.berkeley.cs.sdb.cellmate.algo.Localizer;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.ArrayList;
import java.util.List;

import edu.berkeley.cs.sdb.cellmate.data.Transform;

import static java.lang.Math.abs;

public class Localizer implements SensorEventListener {
    private static final float ACC_THRESHOLD = 0.5f;
    private static final long NS2S = 1000000000;

    private long mLinearAccTimeOld;
    private List<Float> dtsX;
    private List<Float> dtsY;
    private List<Float> dtsZ;
    private List<Float> mLinearAccsX;
    private List<Float> mLinearAccsY;
    private List<Float> mLinearAccsZ;
    private long mLinearAccTime;
    private float[] mLinearAcc;
    private float[] mRotVec;

    private float[] mRotMat;
    private List<Float> mVelocitysX;
    private List<Float> mVelocitysY;
    private List<Float> mVelocitysZ;
    private List<Float> mPositionsX;
    private List<Float> mPositionsY;
    private List<Float> mPositionsZ;
    private float mPositionX = 0;
    private float mPositionY = 0;
    private float mPositionZ = 0;
    private List<Float> mVelocitysX_cor;
    private List<Float> mVelocitysY_cor;
    private List<Float> mVelocitysZ_cor;
    private Localizer.Phase mPhaseX;
    private Localizer.Phase mPhaseY;
    private Localizer.Phase mPhaseZ;
    private int zeroCountX = 0;
    private int zeroCountY = 0;
    private int zeroCountZ = 0;

    private int confidentCount = 100;
    private int first100 = 100;

    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do something here if sensor accuracy changes.
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            Long time = System.nanoTime();

            //Makesure ignore first 100 readings, because we observed that first few readings is really off when the app just started
            if (first100-- > 0) {
                return;
            }

            mLinearAccTimeOld = mLinearAccTime;
            mLinearAccTime = time;

            // Android reuses events, so you probably want a copy
            System.arraycopy(event.values, 0, mLinearAcc, 0, event.values.length);

            mLinearAcc = rotateVec(mRotMat, mLinearAcc);

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
        }
    }

    public Localizer() {
        mLinearAcc = new float[3];
        dtsX = new ArrayList<>();
        dtsY = new ArrayList<>();
        dtsZ = new ArrayList<>();
        mLinearAccsX = new ArrayList<>();
        mLinearAccsY = new ArrayList<>();
        mLinearAccsZ = new ArrayList<>();

        mVelocitysX = new ArrayList<>();
        mVelocitysY = new ArrayList<>();
        mVelocitysZ = new ArrayList<>();

        mVelocitysX_cor = new ArrayList<>();
        mVelocitysY_cor = new ArrayList<>();
        mVelocitysZ_cor = new ArrayList<>();

        mPositionsX = new ArrayList<>();
        mPositionsY = new ArrayList<>();
        mPositionsZ = new ArrayList<>();

        reset();
    }

    public void reset() {
        mLinearAccTimeOld = 0;

        dtsX.clear();
        dtsX.add(0.0f);
        dtsY.clear();
        dtsY.add(0.0f);
        dtsZ.clear();
        dtsZ.add(0.0f);

        mLinearAccsX.clear();
        mLinearAccsX.add(0.0f);
        mLinearAccsY.clear();
        mLinearAccsY.add(0.0f);
        mLinearAccsZ.clear();
        mLinearAccsZ.add(0.0f);

        mVelocitysX.clear();
        mVelocitysX.add(0.0f);
        mVelocitysY.clear();
        mVelocitysY.add(0.0f);
        mVelocitysZ.clear();
        mVelocitysZ.add(0.0f);

        mVelocitysX_cor.clear();
        mVelocitysY_cor.clear();
        mVelocitysZ_cor.clear();

        mPositionsX.clear();
        mPositionsX.add(0.0f);
        mPositionsY.clear();
        mPositionsY.add(0.0f);
        mPositionsZ.clear();
        mPositionsZ.add(0.0f);

        mRotVec = new float[4];
        mRotMat = new float[9];

        mPhaseX = Phase.STANCE;
        mPhaseY = Phase.STANCE;
        mPhaseZ = Phase.STANCE;
    }

    public Transform getPose() {
        return new Transform(mRotMat[0], mRotMat[1], mRotMat[2], mPositionX,
                mRotMat[3], mRotMat[4], mRotMat[5], mPositionY,
                mRotMat[6], mRotMat[7], mRotMat[8], mPositionZ);
    }

    private void updateXDirection(float dt) {
        dtsX.add(dt);
        float preVel = mVelocitysX.get(mVelocitysX.size() - 1);
        float preAcc = mLinearAccsX.get(mLinearAccsX.size() - 1);
        float curVel = preVel + preAcc * dt + (mLinearAcc[0] - preVel) * dt / 2;
        mLinearAccsX.add(mLinearAcc[0]);
        mVelocitysX.add(curVel);
        mPositionX = mPositionX + preVel * dt + (curVel - preVel) * dt / 2;
        if (mPhaseX == Phase.STANCE) {
            if (mLinearAcc[0] != 0.0f) {
                mPhaseX = Phase.MOVING;
            }
        } else if (mPhaseX == Phase.MOVING) {
            if (mLinearAcc[0] == 0.0f) {
                zeroCountX += 1;
                if (zeroCountX >= confidentCount) {
                    zeroCountX = 0;
                    mPhaseX = Phase.STANCE;
                    doCorrection(mLinearAccsX, mVelocitysX, mVelocitysX_cor, mPositionsX, dtsX);
                    mPositionX = mPositionsX.get(mPositionsX.size() - 1);
                }
            }
        } else {
            throw new IllegalStateException("onSensorChanged in illegal State");
        }
    }

    private void updateYDirection(float dt) {
        dtsY.add(dt);
        float preVel = mVelocitysY.get(mVelocitysY.size() - 1);
        float preAcc = mLinearAccsY.get(mLinearAccsY.size() - 1);
        float curVel = preVel + preAcc * dt + (mLinearAcc[1] - preVel) * dt / 2;
        mLinearAccsY.add(mLinearAcc[1]);
        mVelocitysY.add(curVel);
        mPositionY = mPositionY + preVel * dt + (curVel - preVel) * dt / 2;
        if (mPhaseY == Phase.STANCE) {
            if (mLinearAcc[1] != 0.0f) {
                mPhaseY = Phase.MOVING;
            }
        } else if (mPhaseY == Phase.MOVING) {
            if (mLinearAcc[1] == 0.0f) {
                zeroCountY += 1;
                if (zeroCountY >= confidentCount) {
                    zeroCountY = 0;
                    mPhaseY = Phase.STANCE;
                    doCorrection(mLinearAccsY, mVelocitysY, mVelocitysY_cor, mPositionsY, dtsY);
                    mPositionY = mPositionsY.get(mPositionsY.size() - 1);
                }
            }
        } else {
            throw new IllegalStateException("onSensorChanged in illegal State");
        }
    }

    private void updateZDirection(float dt) {
        dtsZ.add(dt);
        float preVel = mVelocitysZ.get(mVelocitysZ.size() - 1);
        float preAcc = mLinearAccsZ.get(mLinearAccsZ.size() - 1);
        float curVel = preVel + preAcc * dt + (mLinearAcc[2] - preVel) * dt / 2;
        mLinearAccsZ.add(mLinearAcc[2]);
        mVelocitysZ.add(curVel);
        mPositionZ = mPositionZ + preVel * dt + (curVel - preVel) * dt / 2;
        if (mPhaseZ == Phase.STANCE) {
            if (mLinearAcc[2] != 0.0f) {
                mPhaseZ = Phase.MOVING;
            }
        } else if (mPhaseZ == Phase.MOVING) {
            if (mLinearAcc[2] == 0.0f) {
                zeroCountZ += 1;
                if (zeroCountZ >= confidentCount) {
                    zeroCountZ = 0;
                    mPhaseZ = Phase.STANCE;
                    doCorrection(mLinearAccsZ, mVelocitysZ, mVelocitysZ_cor, mPositionsZ, dtsZ);
                    mPositionZ = mPositionsZ.get(mPositionsZ.size() - 1);
                }
            }
        } else {
            throw new IllegalStateException("onSensorChanged in illegal State");
        }
    }


    private float[] rotateVec(float[] rotMat, float[] vec) {
        float[] result = new float[3];

        result[0] = rotMat[0] * vec[0] + rotMat[1] * vec[1] + rotMat[2] * vec[2];
        result[1] = rotMat[3] * vec[0] + rotMat[4] * vec[1] + rotMat[5] * vec[2];
        result[2] = rotMat[6] * vec[0] + rotMat[7] * vec[1] + rotMat[8] * vec[2];

        return result;
    }


    //Assuming the accs array is consists of {stance phase 0s, MOVING with nonozeros, 100 zeros of next stance phase or paused}
    private void doCorrection(List<Float> accs, List<Float> vels, List<Float> velsCorrected, List<Float> positions, List<Float> dts) {
        if (accs.size() != vels.size() || accs.size() != dts.size()) {
            throw new IllegalStateException("doCorrection illegal state");
        }

        int newStancePhasesStart = findFirstZeroAtTail(accs);
        int oldStancePhaseEnd = findFirstNoneZeroAtHead(accs);

        float prePosition = positions.get(positions.size() - 1);
        //update stancePhase velocity to zero
        for (int j = 0; j < oldStancePhaseEnd; j++) {
            velsCorrected.add(0.0f);
            positions.add(prePosition);
        }

        //correct movingPhase velocity
        float residualValue = vels.get(newStancePhasesStart);
        float movingTotalTime = timeInterval(dts, oldStancePhaseEnd, newStancePhasesStart);
        for (int j = oldStancePhaseEnd; j < newStancePhasesStart; j++) {
            float movingCurrentTime = timeInterval(dts, oldStancePhaseEnd, j);
            float downValue = residualValue * (movingCurrentTime / movingTotalTime);
//            System.out.println(downValue);
            velsCorrected.add(vels.get(j) - downValue);
            prePosition = prePosition + velsCorrected.get(j - 1) * dts.get(j) + (velsCorrected.get(j) - velsCorrected.get(j - 1)) * dts.get(j) / 2;
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



    float timeInterval(List<Float> dts, int start, int end) {
        float total = 0.0f;
        for (int i = start; i <= end; i++) {
            total += dts.get(i);
        }
        return total;
    }


    private int findFirstZeroAtTail(List<Float> target) {
        if (target.get(target.size() - 1) != 0) {
            throw new IllegalStateException("findFirstZeroAtTail input invaild");
        }
        for (int i = target.size() - 1; i >= 0; i--) {
            if (target.get(i) != 0.0f) {
                return i + 1;
            }
        }
        throw new IllegalStateException("findFirstZeroAtTail input invaild: nothing found");
    }

    private int findFirstNoneZeroAtHead(List<Float> target) {
        if (target.get(0) != 0) {
            throw new IllegalStateException("findFirstNoneZeroAtHead input invaild");
        }
        for (int i = 0; i < target.size(); i++) {
            if (target.get(i) != 0.0f) {
                return i;
            }
        }
        throw new IllegalStateException("findFirstNoneZeroAtHead input invaild: nothing found");
    }


    enum Phase {
        UNKNOWN,
        STANCE,
        MOVING
    }
}
