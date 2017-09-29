package edu.berkeley.cs.sdb.cellmate;

import android.opengl.Matrix;

/**
 * Created by tongli on 9/28/17.
 */

public class Transform {
    float []mData;
    public Transform(float r11, float r12, float r13, float t14, //
                     float r21, float r22, float r23, float t24, //
                     float r31, float r32, float r33, float t34) {
        mData = new float[16];
        mData[0] = r11;  mData[4] = r12;  mData[8] = r13;   mData[12] = t14;
        mData[1] = r21;  mData[5] = r22;  mData[9] = r23;   mData[13] = t24;
        mData[2] = r31;  mData[6] = r32;  mData[10] = r33;  mData[14] = t34;
        mData[3] = 0;    mData[7] = 0;    mData[11] = 0;    mData[15] = 1;
    }

    private Transform(float [] data) {
        mData = data;
    }

    public float r11() {
        return mData[0];
    }

    public float r12() {
        return mData[4];
    }

    public float r13() {
        return mData[8];
    }

    public float r21() {
        return mData[1];
    }

    public float r22() {
        return mData[5];
    }

    public float r23() {
        return mData[9];
    }

    public float r31() {
        return mData[2];
    }

    public float r32() {
        return mData[6];
    }

    public float r33() {
        return mData[10];
    }

    public float x() {
        return mData[12];
    }

    public float y() {
        return mData[13];
    }

    public float z() {
        return mData[14];
    }

    public Transform inverse() {
        float [] inv = new float[16];
        Matrix.invertM(inv, 0, mData, 0);
        return new Transform(inv);
    }

    public float[] toEigen3f(){
        throw new IllegalStateException("Not yet Implemented");
    }
}
