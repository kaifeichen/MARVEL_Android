package edu.berkeley.cs.sdb.cellmate.data;


import android.opengl.Matrix;



/**
 * Created by tongli on 9/28/17.
 */

public class Transform {

    private float [] mData ;

    public Transform(float r11, float r12, float r13, float t14, //
                     float r21, float r22, float r23, float t24, //
                     float r31, float r32, float r33, float t34) {
        mData = new float[16];
        mData[0] = r11;
        mData[1] = r21;
        mData[2] = r31;
        mData[3] = 0;
        mData[4] = r12;
        mData[5] = r22;
        mData[6] = r32;
        mData[7] = 0;
        mData[8] = r13;
        mData[9] = r23;
        mData[10] = r33;
        mData[11] = 0;
        mData[12] = t14;
        mData[13] = t24;
        mData[14] = t34;
        mData[15] = 1;
    }

    public Transform(float[] data) {
        mData = data;
    }

    public float[] getData() {
        return mData;
    }

    public void print(){
        System.out.print("debug6 " + r11() + " " + r12() + " " + r13() + " " + x() + "\n"
                +"debug6 "+ r21() + " " + r22() + " " + r23() + " " + y() + "\n"
                +"debug6 " + r31() + " " + r32() + " " + r33() + " " + z() + "\n"
                +"debug6 "+ 0     + " " + 0     + " " + 0     + " " + 1   + "\n"+"debug6 \n");

    }

    public void setX(float x){
        mData[12] = x;
    }
    public void setY(float y){
        mData[13] = y;
    }
    public void setZ(float z){
        mData[14] = z;
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
        float[] inversed = new float[16];
        Matrix.invertM(inversed, 0, mData, 0);
        return new Transform(inversed);
    }

    public Transform transpose() {
        float[] transpose = new float[16];
        Matrix.transposeM(transpose, 0, mData, 0);
        return new Transform(transpose);
    }

    public Transform multiply(Transform t) {
        float[] a = fromTransformToOpenGlMatrix(this);
        float[] b = fromTransformToOpenGlMatrix(t);
        float[] c = new float[16];
        Matrix.multiplyMM(c, 0, a, 0, b, 0);
        return fromOpenGlMatrixToTransform(c);
    }

    public float[] multiplyVector3(float[] vec) {
        float[] result = new float[3];

        result[0] = mData[0] * vec[0] + mData[4] * vec[1] + mData[8] * vec[2];
        result[1] = mData[1] * vec[0] + mData[5] * vec[1] + mData[9] * vec[2];
        result[2] = mData[2] * vec[0] + mData[6] * vec[1] + mData[10] * vec[2];

        return result;
    }

    //OpenGLMatrix
    //m[offset +  0] m[offset +  4] m[offset +  8] m[offset + 12]
    //m[offset +  1] m[offset +  5] m[offset +  9] m[offset + 13]
    //m[offset +  2] m[offset +  6] m[offset + 10] m[offset + 14]
    //m[offset +  3] m[offset +  7] m[offset + 11] m[offset + 15]
    private float[] fromTransformToOpenGlMatrix(Transform t) {
        float[] result = new float[16];
        result[0] = t.r11();  result[4] = t.r12();  result[8] = t.r13();  result[12] = t.x();
        result[1] = t.r21();  result[5] = t.r22();  result[9] = t.r23();  result[13] = t.y();
        result[2] = t.r31();  result[6] = t.r32();  result[10] = t.r33(); result[14] = t.z();
        result[3] = 0      ;  result[7] = 0      ;  result[11] = 0      ; result[15] = 1;
        return result;
    }

    private Transform fromOpenGlMatrixToTransform(float[] t) {
        return new Transform(t[0], t[4], t[8], t[12],
                t[1], t[5], t[9], t[13],
                t[2], t[6], t[10],t[14]);
    }

    public double getAngleDiff(Transform other) {
        double result;
        Transform transform = this.transpose().multiply(other);
        double trace = transform.r11() + transform.r22() + transform.r33();
        double rad = Math.acos((trace - 1) / 2);
        result = Math.toDegrees(rad);

        return result;
    }

    public double getPositionDiff(Transform other) {
        double result;
        double dx = this.x() - other.x();
        double dy = this.y() - other.y();
        double dz = this.z() - other.z();
        result = Math.sqrt(dx*dx + dy*dy + dz*dz);
        return result;
    }


}


