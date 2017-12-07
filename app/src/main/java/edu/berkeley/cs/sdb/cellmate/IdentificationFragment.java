package edu.berkeley.cs.sdb.cellmate;

import edu.berkeley.cs.sdb.cellmate.algo.Localizer.LocTracker;
import edu.berkeley.cs.sdb.cellmate.algo.Localizer.OpticalFLowTracker;
import edu.berkeley.cs.sdb.cellmate.data.KeyFrame;
import edu.berkeley.cs.sdb.cellmate.data.SortingElement;
import edu.berkeley.cs.sdb.snaplink.*;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.protobuf.ByteString;

import org.opencv.android.InstallCallbackInterface;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.CvType;
import org.opencv.core.MatOfByte;
import org.opencv.core.Mat;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;
import org.w3c.dom.NameList;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import edu.berkeley.cs.sdb.cellmate.data.CameraModel;
import edu.berkeley.cs.sdb.cellmate.data.Label;
import edu.berkeley.cs.sdb.cellmate.data.Transform;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import static org.opencv.calib3d.Calib3d.Rodrigues;
import static org.opencv.calib3d.Calib3d.projectPoints;
import static org.opencv.core.CvType.CV_64FC1;

public class IdentificationFragment extends Fragment implements LocTracker.StateCallback {
    private static final String LOG_TAG = "CellMate";
    private static final String CONTROL_TOPIC_PREFIX = "410.dev/plugctl/front/s.powerup.v0/";
    private static final String CONTROL_TOPIC_SUFFIX = "/i.binact/slot/state";
    private final int CIRCULAR_BUFFER_LENGTH = 10;
    String mHost;
    String mPort;
    float mFx;
    float mFy;
    float mCx;
    float mCy;
    ManagedChannel mChannel;
    StreamObserver<SnapLinkProto.LocalizationRequest> mRequestObserver;
    TextView mTextView;
    TextView mInforText;
    ImageReader mImageReader;
    private Handler mHandler;
    private boolean mAttached;
    private int REQUEST_INTERVAL = 500;
    private final int ANGLE_DIFF_LIMIT = 5;
    OpticalFLowTracker opticalFLowTracker;

    private int frameCount;
    LoaderCallbackInterface mLoaderCallback = new LoaderCallbackInterface() {
        @Override
        public void onManagerConnected(int i) {
            onresumeCallback();
        }

        @Override
        public void onPackageInstall(int i, InstallCallbackInterface installCallbackInterface) {

        }
    };


    private StateCallback mStateCallback;

    private HashMap<Integer, List<Label>> mLabels;
    private int mRoomId;
    private Long mLastTime;
    // We keep a toast reference so it can be updated instantly
    private Toast mToast;
    private String mTargetObject;
    private List<String> mRecentObjects;
    private List<String> mDescriptions = new ArrayList<>();
    private byte[] mLatestImageData;
    private List<KeyFrame> mFrameCache = new ArrayList<>();
    private int mFrameCacheLimit = 10;
    private List<KeyFrame> mFrameCacheForOF = new ArrayList<>();
    private final int mFrameCacheForOFLimit = 1000;

    long mCurrentPoseMATime;
    Transform mCurrentPoseMA;
    Transform mCurrentPoseMS;
    boolean stancePhaseJustCorrected = false;

    //HashMap of {time/queryId, poses including poseAP,poseMI,poseSI at that time}
    //A entry will be added when a queryMessage is send
    //A entry will be removed when the queryResult comes back, and it is corrected by locTracker
    private HashMap<Long, Poses> mPosesMap;


    private Point opticalFlowLabel = null;
    private Point diff = new Point(0,0);
    private double diffSquareThresh = 100 * 100;
    private final double opticalFlowStdThresh = 15;
    private double opticalFlowConfident = 0;
    private final double imuCountThread = 200;
    private double imuConfident = 0;
    private boolean offloading = false;

    StreamObserver<SnapLinkProto.LocalizationResponse> mResponseObserver = new StreamObserver<SnapLinkProto.LocalizationResponse>() {
        @Override
        public void onNext(SnapLinkProto.LocalizationResponse value) {
            final Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    try {
//                        offloading = false;
                        System.out.println("debug3.3 offload back");


                        mStateCallback.resetLocTrackerLinearMoveCount();
                        double oldOpticalFlowConfident = opticalFlowConfident;
                        opticalFlowConfident = 1;
                        imuConfident = 1;
                        System.out.println("debug3 step1");



                        Transform currentPoseMS = null;
                        if(value.getRequestId(0) > mCurrentPoseMATime) {
                            System.out.println("debug3 step2");
                            List<Transform> PoseMIs = getPoseFromMessage(value);
                            List<Transform> PoseMAs = new ArrayList<>();
                            List<Long> PosesTimes = new ArrayList<Long>();
                            System.out.println("debug3 step3");
                            for(int i = 0; i < PoseMIs.size(); i++) {
                                System.out.println("debug3 step3.1");
                                if(!value.getSuccess(i)) {
                                    System.out.println("debug3.4 fail index" + i);
                                    mPosesMap.remove(value.getRequestId(i));
                                    continue;
                                }
                                System.out.println("debug3 step3.2");
                                System.out.println("debug3 step3.9 key=" + value.getRequestId(i));
                                Poses poses = mPosesMap.get(value.getRequestId(i));
                                if(poses == null) {
                                    System.out.println("debug3 step3.2 poses is null");
                                }
                                System.out.println("debug3 step3.2.1 i=" + i + " size=" + PoseMIs.size());
                                poses.PoseMI = PoseMIs.get(i);
                                System.out.println("debug3 step3.2.2");
                                Transform PoseSIinv = poses.PoseSI.inverse();
                                System.out.println("debug3 step3.2.3");
                                Transform PoseAPinv = poses.PoseAP.inverse();
                                System.out.println("debug3 step3.2.4");
                                Transform PosePSinv = getPosePS().inverse();

                                System.out.println("debug3 step3.3");
                                Transform tempPoseMA = PoseMIs.get(i).multiply(PoseSIinv).multiply(PosePSinv).multiply(PoseAPinv);
                                PoseMAs.add(tempPoseMA);
                                PosesTimes.add(value.getRequestId(i));
                                if(poses.PoseAPCorected) {
                                    //remove this query event poses if it is corrected by LocTracker already
                                    mPosesMap.remove(value.getRequestId(i));
                                }
                            }

                            System.out.println("debug3 step4");
                            if(PoseMAs.size() <= 0) {
                                return;
                            }
                            int bestPoseMAindex = bestPoseMAindex(PoseMAs);
                            mCurrentPoseMA = PoseMAs.get(bestPoseMAindex);
                            mCurrentPoseMATime = PosesTimes.get(bestPoseMAindex);

                            System.out.println("debug3 step5");
                            Transform currentPoseAP = mStateCallback.getLatestPoseAndTime().pose;
                            currentPoseMS = mCurrentPoseMA.multiply(currentPoseAP).multiply(getPosePS());
                            mCurrentPoseMS = currentPoseMS;
                        } else {
                            System.out.println("debug3 shouldnt be here");
                            //remove this query event poses if it is outdated
                            for(long id : value.getRequestIdList()) {
                                mPosesMap.remove(id);
                            }
                        }
                        System.out.println("debug3 must be here");
                        mRoomId = value.getDbId();
                        System.out.println("debug3 dbid = " + mRoomId);

                        //Set Optical flow label position
                        long ofTime = SystemClock.elapsedRealtimeNanos()-mImageDelayedTime;
                        Transform ofPoseAP = mStateCallback.getNearestPoseAndTimeForOF2(ofTime).pose;
                        Transform ofPoseMS = mCurrentPoseMA.multiply(ofPoseAP).multiply(getPosePS());
                        ArrayList<String> ofNameListOld = new ArrayList<>();
                        ArrayList<Float> ofXListOld = new ArrayList<>();
                        ArrayList<Float> ofYListOld = new ArrayList<>();
                        ArrayList<Float> ofSizeListOld = new ArrayList<>();
                        visibility(ofPoseMS, ofNameListOld, ofXListOld, ofYListOld, ofSizeListOld);
                        opticalFlowLabel = new Point(ofXListOld.get(0), ofYListOld.get(0));



                        ArrayList<String> nameListOld = new ArrayList<>();
                        ArrayList<Float> XListOld = new ArrayList<>();
                        ArrayList<Float> YListOld = new ArrayList<>();
                        ArrayList<Float> SizeListOld = new ArrayList<>();

                        visibility(currentPoseMS, nameListOld, XListOld, YListOld, SizeListOld);
                        nameListOld.add(0, "corrected");
                        XListOld.add(0, XListOld.get(0));
                        YListOld.add(0, YListOld.get(0));
                        SizeListOld.add(0, SizeListOld.get(0));
                        nameListOld.add("red");
                        //this function modifying x,y list
                        nameListOld.add(0,"B");
                        mStateCallback.onObjectIdentified(nameListOld, XListOld, YListOld, SizeListOld);

                    } catch (IllegalStateException e) {
                        //Do nothing
                        //To fix "Fragment ControlFragment{2dab555} not attached to Activity"
                    } catch (NullPointerException e) {
                        //Do nothing
                        //To fix  "Attempt to invoke interface method
                        //'void edu.berkeley.cs.sdb.cellmate.ControlFragment$
                        //StateCallback.onObjectIdentified(java.lang.String, double, double, double)'
                        //on a null object reference"
                        //I think this problem is due to
                    }
                });

            }

        }

        @Override
        public void onError(Throwable t) {
            System.out.println("onError");
            t.printStackTrace();
        }

        @Override
        public void onCompleted() {
            showToast("Server is disconnected due to grpc complete", Toast.LENGTH_LONG);
        }
    };

    private int bestPoseMAindex(List<Transform> PoseMSList) {
        //Construct 2 matrix, one for angleDifference, one for posDifference
        List<List<Double>> angleDiffs = new ArrayList<>();
        List<List<Double>> posDiffs = new ArrayList<>();
        for(int i = 0; i < PoseMSList.size(); i++) {
            List<Double> subAngleDiffs = new ArrayList<>();
            List<Double> subPosDiff = new ArrayList<>();
            for(int j = 0; j < PoseMSList.size(); j++) {
                double angleDiff = 0;
                if(i!=j) {
                    angleDiff = PoseMSList.get(i).getAngleDiff(PoseMSList.get(j));
                }
                double posDiff = PoseMSList.get(i).getPositionDiff(PoseMSList.get(j));
                subAngleDiffs.add(angleDiff);
                subPosDiff.add(posDiff);
            }
            angleDiffs.add(subAngleDiffs);
            posDiffs.add(subPosDiff);
        }


        //Base on the 2 matrix above, calculate the sum difference of each PoseMS relative to others
        List<Double> angleDiffList = new ArrayList<>();
        List<Double> posDiffList = new ArrayList<>();
        for(int i = 0; i < angleDiffs.size(); i++) {
            double angleDiffRowSum = 0;
            List<Double> subAngleDiffs = angleDiffs.get(i);
            for(int j = 0; j < subAngleDiffs.size(); j++) {
                angleDiffRowSum += subAngleDiffs.get(j);
            }
            angleDiffList.add(angleDiffRowSum);

            double posDiffsRowSum = 0;
            List<Double> subPosDiffs = posDiffs.get(i);
            for(int j = 0; j < subPosDiffs.size(); j++) {
                posDiffsRowSum += subPosDiffs.get(j);
            }
            posDiffList.add(posDiffsRowSum);
        }

        //Construct a list of SortingElement to sort the PoseMAs
        List<SortingElement> sortingList = new ArrayList<>();
        for(int i = 0; i < angleDiffList.size(); i++) {
            sortingList.add(new SortingElement(i));
        }

        //First sort by angleDiff
        for(int i = 0; i < sortingList.size(); i++) {
            sortingList.get(i).setValue(angleDiffList.get(i));
        }

        Collections.sort(sortingList);

        //Save the result of angleDiff sort ranking using addRank
        //At same time, set value to posDiff in order to perform sorting by posDiff
        for(int i = 0; i < sortingList.size(); i++) {
            SortingElement element = sortingList.get(i);
            element.addRank(i);
            element.setValue(posDiffList.get(element.getIndex()));
        }

        Collections.sort(sortingList);

        //Add the result of posDiff sort ranking using addRank
        //At same time, set value to rankSum in order to perform sorting by rankSum
        for(int i = 0; i < sortingList.size(); i++) {
            SortingElement element = sortingList.get(i);
            element.addRank(i);
            element.setValue(element.getRankSum());
        }

        Collections.sort(sortingList);


        //the first one should have the smallest rank,
        //meaning have the smallest angleDiff and posDiff to others
        return sortingList.get(0).getIndex();

    }



    private long mImageDelayedTime = 0;

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = (ImageReader reader) -> {
        //discard first 3 frames. Pretty sure that the first frame is black
        if(frameCount < 3) {
            frameCount++;
            return;
        }

        if (mAttached) {
            System.out.println("ImageReader.OnImageAvailableListener");

            //Update IMU poses
            if(mCurrentPoseMA != null) {
                Transform currentPoseAP = mStateCallback.getLatestPoseAndTime().pose;
                Transform currentPoseMS = mCurrentPoseMA.multiply(currentPoseAP).multiply(getPosePS());
                ArrayList<String> nameList = new ArrayList<>();
                ArrayList<Float> XList = new ArrayList<>();
                ArrayList<Float> YList = new ArrayList<>();
                ArrayList<Float> SizeList = new ArrayList<>();
                visibility(currentPoseMS, nameList, XList, YList, SizeList);
                if(stancePhaseJustCorrected) {
                    opticalFlowLabel = new Point(XList.get(0), YList.get(0));
                    System.out.println("opticalFlowLabel stancePhaseCorrection " + opticalFlowLabel.x + "  " + opticalFlowLabel.y );
                    stancePhaseJustCorrected = false;
                    diff = new Point(0,0);
//                    opticalFLowTracker.resetFirstFrame(null);
                    System.out.println("debug6 step0");
                }
                mCurrentPoseMS = currentPoseMS;
            }

            Image image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }


            long imageGeneratedTime = image.getTimestamp();
            mImageDelayedTime = SystemClock.elapsedRealtimeNanos() - imageGeneratedTime;
            mLatestImageData = imageToBytes(image);
            int height = image.getHeight();
            int width = image.getWidth();
            image.close();

            //Construct the frame for current image
            Camera camera = Camera.getInstance();
            int rotateClockwiseAngle = (camera.getDeviceOrientation() + 90) % 360;
            KeyFrame curFrame = new KeyFrame(
                    imageGeneratedTime,
                    mStateCallback.getNearestPoseAndTime(imageGeneratedTime),
                    mLatestImageData,
                    height,
                    width,
                    rotateClockwiseAngle);

            //Calculate correct label position and draw
            Runnable calculateAndDrawRunnable = new Runnable() {
                @Override
                public void run() {
                    //Calculate correct label position and draw
                    CalculateLabelAndDraw(curFrame);
                }
            };
            mHandler.post(calculateAndDrawRunnable);

            //Offloading
            mFrameCache.add(curFrame);
            if(mFrameCache.size() > mFrameCacheLimit) {
                mFrameCache.remove(0);
            }
        }
    };

    private void offLoad() {
        if(offloading) {
            return;
        }
        System.out.println("debug3 " + mFrameCache.size());
        if(mFrameCache.size()<5) {
            System.out.println("debug3.1 " + mFrameCache.size());
            return;
        }
        Runnable senderRunnable = new Runnable() {
            @Override
            public void run() {
                offloading = true;
                System.out.println("offload");
                sortFramesToFindBest();


                List<ByteString> datas = new ArrayList<>();
                List<Integer> rotateClockwiseAngles =  new ArrayList<>();
                List<Long> times =  new ArrayList<>();
                List<Float> blurness = new ArrayList<>();
                List<SnapLinkProto.Matrix> poses = new ArrayList<>();


                for(int i = 0; i < 5; i++) {
                    KeyFrame frameToSend = mFrameCache.get(i);
                    Transform pose = frameToSend.getImuPose().pose;
                    System.out.println("debug3 step3.8 key=" + frameToSend.getImuPose().time);
                    mPosesMap.put(frameToSend.getImuPose().time, new Poses(false,pose,null,getPoseSI()));
                    datas.add(ByteString.copyFrom(frameToSend.getJpegData()));
                    rotateClockwiseAngles.add(frameToSend.getmRotateClockwiseAngle());
                    times.add(frameToSend.getImuPose().time);
                    blurness.add((float)i);
                    frameToSend.getImuPose().pose.print();
                    poses.add(getMessageMatrixFromPose(frameToSend.getImuPose().pose));
                }

                System.out.println("debug " + datas.size());
                sendRequestToServer(datas, rotateClockwiseAngles, times, blurness, poses);

            }
        };
        mHandler.post(senderRunnable);
    }

    Point diff1;
    double opticalFlowPositionDiffThreshold = 0.01;
    boolean offLoadNow = false;
    int ofCount = 3;
    private void CalculateLabelAndDraw(KeyFrame curFrame) {
        //Can't do this in onCreate or onResume because at that time the openCV loader is not loded yet
        if(opticalFLowTracker == null) {
            opticalFLowTracker = new OpticalFLowTracker();
        }

        boolean doOpticalFlow = false;

        if(opticalFLowTracker.latestFrame() == null) {
            opticalFLowTracker.resetFirstFrame(curFrame);
        }

        if(!curFrame.isBlur()) {
//            System.out.println("debug6, 2 frame translation is" + curFrame.getImuPose().pose.getPositionDiff(opticalFLowTracker.latestFrame().getImuPose().pose));
//            curFrame.getImuPose().pose.print();
//            opticalFLowTracker.latestFrame().getImuPose().pose.print();
//            System.out.println("debug6, 2 frame id is" + curFrame.getImuPose().time + "    " + opticalFLowTracker.latestFrame().getImuPose().time);
//            ofCount++;
//            if(ofCount > 10) {
//                doOpticalFlow = true;
//                ofCount = 0;
//            }
            if(curFrame.getImuPose().pose.getPositionDiff(opticalFLowTracker.latestFrame().getImuPose().pose) > opticalFlowPositionDiffThreshold) {
                doOpticalFlow = true;
                System.out.println("debug6 doingOF because translaion pos diff = " + curFrame.getImuPose().pose.getPositionDiff(opticalFLowTracker.latestFrame().getImuPose().pose));
            }
            if(curFrame.angleDiffInDegree(opticalFLowTracker.latestFrame()) > ANGLE_DIFF_LIMIT) {
                doOpticalFlow = true;
                System.out.println("debug6 doingOF because rotation anngle diff = " + curFrame.angleDiffInDegree(opticalFLowTracker.latestFrame()));

            }
        }
        List<Point> oldFramePoints = null;
        List<Point> newFramePoints = null;

        if(doOpticalFlow) {
            opticalFLowTracker.trackFlow(curFrame);
            oldFramePoints = opticalFLowTracker.getOldFramePoints();
            newFramePoints = opticalFLowTracker.getNewFramePoints();
        }

        Point imuLabel_past = null;
        //Calculating diff (difference between IMU label and IMU+opticalFlow label)
        if(oldFramePoints == null) {
            //didn't do opticalFlow
            //diff remain unchanged
        } else {
            //opticalFlow is performed, we can update diff now
            if(opticalFlowLabel != null) {
                System.out.println("debug6 of label x = "+ opticalFlowLabel.x + " " + opticalFlowLabel.y);

                List<Integer> closestNPoints = nearestNPointIndex(oldFramePoints, opticalFlowLabel, newFramePoints);
                if(closestNPoints == null) {
                    System.out.println("debug6 step1");
                    opticalFlowConfident = 0;
                } else {
                    System.out.println("debug6 step2");

                    //this function also update opticalFlow confident
                    opticalFlowLabel = predictLabelWithNearestPoints(closestNPoints,oldFramePoints, opticalFlowLabel, newFramePoints);
                }
                updateImuConfident();
                if(opticalFlowConfident == 0) {
                    System.out.println("debug6 step3");

                    //std showing OF inaccurate, probably need to offload to server to calibrate
                    diff = new Point(0,0);
                } else {
                    //Calcluate IMU label, then diff
                    System.out.println("debug6 step4");

                    long t0 = curFrame.getFrameTime();
                    Transform poseAP_t0 = mStateCallback.getNearestPoseAndTimeForOF(t0).pose;
                    Transform poseMS_t0 = mCurrentPoseMA.multiply(poseAP_t0.multiply(getPosePS()));
                    ArrayList<String> nameList = new ArrayList<>();
                    ArrayList<Float> XList = new ArrayList<>();
                    ArrayList<Float> YList = new ArrayList<>();
                    ArrayList<Float> SizeList = new ArrayList<>();
                    visibility(poseMS_t0, nameList, XList, YList, SizeList);
                    Point imuLabel = new Point(XList.get(0), YList.get(0));
                    imuLabel_past = imuLabel;
                    double totalConfident = imuConfident + opticalFlowConfident;
                    double imuWeight = imuConfident/totalConfident;
                    double opticalFlowWeight = opticalFlowConfident/totalConfident;
                    double weightedX = imuLabel.x * imuWeight + opticalFlowLabel.x * opticalFlowWeight;
                    double weightedY = imuLabel.y * imuWeight + opticalFlowLabel.y * opticalFlowWeight;
                    Point combinedLabel = new Point(weightedX, weightedY);
                    diff = new Point(imuLabel.x - combinedLabel.x, imuLabel.y - combinedLabel.y);
                    System.out.println("debug6 imuLabel" + imuLabel.x + "  " + imuLabel.y);
                    System.out.println("debug6 opticalFlowLabel" + opticalFlowLabel.x + "  " + opticalFlowLabel.y);
                    System.out.println("debug6 diff" + diff.x + "  " + diff.y);
//                    if(mCurrentPoseMS != null) {
//                        ArrayList<String> nameList1 = new ArrayList<>();
//                        ArrayList<Float> XList1 = new ArrayList<>();
//                        ArrayList<Float> YList1 = new ArrayList<>();
//                        ArrayList<Float> SizeList1 = new ArrayList<>();
//                        visibility(mCurrentPoseMS, nameList1, XList1, YList1, SizeList1);
//                        if(nameList1.get(0) != "None") {
//                            diff1 = new Point(XList1.get(0) - XList.get(0), YList1.get(0) - YList.get(0));
//                            System.out.println("debug6 diff1" + diff1.x + "  " + diff1.y);
//                        }
//                    }

                }
            } else {
                System.out.println("debug6 dont be here");
                diff = new Point(0,0);
            }
        }

        if(opticalFlowConfident == 0 && imuConfident == 0) {
            System.out.println("debug case 1 offload");
            offLoadNow = true;
//            opticalFLowTracker.resetFirstFrame(curFrame);
//            opticalFLowTracker.resetFirstFrame(null);
            offLoad();
        } else if((Math.pow(diff.x,2) + Math.pow(diff.y,2)) > diffSquareThresh) {
            System.out.println("debug case 2 offload");
            offLoadNow = true;
//            opticalFLowTracker.resetFirstFrame(curFrame);
            offLoad();
        }

        //apply diff to IMU_Label_t1
        if(mCurrentPoseMS != null) {
            ArrayList<String> nameList = new ArrayList<>();
            ArrayList<Float> XList = new ArrayList<>();
            ArrayList<Float> YList = new ArrayList<>();
            ArrayList<Float> SizeList = new ArrayList<>();
            visibility(mCurrentPoseMS, nameList, XList, YList, SizeList);

//            use opticalflow_past and imu_past to update imu_now
            if(nameList.get(0) != "None") {
                nameList.add(0,"corrected");
                XList.add(0,XList.get(0) - (float)diff.x);
                YList.add(0,YList.get(0) - (float)diff.y);
                SizeList.add(0,SizeList.get(0));
                if(offLoadNow) {
                    offLoadNow = false;
                    nameList.add(0,"O");
                } else {
                    nameList.add(0,"N");
                }
                nameList.add("optFlow");
                XList.add((float)opticalFlowLabel.x);
                YList.add((float)opticalFlowLabel.y);
                SizeList.add(SizeList.get(0));
                if(imuLabel_past!=null) {
                    nameList.add("imu_past");
                    XList.add((float)imuLabel_past.x);
                    YList.add((float)imuLabel_past.y);
                    SizeList.add(SizeList.get(0));
                }
            }

            //Use opticalflow_past and imu_now
//            if(nameList.get(0) != "None") {
//                XList.set(0, (XList.get(0) + (float)opticalFlowLabel.x)/2);
//                YList.set(0, (YList.get(0) + (float)opticalFlowLabel.y)/2);
//            }

//            Use purely optical flow
//            if(nameList.get(0) != "None") {
//                XList.set(0, (float)opticalFlowLabel.x);
//                YList.set(0, (float)opticalFlowLabel.y);
//            }


//            if(opticalFlowConfident != 0) {
//                if(diff1!=null) {
//                    System.out.println("aaaa1");
//                    if(nameList.get(0) != "None") {
//                        System.out.println("aaaa2");
//                        nameList.set(0, "of");
//                        XList.set(0, (float)opticalFlowLabel.x);
//                        YList.set(0, (float)opticalFlowLabel.y);
//                        nameList.add("of_cor");
//                        XList.add((float)opticalFlowLabel.x + (float)diff1.x);
//                        YList.add((float)opticalFlowLabel.y + (float)diff1.y);
//                        SizeList.add(SizeList.get(0));
//                        System.out.print("sizes :" + nameList.size() + " " + XList.size());
//                        nameList.add("red");
//
//                    }
//                }
//            } else {
//
//                System.out.println("aaaa3");
//                updateImuConfident();
//                if(imuConfident < 0.3) {
//                    offLoad();
//                }
//            }


            System.out.println("aaaa4");
            mStateCallback.onObjectIdentified(nameList, XList, YList, SizeList);
        }


    }

    private void updateImuConfident() {
        int count = mStateCallback.getLocTrackerLinearMoveCount();
        if(count > imuCountThread) {
            imuConfident = 0;
        } else {
            imuConfident = 1 - (count / imuCountThread);
        }
    }

    Comparator<KeyFrame> gyroComparator = new Comparator<KeyFrame>() {
        @Override
        public int compare(KeyFrame keyFrame, KeyFrame t1) {
            float[] gyroReading = keyFrame.getImuPose().gyroReading;
            float norm1 = (float)Math.sqrt(Math.pow(gyroReading[0], 2)+Math.pow(gyroReading[1], 2)+ Math.pow(gyroReading[2], 2));
            float[] gyroReading2 = t1.getImuPose().gyroReading;
            float norm2 = (float)Math.sqrt(Math.pow(gyroReading2[0], 2)+Math.pow(gyroReading2[1], 2)+ Math.pow(gyroReading2[2], 2));
            if(norm1 - norm2 < 0) {
                return -1;
            } else if(norm1 - norm2 > 0) {
                return 1;
            } else {
                return 0;
            }
        }
    };

    Comparator<KeyFrame> featureComparator = new Comparator<KeyFrame>() {
        @Override
        public int compare(KeyFrame keyFrame, KeyFrame t1) {
            return t1.getNumOfEdges() - keyFrame.getNumOfEdges();
        }
    };

    Comparator<KeyFrame> rankComparator = new Comparator<KeyFrame>() {
        @Override
        public int compare(KeyFrame keyFrame, KeyFrame t1) {
            return keyFrame.getRank() - t1.getRank();
        }
    };

    private void sortFramesToFindBest() {
        Collections.sort(mFrameCache, gyroComparator);
        for(int i = 0; i < mFrameCache.size(); i++) {
            mFrameCache.get(i).setGyroRank(i);
        }

        Collections.sort(mFrameCache, featureComparator);
        for(int i = 0; i < mFrameCache.size(); i++) {
            mFrameCache.get(i).setFeatureRank(i);
        }

        Collections.sort(mFrameCache, rankComparator);
    }

    private View mView;

    public static IdentificationFragment newInstance() {
        return new IdentificationFragment();
    }

    private List<Transform> getPoseFromMessage(SnapLinkProto.LocalizationResponse value) {
        List<Transform> retVal = new ArrayList<>();
        for(int i = 0; i < value.getPoseList().size(); i++) {
            SnapLinkProto.Matrix matrix = value.getPose(i);
            retVal.add(new Transform(matrix.getData(0), matrix.getData(1), matrix.getData(2), matrix.getData(3),
                    matrix.getData(4), matrix.getData(5), matrix.getData(6), matrix.getData(7),
                    matrix.getData(8), matrix.getData(9), matrix.getData(10), matrix.getData(11)));
        }
        return retVal;
    }

    private SnapLinkProto.Matrix getMessageMatrixFromPose(Transform transform) {
        SnapLinkProto.Matrix matrix = SnapLinkProto.Matrix.newBuilder().setRows(3)
                .setCols(4)
                .addData(transform.r11())
                .addData(transform.r12())
                .addData(transform.r13())
                .addData(transform.x())
                .addData(transform.r21())
                .addData(transform.r22())
                .addData(transform.r23())
                .addData(transform.y())
                .addData(transform.r31())
                .addData(transform.r32())
                .addData(transform.r33())
                .addData(transform.z())
                .build();
        return matrix;
    }

    private void  sendRequestToServer(List<ByteString> datas, List<Integer> rotateClockwiseAngles, List<Long> messageIds, List<Float> blurness, List<SnapLinkProto.Matrix> poses) {
        Activity activity = getActivity();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        float Fx = Float.parseFloat(preferences.getString(activity.getString(R.string.camera_fx_key), activity.getString(R.string.camera_fx_val)));
        float Fy = Float.parseFloat(preferences.getString(activity.getString(R.string.camera_fy_key), activity.getString(R.string.camera_fy_val)));
        float Cx = Float.parseFloat(preferences.getString(activity.getString(R.string.camera_cx_key), activity.getString(R.string.camera_cx_val)));
        float Cy = Float.parseFloat(preferences.getString(activity.getString(R.string.camera_cy_key), activity.getString(R.string.camera_cy_val)));
        String grpcCellmateServerAddr = preferences.getString(getString(R.string.grpc_server_addr_key), getString(R.string.grpc_server_addr_val));
        String grpcCellmateServerPort = preferences.getString(getString(R.string.grpc_server_port_key), getString(R.string.grpc_server_port_val));
        if (mHost != grpcCellmateServerAddr || mPort != grpcCellmateServerPort ||
                mFx != Fx || mFy != Fy || mCx != Cx || mCy != Cy) {
            mRequestObserver.onCompleted();
            copyAllPreferenceValue();
            mRequestObserver = createNewRequestObserver();
        }



        try {
            List<Integer> orientations = new ArrayList<>();
            for(int i = 0; i < rotateClockwiseAngles.size(); i++) {
                int orientation = 1;
                if(rotateClockwiseAngles.get(i) == 90) {
                    orientation = 8;
                } else if(rotateClockwiseAngles.get(i) == 180) {
                    orientation = 3;
                } else if(rotateClockwiseAngles.get(i) == 270) {
                    orientation = 6;
                } else {
                    orientation = 1;
                }
                orientations.add(orientation);
            }

            SnapLinkProto.LocalizationRequest request = SnapLinkProto.LocalizationRequest.newBuilder()
                    .setCamera(SnapLinkProto.CameraModel.newBuilder().setCx(mCx).setCy(mCy).setFx(mFx).setFy(mFy).build())
                    .addAllImage(datas)
                    .addAllOrientation(orientations)
                    .addAllRequestId(messageIds)
                    .addAllBlurness(blurness)
                    .addAllPoses(poses)
                    .build();
            System.out.println("debug3.2 " + datas.size());
            mRequestObserver.onNext(request);
        } catch (RuntimeException e) {
            // Cancel RPC
            showToast("Network Error", Toast.LENGTH_LONG);
            mRequestObserver.onError(e);
        }
    }

    void copyAllPreferenceValue() {
        Activity activity = getActivity();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        mFx = Float.parseFloat(preferences.getString(activity.getString(R.string.camera_fx_key), activity.getString(R.string.camera_fx_val)));
        mFy = Float.parseFloat(preferences.getString(activity.getString(R.string.camera_fy_key), activity.getString(R.string.camera_fy_val)));
        mCx = Float.parseFloat(preferences.getString(activity.getString(R.string.camera_cx_key), activity.getString(R.string.camera_cx_val)));
        mCy = Float.parseFloat(preferences.getString(activity.getString(R.string.camera_cy_key), activity.getString(R.string.camera_cy_val)));
        mHost = preferences.getString(getString(R.string.grpc_server_addr_key), getString(R.string.grpc_server_addr_val));
        mPort = preferences.getString(getString(R.string.grpc_server_port_key), getString(R.string.grpc_server_port_val));
    }

    StreamObserver<SnapLinkProto.LocalizationRequest> createNewRequestObserver() {
        return GrpcServiceGrpc.newStub(mChannel).localize(mResponseObserver);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            mStateCallback = (StateCallback) context;
        } catch (ClassCastException e) {
            throw new RuntimeException(e);
        }
        System.out.println("onAttach");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.identification_fragment, container, false);
        System.out.println("onCreateView");

        return mView;
    }


    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextView = (TextView) view.findViewById(R.id.textInIdentiFrag);
        mInforText = (TextView) view.findViewById(R.id.inforText);

        setHasOptionsMenu(true);

        mLastTime = System.currentTimeMillis();
        mToast = Toast.makeText(getActivity(), "", Toast.LENGTH_SHORT);
        Camera camera = Camera.getInstance();
        Size captureSize = camera.getCaptureSize();
        Size previewSize = camera.getPreviewSize();
        mImageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(),
                ImageFormat.YUV_420_888, /*maxImages*/2);
        mImageReader.setOnImageAvailableListener(
                mOnImageAvailableListener, null);
        mHandler = new Handler();
        System.out.println("onViewCreated");
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        System.out.println("onActivityCreated pre");

        mTargetObject = null;
        mRecentObjects = new ArrayList<>(CIRCULAR_BUFFER_LENGTH);

        copyAllPreferenceValue();

        mPosesMap = new HashMap<>();
        mLabels = new HashMap<>();

        System.out.println("onActivityCreated pre2");
        try {
            mChannel = ManagedChannelBuilder.forAddress(mHost, Integer.valueOf(mPort)).usePlaintext(true).build();
            GrpcServiceGrpc.GrpcServiceBlockingStub stub = GrpcServiceGrpc.newBlockingStub(mChannel);
            SnapLinkProto.Empty message = SnapLinkProto.Empty.newBuilder().build();
            SnapLinkProto.GetLabelsResponse response = stub.getLabels(message);
            Map<Integer, SnapLinkProto.Labels> labelsMap = response.getLabelsMapMap();


            for (Map.Entry<Integer, SnapLinkProto.Labels> entry : labelsMap.entrySet()) {
                int dbId = entry.getKey();
                SnapLinkProto.Labels labels = entry.getValue();
                List<Label> labelsInModel = new ArrayList<>();
                for (SnapLinkProto.Label label : labels.getLabelsList()) {
                    Point3 position = new Point3(label.getX(), label.getY(), label.getZ());
                    labelsInModel.add(new Label(label.getDbId(), position, label.getName()));
                    System.out.println(label.getName());
                }
                mLabels.put(dbId, labelsInModel);
            }
        } catch (RuntimeException e) {
            System.out.println("Getting Labels failed");
            e.printStackTrace();
        }

        System.out.println("onActivityCreated pre3");
        mRequestObserver = createNewRequestObserver();

        System.out.println("onActivityCreated");


    }

    private void onresumeCallback() {
        Activity activity = getActivity();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        double Fx = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_fx_key), activity.getString(R.string.camera_fx_val)));
        if (Fx > 0) {
            mInforText.setVisibility(View.GONE);
        } else {
            mInforText.setText("Camera is uncalibrated, for better use of the system,\n please go to calibration mode and calibrate");
        }
        double frameRate = Double.parseDouble(preferences.getString(activity.getString(R.string.query_rate_key), activity.getString(R.string.query_rate_val)));
        REQUEST_INTERVAL = (int) ((1.0 / frameRate) * 1000);
        copyAllPreferenceValue();
        mRequestObserver = createNewRequestObserver();
        mAttached = true;
        Camera camera = Camera.getInstance();
        Log.i("CellMate", "control fragment register++++");
        camera.registerPreviewSurface(mImageReader.getSurface());
        frameCount = 0;
    }

    @Override
    public void onResume() {
        super.onResume();

        if (!OpenCVLoader.initDebug()) {
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_2_0, getActivity(), mLoaderCallback);
        } else {
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }


    }

    @Override
    public void onPause() {
        mAttached = false;
        mTargetObject = null;
        mTextView.setText(getString(R.string.none));
        mRequestObserver.onCompleted();
        mChannel.shutdown();

        Camera camera = Camera.getInstance();
        Log.i("CellMate", "control fragment unregister");
        camera.unregisterPreviewSurface(mImageReader.getSurface());


//        FileOutputStream logStream;
//
//        File logFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "/imu/pos.txt");
//        logFile.getParentFile().mkdirs();
//        try {
//            logFile.createNewFile();
//            boolean append = true;
//            logStream = new FileOutputStream(logFile, append);
//
//            for (int i = 0; i < mDescriptions.size(); i++) {
//                logStream.write(mDescriptions.get(i).getBytes());
//            }
//            logStream.close();
//        } catch (IOException e) {
//            e.printStackTrace();
//            return;
//        }
//        mHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    /**
     * Shows a Toast on the UI thread.
     *
     * @param text     The message to show
     * @param duration How long to display the message. Either LENGTH_SHORT or LENGTH_LONG
     */
    private void showToast(final String text, final int duration) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                mToast.setText(text);
                mToast.setDuration(duration);
                mToast.show();
            });
        }
    }

    private void visibility(Transform pose, List<String> nameList, List<Float> XList, List<Float> YList, List<Float> SizeList) {
        System.out.println("Inside visibility");
        Size previewSize = Camera.getInstance().getPreviewSize();
        float width = Math.min(previewSize.getWidth(),previewSize.getHeight());
        float height = Math.max(previewSize.getWidth(),previewSize.getHeight());
        CameraModel camera = new CameraModel("CameraModel",
                new Size((int)width,(int)height),
                mFx, mFy, Math.min(mCx,mCy), Math.max(mCx,mCy));
        System.out.println("debug4 nextline should appear, if not mLabels is null");
        System.out.println("debug4 mLabels.size() = " + mLabels.size() + " mRoomId="+ mRoomId);
        if (mLabels.get(mRoomId).isEmpty()) {
            return;
        }

        List<Point3> points3 = new LinkedList<>();
        List<String> names = new LinkedList<>();
        for (Label label : mLabels.get(mRoomId)) {
            points3.add(label.getPoint3());
            names.add(label.getName());
        }

        MatOfPoint2f planePoints = new MatOfPoint2f();

        Mat K = camera.K();
        // change the base coordiate of the transform from the world coordinate to the
        // image coordinate
        Transform poseInCamera = pose.inverse();
        Mat R = new Mat(3, 3, CV_64FC1);
        R.put(0, 0, new double[]{poseInCamera.r11()});
        R.put(0, 1, new double[]{poseInCamera.r12()});
        R.put(0, 2, new double[]{poseInCamera.r13()});
        R.put(1, 0, new double[]{poseInCamera.r21()});
        R.put(1, 1, new double[]{poseInCamera.r22()});
        R.put(1, 2, new double[]{poseInCamera.r23()});
        R.put(2, 0, new double[]{poseInCamera.r31()});
        R.put(2, 1, new double[]{poseInCamera.r32()});
        R.put(2, 2, new double[]{poseInCamera.r33()});
        Mat rvec = new Mat(1, 3, CV_64FC1);
        Rodrigues(R, rvec);
        Mat tvec = new Mat(1, 3, CV_64FC1);
        tvec.put(0, 0, new double[]{poseInCamera.x(), poseInCamera.y(), poseInCamera.z()});

        // do the projection
        MatOfPoint3f objectPoints = new MatOfPoint3f();
        objectPoints.fromList(points3);
        projectPoints(objectPoints, rvec, tvec, K, new MatOfDouble(), planePoints);

        // find points in the image
        Point center = new Point(width / 2, height / 2);
        List<Point> points2 = planePoints.toList();
        Map<Float, Pair<String, Point>> resultMap = new HashMap<>();
        for (int i = 0; i < points3.size(); ++i) {
            String name = names.get(i);

            if (isInFrontOfCamera(points3.get(i), poseInCamera)) {
                Float dist = (float)Math.sqrt(points2.get(i).dot(center));
                resultMap.put(dist, new Pair<String, Point>(name, points2.get(i)));
            }
        }

        float size;
        if (width > height) {
            size = height / 10;
        } else {
            size = width / 10;
        }
        for (Map.Entry<Float, Pair<String, Point>> entry : resultMap.entrySet()) {
            Pair<String, Point> result = entry.getValue();
            nameList.add(result.first);
            XList.add((float)result.second.x);
            YList.add((float)result.second.y);
            SizeList.add(size);
        }
        if(nameList.isEmpty()) {
            nameList.add("None");
            XList.add(-1.0f);
            YList.add(-1.0f);
        }
    }

    private boolean isInFrontOfCamera(Point3 point3, Transform pose) {
        double z = pose.r31() * point3.x + pose.r32() * point3.y + pose.r33() * point3.z + pose.z();
        return z > 0;
    }

    public interface StateCallback {
        void onObjectIdentified(List<String> name, List<Float> x, List<Float> y, List<Float> size);
        LocTracker.ImuPose getNearestPoseAndTime(long time);
        LocTracker.ImuPose getNearestPoseAndTimeForOF(long time);
        LocTracker.ImuPose getNearestPoseAndTimeForOF2(long time);
        LocTracker.ImuPose getLatestPoseAndTime();
        void resetLocTrackerLinearMoveCount();
        int getLocTrackerLinearMoveCount();
    }


    private Transform getPoseSI() {
        Camera camera = Camera.getInstance();
        int deviceOrientation = camera.getDeviceOrientation();
        if(deviceOrientation == 0) {
            return new Transform(1,0,0,0,
                                 0,1,0,0,
                                 0,0,1,0);
        } else if(deviceOrientation == 90) {
            return new Transform(0,1,0,0,
                                 -1,0,0,0,
                                 0,0,1,0);
        } else if(deviceOrientation == 180) {
            return new Transform(-1,0,0,0,
                                 0,-1,0,0,
                                 0,0,1,0);
        } else if(deviceOrientation == 270) {
            return new Transform(0,-1,0,0,
                                 1,0,0,0,
                                 0,0,1,0);
        } else {
            throw new IllegalStateException("Rotation Angle is not multiple of 90");
        }
    }

    private Transform getPosePS() {
        return new Transform(1,0,0,0,
                             0,-1,0,0,
                             0,0,-1,0);
    }

    //A tuple class that contains(TimeStamp t, PoseAP from LocTracker at TimeStamp t, PoseMI from server with the image at TimeStamp t)
    private class Poses {
        boolean PoseAPCorected;
        public Transform PoseAP;
        public Transform PoseMI;
        public Transform PoseSI;

        public Poses(boolean poseAPCorected, Transform poseAP, Transform poseMI, Transform poseSI) {
            PoseAPCorected = poseAPCorected;
            PoseAP = poseAP;
            PoseMI = poseMI;
            PoseSI = poseSI;
        }
    }

    @Override
    public void onStancePhaseCorrection(List<LocTracker.ImuPose> posesRecord) {
        System.out.println("debug6 onStancePhaseCorrection coems in");
        List<Long> removingEntries = new LinkedList<>();
        stancePhaseJustCorrected = true;
        System.out.println("debug6 onStancePhaseCorrection happended");
        for(LocTracker.ImuPose node : posesRecord) {
            if(mPosesMap.keySet().contains(node.time)) {

                Poses poses = mPosesMap.get(node.time);
                poses.PoseAP = node.pose;
                poses.PoseAPCorected = true;
                if(poses.PoseMI != null) {
                    if(node.time > mCurrentPoseMATime) {
                        Transform PoseSIinv0 = poses.PoseSI.inverse();
                        Transform PoseAPinv = poses.PoseAP.inverse();
                        Transform PosePSinv0 = getPosePS().inverse();
                        mCurrentPoseMATime = node.time;
                        mCurrentPoseMA = poses.PoseMI.multiply(PoseSIinv0).multiply(PosePSinv0).multiply(PoseAPinv);


                    }
                    removingEntries.add(node.time);
                }
            }
        }
        for(long entry : removingEntries) {
            mPosesMap.remove(entry);
        }
    }


    private final int NEIGHBOR_COUNT = 5;

    private List<Integer> nearestNPointIndex(List<Point> points, Point target, List<Point> points2) {
        if(points.size() < NEIGHBOR_COUNT) {
            return null;
        }
        PriorityQueue<Integer> pointsQueue = new PriorityQueue<>(points.size(),new Comparator<Integer>() {
            @Override
            public int compare(Integer integer, Integer t1) {
                Point pt = points.get(integer.intValue());
                double distSquare = (target.x - pt.x) * (target.x - pt.x) + (target.y - pt.y) * (target.y - pt.y);
                Point pt2 = points.get(t1.intValue());
                double distSquare2 = (target.x - pt2.x) * (target.x - pt2.x) + (target.y - pt2.y) * (target.y - pt2.y);
                if(distSquare < distSquare2) {
                    return -1;
                } else if (distSquare == distSquare2) {
                    return 0;
                } else {
                    return 1;
                }
            }
        });

        for(int i = 0; i < points.size(); i++) {
            pointsQueue.add(i);
        }

        List<Integer> closestNPointsIndex = new ArrayList<>();


        for(int i = 0; i < NEIGHBOR_COUNT; i++) {
            closestNPointsIndex.add(pointsQueue.remove());
        }


        return closestNPointsIndex;
    }

    private Point predictLabelWithNearestPoints(List<Integer> closestNPointsIndex, List<Point> points, Point target, List<Point> points2) {
        List<Double> predictedXList = new ArrayList<>();
        List<Double> predictedYList = new ArrayList<>();
        for(int index : closestNPointsIndex) {
            Point pt = points.get(index);
            Point pt2 = points2.get(index);
            double deltaX = pt.x - target.x;
            double newX = pt2.x - deltaX;
            predictedXList.add(newX);
            double deltaY = pt.y - target.y;
            double newY = pt2.y - deltaY;
            predictedYList.add(newY);
        }
        System.out.println("debug6 std is " + (std(predictedXList) + std(predictedYList)));
        if(std(predictedXList) + std(predictedYList) > opticalFlowStdThresh) {
            opticalFlowConfident = 0;
        } else {
            double newConfident = 1 -  (std(predictedXList) + std(predictedYList)) / opticalFlowStdThresh;
            if(newConfident < opticalFlowConfident) {
                opticalFlowConfident = newConfident;
            }
        }
        return new Point(mean(predictedXList), mean(predictedYList));
    }

    private double mean(List<Double> values) {
        double total = 0;
        for(double val : values) {
            total += val;
        }
        return  total/values.size();
    }
    private double std(List<Double> values) {
       double mu = mean(values);
        double variance = 0;
        for(double val : values) {
            variance += Math.pow(val - mu, 2);
        }
        return Math.pow(variance/values.size(), 0.5);
    }

    private int nearestPointIndex(List<Point> points, Point target) {
        double minDistSquare = Double.MAX_VALUE;
        int minIndex = 0;
        for(int i = 0; i < points.size(); i++) {
            Point pt = points.get(i);
            double distSquare = (target.x - pt.x) * (target.x - pt.x) + (target.y - pt.y) * (target.y - pt.y);
            if(distSquare < minDistSquare) {
                minIndex = i;
                minDistSquare = distSquare;
            }
        }
        System.out.println("nearest point index = " + minIndex + " in one");
        return minIndex;
    }


    /**
     * Takes an Android Image in the YUV_420_888 format and returns a byte array.
     * ref: http://stackoverflow.com/questions/30510928/convert-android-camera2-api-yuv-420-888-to-rgb
     *
     * @param image Image in the YUV_420_888 format
     * @return bytes that contains the image data in greyscale
     */
    private byte[] imageToBytes(Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            return null;
        }

        int bytesPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8;
        Image.Plane yPlane = image.getPlanes()[0]; // we only need a gray picture
        int pixelStride = yPlane.getPixelStride();
        if (bytesPerPixel != 1 || pixelStride != 1) { // they are guaranteed to be both 1 in Y plane
            throw new RuntimeException("Wrong image format");
        }

        ByteBuffer buffer = yPlane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] data = new byte[width * height];
        buffer.get(data);

        return data;
    }
}
