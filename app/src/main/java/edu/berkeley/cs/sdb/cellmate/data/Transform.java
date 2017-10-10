package edu.berkeley.cs.sdb.cellmate.data;

import android.opengl.Matrix;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

public class Transform {
    private Mat mData;

    public Transform(float r11, float r12, float r13, float t14, //
                     float r21, float r22, float r23, float t24, //
                     float r31, float r32, float r33, float t34) {
        mData = new Mat(4, 4, CvType.CV_32FC1);
        int row = 0, col = 0;
        mData.put(row, col, r11, r12, r13, t14, r21, r22, r23, t24, r31, r32, r33, t34, 0, 0, 0, 1);
    }

    private Transform(Mat data) {
        mData = data;
    }


    public float r11() {
        return (float) mData.get(0, 0)[0];
    }

    public float r12() {
        return (float) mData.get(0, 1)[0];
    }

    public float r13() {
        return (float) mData.get(0, 2)[0];
    }

    public float r21() {
        return (float) mData.get(1, 0)[0];
    }

    public float r22() {
        return (float) mData.get(1, 1)[0];
    }

    public float r23() {
        return (float) mData.get(1, 2)[0];
    }

    public float r31() {
        return (float) mData.get(2, 0)[0];
    }

    public float r32() {
        return (float) mData.get(2, 1)[0];
    }

    public float r33() {
        return (float) mData.get(2, 2)[0];
    }

    public float x() {
        return (float) mData.get(0, 3)[0];
    }

    public float y() {
        return (float) mData.get(1, 3)[0];
    }

    public float z() {
        return (float) mData.get(2, 3)[0];
    }

    public Transform inverse() {
        return new Transform(mData.inv());
    }

    public Transform multiply(Transform t) {
        float[] a = fromTransformToOpenGlMatrix(this);
        float[] b = fromTransformToOpenGlMatrix(t);
        float[] c = new float[16];
        Matrix.multiplyMM(c, 0, a, 0, b, 0);
        return fromOpenGlMatrixToTransform(c);
    }

    //OpenGLMatrix
    //m[offset +  0] m[offset +  4] m[offset +  8] m[offset + 12]
    //m[offset +  1] m[offset +  5] m[offset +  9] m[offset + 13]
    //m[offset +  2] m[offset +  6] m[offset + 10] m[offset + 14]
    //m[offset +  3] m[offset +  7] m[offset + 11] m[offset + 15]
    private float[] fromTransformToOpenGlMatrix(Transform t) {
        float[] result = new float[16];
        result[0] = t.r11();
        result[4] = t.r12();
        result[8] = t.r13();
        result[12] = t.x();
        result[1] = t.r21();
        result[5] = t.r22();
        result[9] = t.r23();
        result[13] = t.y();
        result[2] = t.r31();
        result[6] = t.r32();
        result[10] = t.r33();
        result[14] = t.z();
        result[3] = 0;
        result[7] = 0;
        result[11] = 0;
        result[15] = 1;
        return result;
    }

    private Transform fromOpenGlMatrixToTransform(float[] t) {
        return new Transform(t[0], t[4], t[8], t[12],
                t[1], t[5], t[9], t[13],
                t[2], t[6], t[10], t[14]);
    }


}
