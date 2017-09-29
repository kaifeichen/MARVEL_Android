package edu.berkeley.cs.sdb.cellmate;

import android.opengl.Matrix;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

/**
 * Created by tongli on 9/28/17.
 */

public class Transform {
    private Mat mData;

    public Transform(float r11, float r12, float r13, float t14, //
                     float r21, float r22, float r23, float t24, //
                     float r31, float r32, float r33, float t34) {
        mData = new Mat(4,4, CvType.CV_32FC1);
        int row = 0, col = 0;
        mData.put(row, col, r11, r12, r13, t14, r21, r22, r23, t24, r31, r32, r33, t34, 0, 0, 0, 1);
    }

    private Transform(Mat data) {
        mData = data;
    }



    public float r11() {
        return (float)mData.get(0,0)[0];
    }

    public float r12() {
        return (float)mData.get(0,1)[0];
    }

    public float r13() {
        return (float)mData.get(0,2)[0];
    }

    public float r21() {
        return (float)mData.get(1,0)[0];
    }

    public float r22() {
        return (float)mData.get(1,1)[0];
    }

    public float r23() {
        return (float)mData.get(1,2)[0];
    }

    public float r31() {
        return (float)mData.get(2,0)[0];
    }

    public float r32() {
        return (float)mData.get(2,1)[0];
    }

    public float r33() {
        return (float)mData.get(2,2)[0];
    }

    public float x() {
        return (float)mData.get(0,3)[0];
    }

    public float y() {
        return (float)mData.get(1,3)[0];
    }

    public float z() {
        return (float)mData.get(2,3)[0];
    }

    public Transform inverse() {
        return new Transform(mData.inv());
    }


}
