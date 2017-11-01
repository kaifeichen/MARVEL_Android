package edu.berkeley.cs.sdb.cellmate.data;

/**
 * Created by tongli on 10/31/17.
 */

public class KeyFrame {
    private Transform mPose;
    private int mNumOfEdges;
    private byte[] mData;
    private long mTime;

    public KeyFrame(long time, Transform Pose, int NumOfEdges, byte[] Data) {
        mTime = time;
        mPose = Pose;
        mNumOfEdges = NumOfEdges;
        mData = Data;
    }

    public void setPose(Transform mPose) {
        this.mPose = mPose;
    }

    public void setNumOfEdges(int mNumOfEdges) {
        this.mNumOfEdges = mNumOfEdges;
    }

    public void setData(byte[] mData) {
        this.mData = mData;
    }

    public void setTime(long mTime) {
        this.mTime = mTime;
    }

    public Transform getPose() {
        return mPose;
    }

    public int getNumOfEdges() {
        return mNumOfEdges;
    }

    public byte[] getData() {
        return mData;
    }

    public long getTime() {
        return mTime;
    }
}
