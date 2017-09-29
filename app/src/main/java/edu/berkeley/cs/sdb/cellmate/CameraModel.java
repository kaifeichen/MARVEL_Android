package edu.berkeley.cs.sdb.cellmate;

import android.graphics.Matrix;
import android.util.Size;

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

    public Matrix K() {
        //need to change to cv::mat later
        return new Matrix();
    }

    public boolean isValid() {
        return mFx > 0 && mFy > 0 && mCx > 0 && mCy > 0;
    }
}
