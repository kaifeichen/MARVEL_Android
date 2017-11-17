package edu.berkeley.cs.sdb.cellmate;

import edu.berkeley.cs.sdb.cellmate.algo.Localizer.LocTracker;
import edu.berkeley.cs.sdb.cellmate.data.KeyFrame;
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



import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
    private float mImagePreviewScale = 1;
    private final int ANGLE_DIFF_LIMIT = 40;
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
    private final int mFrameCacheForOFLimit = 3;

    long mCurrentPoseMATime;
    Transform mCurrentPoseMA;


    HashMap<Long, Poses> mPosesMap;

    StreamObserver<SnapLinkProto.LocalizationResponse> mResponseObserver = new StreamObserver<SnapLinkProto.LocalizationResponse>() {
        @Override
        public void onNext(SnapLinkProto.LocalizationResponse value) {
            Log.d(LOG_TAG, "TAG_TIME response " + System.currentTimeMillis()); // got response from server
            final Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    try {
                        if(!value.getSuccess()) {
                            return;
                        }
//                        byte[] data = mLatestImageData;
//                        Bitmap bitmapImage = BitmapFactory.decodeByteArray(data, 0, data.length, null);
                        Transform currentPoseMS = null;
                        if(value.getRequestId() > mCurrentPoseMATime) {
                            Transform PoseMI = getPoseFromMessage(value);
                            Poses poses = mPosesMap.get(value.getRequestId());
                            poses.PoseMI = PoseMI;
                            Transform PoseSIinv = poses.PoseSI.inverse();
                            Transform PoseAPinv = poses.PoseAP.inverse();
                            Transform PosePSinv = getPosePS().inverse();
                            Long oldPoseMATime = mCurrentPoseMATime;
                            mCurrentPoseMATime = value.getRequestId();
                            mCurrentPoseMA = PoseMI.multiply(PoseSIinv).multiply(PosePSinv).multiply(PoseAPinv);
                            Transform currentPoseAP = mStateCallback.getLatestPoseAndTime().pose;
                            currentPoseMS = mCurrentPoseMA.multiply(currentPoseAP).multiply(getPosePS());

                            if(poses.PoseAPCorected) {
                                //remove this query event poses if it is corrected by LocTracker already
                                mPosesMap.remove(value.getRequestId());
                            }
                            if(mPosesMap.containsKey(oldPoseMATime)) {
                                //remove old query event poses since it is no longer meaningful, given that we have a newer one
                                mPosesMap.remove(oldPoseMATime);
                            }
                        } else {
                            //remove this query event poses if it is outdated
                            mPosesMap.remove(value.getRequestId());
                        }

                        ArrayList<String> nameListOld = new ArrayList<>();
                        ArrayList<Float> XListOld = new ArrayList<>();
                        ArrayList<Float> YListOld = new ArrayList<>();
                        ArrayList<Float> SizeListOld = new ArrayList<>();


                        mRoomId = value.getDbId();
                        visibility(currentPoseMS, nameListOld, XListOld, YListOld, SizeListOld);

//                        System.out.println("Local visibility result:");
//                        for(int i = 0; i < nameListOld.size(); i++) {
//                            System.out.println(nameListOld.get(i) + " " + XListOld.get(i) + " " + YListOld.get(i));
//                        }
//                        System.out.println("Remote visibility result:");
//                        for(int i = 0; i < value.getItemsList().size(); i++) {
//                            SnapLinkProto.Item item = value.getItemsList().get(i);
//                            System.out.println(item.getName() + " " + item.getX() + " " + item.getY());
//                        }
//
//                        ArrayList<String> nameList = new ArrayList<>();
//                        ArrayList<Float> XList = new ArrayList<>();
//                        ArrayList<Float> YList = new ArrayList<>();
//                        ArrayList<Float> SizeList = new ArrayList<>();
//                        visibility(Pmodel1, nameList, XList, YList, SizeList, value.getAngle(),value.getWidth0(), value.getHeight0());
//                        rotateBack(XList,YList,value.getAngle(),(int)value.getWidth0(), (int)value.getHeight0());
//                        System.out.println("predicted visibility result:");
//                        for(int i = 0; i < nameList.size(); i++) {
//                            System.out.println(nameList.get(i) + " " + XList.get(i) + " " + YList.get(i));
//                        }


//                        String description = "";
//                        String title = String.valueOf(value.getRequestId());
//                        description += "id:" + title + "\n";
//                        description += "Old: \n";
//                        for (int i = 0; i < nameListOld.size(); i++) {
//                            description += nameListOld.get(i) + " " + XListOld.get(i) + " " + YListOld.get(i) + " ";
//                        }
//                        description += "\n";
//                        description += "New: \n";
//                        for (int i = 0; i < nameList.size(); i++) {
//                            description += nameList.get(i) + " " + XList.get(i) + " " + YList.get(i) + " ";
//                        }
//                        description += "\n";
//
//                        mDescriptions.add(description);

//                        FileOutputStream fos = null;
//
//                        File myPath = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "/imu/" + title + ".jpg");
//
//                        try {
//                            fos = new FileOutputStream(myPath);
//                            // Use the compress method on the BitMap object to write image to the OutputStream
//                            bitmapImage.compress(Bitmap.CompressFormat.JPEG, 100, fos);
//                        } catch (Exception e) {
//                            e.printStackTrace();
//                        } finally {
//                            try {
//                                fos.close();
//                            } catch (IOException e) {
//                                e.printStackTrace();
//                            }
//                        }
//                        MediaStore.Images.Media.insertImage(getActivity().getContentResolver(), bitmapImage, title , "");

//                        mStateCallback.onObjectIdentified(value.getItemsList(), value.getWidth(), value.getHeight());
                        nameListOld.add("red");
                        mStateCallback.onObjectIdentified(nameListOld, XListOld, YListOld, SizeListOld);
                        mStateCallback.setGroundTruth(nameListOld, XListOld, YListOld, SizeListOld);
//                        mStateCallback.onObjectIdentified(nameList, XList, YList, SizeList, value.getWidth(), value.getHeight());
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


    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = (ImageReader reader) -> {

        if (mAttached) {
            System.out.println("ImageReader.OnImageAvailableListener");
            Long time = System.currentTimeMillis();

            if(mCurrentPoseMA != null) {
                Transform currentPoseAP = mStateCallback.getLatestPoseAndTime().pose;
                Transform currentPoseMS = mCurrentPoseMA.multiply(currentPoseAP).multiply(getPosePS());
                ArrayList<String> nameList = new ArrayList<>();
                ArrayList<Float> XList = new ArrayList<>();
                ArrayList<Float> YList = new ArrayList<>();
                ArrayList<Float> SizeList = new ArrayList<>();
                visibility(currentPoseMS, nameList, XList, YList, SizeList);
                Runnable drawingRunnable = new Runnable() {
                    @Override
                    public void run() {
                        mStateCallback.onObjectIdentified(nameList,XList,YList,SizeList);
                    }
                };
                mHandler.post(drawingRunnable);

            }

            Image image = reader.acquireLatestImage();


            ByteString data;
            if (image == null) {
                return;
            }

            long imageGeneratedTime = image.getTimestamp();
            data = ByteString.copyFrom(image.getPlanes()[0].getBuffer());
            image.close();

            //Construct the frame for current image
            mLatestImageData = new byte[data.size()];
            data.copyTo(mLatestImageData, 0);
            Camera camera = Camera.getInstance();
            int rotateClockwiseAngle = (camera.getDeviceOrientation() + 90) % 360;
            KeyFrame curFrame = new KeyFrame(mStateCallback.getNearestPoseAndTime(imageGeneratedTime),
                    mLatestImageData,
                    rotateClockwiseAngle);


//            Optical Flow
            mFrameCacheForOF.add(curFrame);
            if(mFrameCacheForOF.size() >= mFrameCacheForOFLimit) {
                KeyFrame firstFrame = mFrameCacheForOF.get(0);

                //choose which frame in cache to do optical flow, record it as next anchor frame
                int index;
                for(index = mFrameCacheForOF.size() - 1; index > 1; index--) {
                    KeyFrame frame_i =  mFrameCacheForOF.get(index);
                    double angleDiff = frame_i.angleDiffInDegree(firstFrame);
                    boolean isBlur = curFrame.isBlur();
                    if(angleDiff > ANGLE_DIFF_LIMIT || isBlur) {
                        continue;
                    } else {
                        break;
                    }
                }

                //Keep on popping the cache to the newest anchor frame
                for(int j = 0; j < index; j++) {
                    mFrameCacheForOF.remove(0);
                }

                //doing the optical flow
                Runnable opticalFlowRunnable = new Runnable() {
                    @Override
                    public void run() {
                        opticalFlow(mFrameCacheForOF.get(0).getData());
                    }
                };
                mHandler.post(opticalFlowRunnable);
            }
//
//            Runnable opticalFlowRunnable = new Runnable() {
//                @Override
//                public void run() {
//                    opticalFlow(mLatestImageData);
//                }
//            };
//            mHandler.post(opticalFlowRunnable);


            //Offloading
            mFrameCache.add(curFrame);
            if(mFrameCache.size() > mFrameCacheLimit) {
                mFrameCache.remove(0);
            }

            if (time - mLastTime > REQUEST_INTERVAL) {
                Runnable senderRunnable = new Runnable() {
                    @Override
                    public void run() {
                        sortFramesToFindBest();
                        KeyFrame frameToSend = mFrameCache.get(0);
                        Transform pose = frameToSend.getImuPose().pose;
                        mPosesMap.put(frameToSend.getImuPose().time, new Poses(false,pose,null,getPoseSI()));

                        List<ByteString> datas = new ArrayList<>();
                        List<Integer> rotateClockwiseAngles =  new ArrayList<>();
                        List<Long> times =  new ArrayList<>();
                        List<Float> blurness = new ArrayList<>();
                        List<SnapLinkProto.Matrix> poses = new ArrayList<>();


                        for(int i = 0; i < mFrameCache.size(); i++) {
                            datas.add(ByteString.copyFrom(mFrameCache.get(i).getData()));
                            rotateClockwiseAngles.add(mFrameCache.get(i).getmRotateClockwiseAngle());
                            times.add(mFrameCache.get(i).getImuPose().time);
                            blurness.add((float)i);
                            mFrameCache.get(i).getImuPose().pose.print();
                            poses.add(getMessageMatrixFromPose(mFrameCache.get(i).getImuPose().pose));
                        }

                        sendRequestToServer(datas, rotateClockwiseAngles, times, blurness, poses);

                    }
                };
                mHandler.post(senderRunnable);
                mLastTime = time;
            } else {
                try {
                    image.close();
                } catch (NullPointerException e) {

                }

            }
        }
    };

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

    private Transform getPoseFromMessage(SnapLinkProto.LocalizationResponse value) {
        SnapLinkProto.Matrix matrix = value.getPose();
        return new Transform(matrix.getData(0), matrix.getData(1), matrix.getData(2), matrix.getData(3),
                matrix.getData(4), matrix.getData(5), matrix.getData(6), matrix.getData(7),
                matrix.getData(8), matrix.getData(9), matrix.getData(10), matrix.getData(11));
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
        mImagePreviewScale = (float)Math.min(captureSize.getWidth(), captureSize.getHeight())/(float)Math.min(previewSize.getWidth(), previewSize.getHeight());
        mImageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(),
                ImageFormat.JPEG, /*maxImages*/2);
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
                mFx/mImagePreviewScale, mFy/mImagePreviewScale, Math.min(mCx,mCy)/mImagePreviewScale, Math.max(mCx,mCy)/mImagePreviewScale);
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
        void setGroundTruth(List<String> name, List<Float> x, List<Float> y, List<Float> size);
        LocTracker.ImuPose getNearestPoseAndTime(long time);
        LocTracker.ImuPose getLatestPoseAndTime();
        void onFlowDetected(LinkedList<Point> ptNewFrameList, LinkedList<Point> ptOldFrameList, int width, int height);
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
        List<Long> removingEntries = new LinkedList<>();
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

    private Mat mRgba, mErodeKernel, matOpFlowPrev, matOpFlowThis;
    private MatOfFloat mMOFerr;
    private MatOfByte mMOBStatus;
    private MatOfPoint2f mMOP2fptsPrev = null, mMOP2fptsThis, mMOP2fptsSafe;
    private MatOfPoint MOPcorners;
    private int x, y, iLineThickness = 3, iGFFTMax = 200;
    private List<Point> cornersThis, cornersPrev;
    private List<Byte> byteStatus;
    private Point pt, pt2;

    private void opticalFlow(byte[] data) {
        //Variables for opencv optical flow
        if(mMOP2fptsPrev == null) {
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

        long time = System.currentTimeMillis();


        mRgba = Imgcodecs.imdecode(new MatOfByte(data), Imgcodecs.CV_LOAD_IMAGE_UNCHANGED);
        System.out.println("decode time" + (System.currentTimeMillis() - time));

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
        mRgba = rotateImage(mRgba, orientation);
        System.out.println("rotate time" + (System.currentTimeMillis() - beforeRotateTime));


        if (mMOP2fptsPrev.rows() == 0) {

            //Log.d("Baz", "First time opflow");
            // first time through the loop so we need prev and this mats
            // plus prev points
            // get this mat
            Imgproc.cvtColor(mRgba, matOpFlowThis, Imgproc.COLOR_RGBA2GRAY);

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
            Imgproc.cvtColor(mRgba, matOpFlowThis, Imgproc.COLOR_RGBA2GRAY);

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


        LinkedList<Point> ptNewFrame = new LinkedList<>();
        LinkedList<Point> ptOldFrame = new LinkedList<>();

        for (x = 0; x < y; x++) {
            if (byteStatus.get(x) == 1) {
                pt = cornersThis.get(x);
                pt2 = cornersPrev.get(x);
                ptNewFrame.add(pt);
                ptOldFrame.add(pt2);
            }
        }

        mStateCallback.onFlowDetected(ptNewFrame, ptOldFrame, mRgba.width(), mRgba.height());
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

}
