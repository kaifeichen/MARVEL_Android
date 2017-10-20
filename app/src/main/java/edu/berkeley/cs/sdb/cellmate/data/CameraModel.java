package edu.berkeley.cs.sdb.cellmate.data;


import android.util.Size;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

/**
 * Created by tongli on 9/28/17.
 */

public class CameraModel {
    private String mName;
    private Size mImageSize;
    private float mFx;
    private float mFy;
    private float mCx;
    private float mCy;


    public CameraModel(String mName, Size mImageSize, float mFx, float mFy, float mCx, float mCy) {
        this.mName = mName;
        this.mImageSize = mImageSize;
        this.mFx = mFx;
        this.mFy = mFy;
        this.mCx = mCx;
        this.mCy = mCy;
    }

    public String getName() {
        return mName;
    }

    public Size getImageSize() {
        return mImageSize;
    }

    public float getFx() {
        return mFx;
    }

    public float getFy() {
        return mFy;
    }

    public float getCx() {
        return mCx;
    }

    public float getCy() {
        return mCy;
    }

    public Mat K() {
        Mat mK = new Mat(3,3, CvType.CV_32FC1);
        int row = 0, col = 0;
        mK.put(row, col, mFx, 0, mCx, 0, mFy, mCy, 0, 0, 1);
        return mK;
    }

    public boolean isValid() {
        return mFx > 0 && mFy > 0 && mCx > 0 && mCy > 0;
    }

    public void setFx(float mFx) {
        this.mFx = mFx;
    }

    public void setFy(float mFy) {
        this.mFy = mFy;
    }

    public void setCx(float mCx) {
        this.mCx = mCx;
    }

    public void setCy(float mCy) {
        this.mCy = mCy;
    }
}
