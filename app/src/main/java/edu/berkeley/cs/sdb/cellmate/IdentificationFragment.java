package edu.berkeley.cs.sdb.cellmate;

import edu.berkeley.cs.sdb.snaplink.*;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
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
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
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

public class IdentificationFragment extends Fragment {
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
    //Use the time that sending the message as the id for pose
    private HashMap<Long, Transform> mPoseAPMap;
    private HashMap<Integer, List<Label>> mLabels;
    private int mRoomId;
    private Long mLastTime;
    // We keep a toast reference so it can be updated instantly
    private Toast mToast;
    private String mTargetObject;
    private List<String> mRecentObjects;
    private List<String> mDescriptions = new ArrayList<>();
    private byte[] mLatestImageData;
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

//                        mRoomId = value.getDbId();
//                        Transform Plocal0 = mPoseAPMap.get(value.getRequestId());
//                        Transform Plocal0inv = Plocal0.inverse();
//                        mPoseAPMap.remove(value.getRequestId());
//                        Transform Plocal1 = mStateCallback.getPoseAP();
//                        Transform Pmodel0 = getPoseFromMessage(value);
//                        Transform T = Pmodel0.multiply(Plocal0inv);
//                        Transform Pmodel1 = T.multiply(Plocal1);


                        Transform PoseMI = getPoseFromMessage(value);
                        Transform PoseSI = getPoseSI();
                        Transform PoseMS = PoseMI.multiply(PoseSI.inverse());

                        ArrayList<String> nameListOld = new ArrayList<>();
                        ArrayList<Float> XListOld = new ArrayList<>();
                        ArrayList<Float> YListOld = new ArrayList<>();
                        ArrayList<Float> SizeListOld = new ArrayList<>();

                        visibility(PoseMS, nameListOld, XListOld, YListOld, SizeListOld);

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
                        mStateCallback.onObjectIdentified(nameListOld, XListOld, YListOld, SizeListOld);
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
            Image image = reader.acquireLatestImage();

            ByteString data;
            if (image == null) {
                return;
            }

            data = ByteString.copyFrom(image.getPlanes()[0].getBuffer());
            image.close();
            mLatestImageData = new byte[data.size()];
            data.copyTo(mLatestImageData, 0);

            if (time - mLastTime > REQUEST_INTERVAL) {
                mPoseAPMap.put(time, mStateCallback.getPoseAP());
                Runnable senderRunnable = new Runnable() {
                    ByteString mData;
                    int mRotateClockwiseAngle;

                    @Override
                    public void run() {
                        sendRequestToServer(mData, mRotateClockwiseAngle, time);
                    }

                    public Runnable init(ByteString data) {
                        mData = data;
                        Camera camera = Camera.getInstance();
                        //Angles the data image need to rotate right to have the correct direction
                        mRotateClockwiseAngle = (camera.getDeviceOrientation() + 90) % 360;
                        return (this);
                    }
                }.init(data);
                mHandler.post(senderRunnable);
                //sendRequestToServer(data);
                mLastTime = time;
            } else {
                try {
                    image.close();
                } catch (NullPointerException e) {

                }

            }
        }
    };
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

    private void sendRequestToServer(ByteString data, int rotateClockwiseAngle, long messageId) {
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

            SnapLinkProto.LocalizationRequest request = SnapLinkProto.LocalizationRequest.newBuilder()
                    .setImage(data)
                    .setRequestId(messageId)
                    .setCamera(SnapLinkProto.CameraModel.newBuilder().setCx(mCx).setCy(mCy).setFx(mFx).setFy(mFy).build())
                    .setOrientation(orientation)
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

        mPoseAPMap = new HashMap<>();
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
        System.out.println("3");
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



        poseInCamera.print();
        double[] data = new double[3];
        rvec.get(0,0,data);
        System.out.println("rvec: " + " " + data[0] + " " + data[1] + " " + data[2]);
        tvec.get(0,0,data);
        System.out.println("tvec: " + " " + data[0] + " " + data[1] + " " + data[2]);
        float[] dataF = new float[9];
        K.get(0,0,dataF);
        System.out.println("K: " + " " + dataF[0] + " " + dataF[1] + " " + dataF[2] + "\n "
                + dataF[3] + " " + dataF[4] + " " + dataF[5] + "\n "
                + dataF[6] + " " + dataF[7] + " " + dataF[8] + "\n ");
        List<Point3> pointsList = objectPoints.toList();
        for(int i = 0; i < pointsList.size(); i++) {
            System.out.println(names.get(i) + " " + pointsList.get(i).toString());
        }

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
        Transform getPoseAP();
    }

//    private void rotateBack(List<Float> xList, List<Float> yList, double angle,
//                            int width, int height) {
//        for (int i = 0; i < xList.size(); i++) {
//            float oldX = xList.get(i);
//            float oldY = yList.get(i);
//            if (oldX == -1) {
//                continue;
//            }
//            if (angle == 90) {
//                // do nothing
//            } else if (angle == 180) {
//                xList.set(i, oldY);
//                yList.set(i, width - oldX);
//            } else if (angle == 270) {
//                xList.set(i, width - oldX);
//                yList.set(i, height - oldY);
//            } else {
//                // angle = 0
//                xList.set(i, height - oldY);
//                yList.set(i, oldX);
//            }
//        }
//    }
//
//    private void setIntrinsics(double width, double height, double angle, CameraModel model) {
//        double oldCx = model.getCx();
//        double oldCy = model.getCy();
//        double newCx = 0;
//        double newCy = 0;
//        if (angle == 0) {
//            newCx = oldCx;
//            newCy = oldCy;
//        } else if (angle == 90) {
//            newCx = width - oldCy;
//            newCy = oldCx;
//        } else if (angle == 180) {
//            newCx = width - oldCx;
//            newCy = height - oldCy;
//        } else if (angle == 270) {
//            newCx = oldCy;
//            newCy = height - oldCx;
//        }
//        model.setCx((float)newCx);
//        model.setCy((float)newCy);
//    }

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


}
