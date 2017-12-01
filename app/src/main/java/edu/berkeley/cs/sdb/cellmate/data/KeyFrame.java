package edu.berkeley.cs.sdb.cellmate.data;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import edu.berkeley.cs.sdb.cellmate.algo.Localizer.LocTracker;

/**
 * Created by tongli on 10/31/17.
 */

public class KeyFrame {
    private long mFrameTime;
    private LocTracker.ImuPose mImuPose;
    private int mNumOfEdges = -1;
    private float mGyroNorm = -1;
    private byte[] mData;
    int mHeight;
    int mWidth;
    private int mRotateClockwiseAngle;
    private int mGyroRank;
    private int mFeatureRank;
    private int mRank = -1;
    private final float GYRO_NORM_UPPER_LIMIT = 0.5f;
    private final int NUM_EDGES_LOWER_LIMIT = 500000;

    public KeyFrame(long time, LocTracker.ImuPose imuPose, byte[] Data, int height, int width,  int rotateClockwiseAngle) {
        mFrameTime = time;
        mImuPose = imuPose;
        mData = Data;
        mRotateClockwiseAngle = rotateClockwiseAngle;

        mHeight = height;
        mWidth = width;
        //Calculate mGyroNorm
        float[] gyroReading = mImuPose.gyroReading;
        mGyroNorm = (float)Math.sqrt(Math.pow(gyroReading[0], 2)+Math.pow(gyroReading[1], 2)+ Math.pow(gyroReading[2], 2));

        //Calculate mNumOfEdges
//        Mat src = Imgcodecs.imdecode(new MatOfByte(mData), Imgcodecs.CV_LOAD_IMAGE_UNCHANGED);
//        Mat src_gray = new Mat();
//        Imgproc.cvtColor(src, src_gray, Imgproc.COLOR_BGR2GRAY);

//        cv::Mat img(height, width, CV_8UC1, &(*data)[0]);
//        Mat src_gray = new Mat(height, width, mData);

        Mat src_gray = new Mat(height, width, CvType.CV_8UC1);
        src_gray.put(0, 0, mData);

        Mat grad_x = new Mat();
        Mat grad_y = new Mat();
        Imgproc.Sobel(src_gray, grad_x, 3, 1, 0, 1, 1, 0);
        Imgproc.Sobel(src_gray, grad_y, 3, 0, 1, 1, 1, 0);
        Mat abs_grad_x = new Mat();
        Mat abs_grad_y = new Mat();
        Core.convertScaleAbs(grad_x, abs_grad_x);
        Core.convertScaleAbs(grad_y, abs_grad_y);
        Mat grad = new Mat();
        Core.addWeighted(abs_grad_x, 0.5, abs_grad_y, 0.5, 0, grad);
        mNumOfEdges = (int)Core.sumElems(grad).val[0];
    }

    public void setImuPose(LocTracker.ImuPose imuPose) {
        mImuPose = imuPose;
    }

    public void setNumOfEdges(int numOfEdges) {
        mNumOfEdges = numOfEdges;
    }

    public void setData(byte[] data) {
        mData = data;
    }

    public void setmRotateClockwiseAngle(int mRotateClockwiseAngle) {
        this.mRotateClockwiseAngle = mRotateClockwiseAngle;
    }

    public LocTracker.ImuPose getImuPose() {
        return mImuPose;
    }

    public float getGyroNorm() {
        return mGyroNorm;
    }
    public int getNumOfEdges() {
        return mNumOfEdges;
    }

    public byte[] getData() {
        return mData;
    }


    public int getmRotateClockwiseAngle() {
        return mRotateClockwiseAngle;
    }

    public void setGyroRank(int rank) {
        mGyroRank = rank;
    }

    public void setFeatureRank(int rank) {
        mFeatureRank = rank;
    }

    public int getRank() {
        if(mRank == -1) {
            return mGyroRank + mFeatureRank;
        }
        return mRank;
    }

    public int setRank(int rank) {
        return mRank = rank;
    }

    public void setFrameTime(long time) {
        mFrameTime = time;
    }

    public long getFrameTime() {
        return mFrameTime;
    }

    public double angleDiffInDegree(KeyFrame other) {
        float[] zVec = {0,0,-1};
        float[] vec0 = this.getImuPose().pose.multiplyVector3(zVec);
        float[] vec1 = other.getImuPose().pose.multiplyVector3(zVec);
        float dotProduct = vec0[0] * vec1[0] + vec0[1] * vec1[1] + vec0[2] * vec1[2];
        double norm0 = Math.sqrt(vec0[0] * vec0[0] + vec0[1] * vec0[1] + vec0[2] * vec0[2]);
        double norm1 = Math.sqrt(vec1[0] * vec1[0] + vec1[1] * vec1[1] + vec1[2] * vec1[2]);
        double theta = Math.acos(dotProduct/(norm0 * norm1));
        return Math.toDegrees(theta);
    }

    public boolean isBlur() {
        if(mGyroNorm > 0.5) {
            return true;
        }
        if(mNumOfEdges < NUM_EDGES_LOWER_LIMIT) {
            return true;
        }
        return false;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getWidth() {
        return mWidth;
    }
}
