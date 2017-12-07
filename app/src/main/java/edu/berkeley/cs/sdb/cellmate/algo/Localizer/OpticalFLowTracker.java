package edu.berkeley.cs.sdb.cellmate.algo.Localizer;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

import java.security.Key;
import java.util.ArrayList;
import java.util.List;

import edu.berkeley.cs.sdb.cellmate.Camera;
import edu.berkeley.cs.sdb.cellmate.data.KeyFrame;

/**
 * Created by tongli on 11/20/17.
 */

public class OpticalFLowTracker {
    private KeyFrame mOldFrame = null;
    private KeyFrame mNewFrame = null;
    private List<Point> oldFramePoints;
    private List<Point> newFramePoints;

    private Mat mRgba, mErodeKernel, matOpFlowPrev, matOpFlowThis;
    private MatOfFloat mMOFerr;
    private MatOfByte mMOBStatus;
    private MatOfPoint2f mMOP2fptsPrev = null, mMOP2fptsThis, mMOP2fptsSafe;
    private MatOfPoint MOPcorners;
    private int x, y, iLineThickness = 3, iGFFTMax = 200;
    private List<Point> cornersThis, cornersPrev;
    private List<Byte> byteStatus;
    private Point pt, pt2;

    public OpticalFLowTracker() {
        mMOP2fptsPrev = new MatOfPoint2f();
        mMOP2fptsThis = new MatOfPoint2f();
        mMOP2fptsSafe = new MatOfPoint2f();
        mMOFerr = new MatOfFloat();
        mMOBStatus = new MatOfByte();
        MOPcorners = new MatOfPoint();
        mRgba = new Mat();
        matOpFlowThis = new Mat();
        matOpFlowPrev = new Mat();
        cornersThis = new ArrayList<>();
        cornersPrev = new ArrayList<>();
        pt = new Point(0, 0);
        pt2 = new Point(0, 0);
    }

    public void trackFlow(KeyFrame newFrame) {
        mOldFrame = mNewFrame;
        mNewFrame = newFrame;
        long time = System.currentTimeMillis();

        mRgba = getMatFromFrame(newFrame);

        if (mMOP2fptsSafe.rows() == 0) {

            // first time through the loop so we need prev and this mats
            // plus prev points
            // get this mat
            matOpFlowThis = mRgba;

            // copy that to prev mat
            matOpFlowThis.copyTo(matOpFlowPrev);

            // get prev corners
            Imgproc.goodFeaturesToTrack(matOpFlowPrev, MOPcorners, iGFFTMax, 0.05, 10);

            mMOP2fptsPrev.fromArray(MOPcorners.toArray());

            // get safe copy of this corners
            mMOP2fptsPrev.copyTo(mMOP2fptsSafe);
        }
        else
        {
            //Log.d("Baz", "Opflow");
            // we've been through before so
            // this mat is valid. Copy it to prev mat
            matOpFlowThis.copyTo(matOpFlowPrev);

            // get this mat
//            Imgproc.cvtColor(mRgba, matOpFlowThis, Imgproc.COLOR_RGBA2GRAY);
            matOpFlowThis = mRgba;

            // get the corners for this mat
            Imgproc.goodFeaturesToTrack(matOpFlowThis, MOPcorners, iGFFTMax, 0.05, 10);
            mMOP2fptsThis.fromArray(MOPcorners.toArray());

            // retrieve the corners from the prev mat
            // (saves calculating them again)
            mMOP2fptsSafe.copyTo(mMOP2fptsPrev);

            // and save this corners for next time through
            mMOP2fptsThis.copyTo(mMOP2fptsSafe);
        }


        /*
        Parameters:
            prevImg first 8-bit input image
            nextImg second input image
            prevPts vector of 2D points for which the flow needs to be found; point coordinates must be single-precision floating-point numbers.
            nextPts output vector of 2D points (with single-precision floating-point coordinates) containing the calculated new positions of input features in the second image; when OPTFLOW_USE_INITIAL_FLOW flag is passed, the vector must have the same size as in the input.
            status output status vector (of unsigned chars); each element of the vector is set to 1 if the flow for the corresponding features has been found, otherwise, it is set to 0.
            err output vector of errors; each element of the vector is set to an error for the corresponding feature, type of the error measure can be set in flags parameter; if the flow wasn't found then the error is not defined (use the status parameter to find such cases).
        */
        Video.calcOpticalFlowPyrLK(matOpFlowPrev, matOpFlowThis, mMOP2fptsPrev, mMOP2fptsThis, mMOBStatus, mMOFerr);

        cornersPrev = mMOP2fptsPrev.toList();
        cornersThis = mMOP2fptsThis.toList();
        byteStatus = mMOBStatus.toList();

        y = byteStatus.size() - 1;

        //Creating new reference is because the old reference might be used by some other application
        oldFramePoints = new ArrayList<>();
        newFramePoints = new ArrayList<>();

        for (x = 0; x < y; x++) {
            if (byteStatus.get(x) == 1) {
                pt = cornersThis.get(x);
                pt2 = cornersPrev.get(x);
                newFramePoints.add(pt);
                oldFramePoints.add(pt2);
            }
        }
        System.out.println("optical flow uses " + (System.currentTimeMillis() - time) + "ms" );
    }

    Mat rotateImage(Mat img, int orientation) {
        if (orientation == 8) { // 90
            Core.transpose(img, img);
            Core.flip(img, img, 1);
        } else if (orientation == 3) { // 180
            Core.flip(img, img, -1);
        } else if (orientation == 6) { // 270
            Core.transpose(img, img);
            Core.flip(img, img, 0);
        }
        return img;
    }

    public List<Point> getOldFramePoints() {
        return oldFramePoints;
    }

    public List<Point> getNewFramePoints() {
        return newFramePoints;
    }

    public void resetFirstFrame(KeyFrame firstFrame) {
        if(mNewFrame != firstFrame) {
            mNewFrame = firstFrame;
            if(firstFrame != null) {
                matOpFlowThis = getMatFromFrame(firstFrame);
                mMOP2fptsSafe.fromArray(getGoodFeaturesFromData(matOpFlowThis).toArray());
            }

        }
    }

    private Mat getMatFromFrame(KeyFrame frame) {
        byte[] data = frame.getData();
        int height = frame.getHeight();
        int width = frame.getWidth();

        Mat mat;

        mat = new Mat(height, width, CvType.CV_8UC1);
        mat.put(0, 0, data);

        long beforeRotateTime = System.currentTimeMillis();
        Camera camera = Camera.getInstance();
        int rotateClockwiseAngle = (camera.getDeviceOrientation() + 90) % 360;
        int orientation = 1;
        if(rotateClockwiseAngle == 90) {
            orientation = 8;
        } else if(rotateClockwiseAngle == 180) {
            orientation = 3;
        } else if(rotateClockwiseAngle == 270) {
            orientation = 6;
        } else {
            orientation = 1;
        }
        mat = rotateImage(mat, orientation);
        System.out.println("rotate time" + (System.currentTimeMillis() - beforeRotateTime));

        return mat;
    }

    private MatOfPoint getGoodFeaturesFromData(Mat data) {
        MatOfPoint goodFeatures = new MatOfPoint();
        // get prev corners
        Imgproc.goodFeaturesToTrack(data, goodFeatures, iGFFTMax, 0.05, 10);
        return goodFeatures;
    }

    public KeyFrame latestFrame() {
        return mNewFrame;
    }
}
