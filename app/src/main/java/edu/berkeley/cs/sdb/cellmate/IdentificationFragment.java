package edu.berkeley.cs.sdb.cellmate;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import org.opencv.android.InstallCallbackInterface;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point3;
import org.opencv.core.Point;

import static org.opencv.calib3d.Calib3d.Rodrigues;
import static org.opencv.core.Core.norm;
import static org.opencv.core.CvType.CV_32F;
import static org.opencv.core.CvType.CV_64F;
import static org.opencv.core.CvType.CV_64FC1;
import static org.opencv.calib3d.Calib3d.projectPoints;

public class IdentificationFragment extends Fragment {
    private Handler mHandler;
    private boolean mAttached;
    private static final String LOG_TAG = "CellMate";
    private static final String CONTROL_TOPIC_PREFIX = "410.dev/plugctl/front/s.powerup.v0/";
    private static final String CONTROL_TOPIC_SUFFIX = "/i.binact/slot/state";
    private int REQUEST_INTERVAL = 500;
    private final int CIRCULAR_BUFFER_LENGTH = 10;
    String mHost;
    String mPort;
    double mFx;
    double mFy;
    double mCx;
    double mCy;
    private StateCallback mStateCallback;
    ManagedChannel mChannel;
    StreamObserver<CellmateProto.ClientQueryMessage> mRequestObserver;
    TextView mTextView;
    TextView mInforText;

    //Use the time that sending the message as the id for pose
    private HashMap<Long, Transform> mPoseMap;
    private HashMap<Integer, List<Label>> mLabels;
    private  int mRoomId;


    private Long mLastTime;
    // We keep a toast reference so it can be updated instantly
    private Toast mToast;

    private String mTargetObject;

    private Transform getPoseFromMessage(CellmateProto.ServerRespondMessage value) {
        return new Transform(value.getR11(), value.getR12(), value.getR13(), value.getTx(),
                             value.getR21(), value.getR22(), value.getR23(), value.getTy(),
                             value.getR31(), value.getR32(), value.getR33(), value.getTz());
    }
    private List<String> mRecentObjects;
    StreamObserver<CellmateProto.ServerRespondMessage> mResponseObserver = new StreamObserver<CellmateProto.ServerRespondMessage>() {
        @Override
        public void onNext(CellmateProto.ServerRespondMessage value) {
            Log.d(LOG_TAG, "TAG_TIME response " + System.currentTimeMillis()); // got response from server
            final Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    try {
                        mRoomId = value.getRoomId();
                        Transform Plocal0 = mPoseMap.get(value.getId());
                        Transform Plocal0inv = Plocal0.inverse();
                        mPoseMap.remove(value.getId());
                        Transform Plocal1 = mStateCallback.getPose();
                        Transform Pmodel0 = getPoseFromMessage(value);
                        Transform deltaLocalTransform = Plocal1.multiply(Plocal0inv);
                        Transform Pmodel1 = deltaLocalTransform.multiply(Pmodel0);

                        ArrayList<String> nameList = new ArrayList<>();
                        ArrayList<Double> XList = new ArrayList<>();
                        ArrayList<Double> YList = new ArrayList<>();
                        ArrayList<Double> SizeList = new ArrayList<>();
                        visibility(Pmodel1, nameList, XList, YList, SizeList);
                        mStateCallback.onObjectIdentified(value.getNameList(), value.getXList(), value.getYList(),value.getSizeList() , value.getWidth(), value.getHeight());
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
            showToast("Server is disconnected due to grpc error", Toast.LENGTH_LONG);
            t.printStackTrace();
        }

        @Override
        public void onCompleted() {
            showToast("Server is disconnected due to grpc complete", Toast.LENGTH_LONG);
        }
    };



    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = (ImageReader reader) -> {
        if(mAttached) {
            Long time = System.currentTimeMillis();
            Image image = reader.acquireLatestImage();
            if (time - mLastTime > REQUEST_INTERVAL) {
                ByteString data = ByteString.copyFrom(image.getPlanes()[0].getBuffer());
                image.close();
                mPoseMap.put(time, mStateCallback.getPose());
                Runnable senderRunnable = new Runnable() {
                    ByteString mData;
                    int mRotateClockwiseAngle;
                    @Override
                    public void run() {
                        sendRequestToServer(mData,mRotateClockwiseAngle,time);
                    }

                    public Runnable init(ByteString data) {
                        mData = data;
                        Camera camera = Camera.getInstance();
                        //Angles the data image need to rotate right to have the correct direction
                        mRotateClockwiseAngle = (camera.getDeviceOrientation() + 90) % 360;
                        return(this);
                    }
                }.init(data);
                mHandler.post(senderRunnable);
                //sendRequestToServer(data);
                mLastTime = time;
            } else {
                try{
                    image.close();
                } catch (NullPointerException e) {

                }

            }
        }
    };

    public static IdentificationFragment newInstance() {
        return new IdentificationFragment();
    }

    private void sendRequestToServer(ByteString data, int rotateClockwiseAngle, long messageId) {
        Activity activity = getActivity();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        double Fx = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_fx_key), activity.getString(R.string.camera_fx_val)));
        double Fy = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_fy_key), activity.getString(R.string.camera_fy_val)));
        double Cx = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_cx_key), activity.getString(R.string.camera_cx_val)));
        double Cy = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_cy_key), activity.getString(R.string.camera_cy_val)));
        String grpcCellmateServerAddr = preferences.getString(getString(R.string.grpc_server_addr_key), getString(R.string.grpc_server_addr_val));
        String grpcCellmateServerPort = preferences.getString(getString(R.string.grpc_server_port_key), getString(R.string.grpc_server_port_val));
        if (mHost != grpcCellmateServerAddr || mPort != grpcCellmateServerPort ||
                mFx != Fx || mFy != Fy || mCx != Cx || mCy != Cy) {
            mRequestObserver.onCompleted();
            copyAllPreferenceValue();
            mRequestObserver = createNewRequestObserver();
        }

        try {
            CellmateProto.ClientQueryMessage request = CellmateProto.ClientQueryMessage.newBuilder()
                    .setImage(data)
                    .setFx(mFx)
                    .setFy(mFy)
                    .setCx(mCx)
                    .setCy(mCy)
                    .setId(messageId)
                    .setAngle(rotateClockwiseAngle)
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
        mFx = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_fx_key), activity.getString(R.string.camera_fx_val)));
        mFy = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_fy_key), activity.getString(R.string.camera_fy_val)));
        mCx = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_cx_key), activity.getString(R.string.camera_cx_val)));
        mCy = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_cy_key), activity.getString(R.string.camera_cy_val)));
        mHost = preferences.getString(getString(R.string.grpc_server_addr_key), getString(R.string.grpc_server_addr_val));
        mPort = preferences.getString(getString(R.string.grpc_server_port_key), getString(R.string.grpc_server_port_val));
    }

    StreamObserver<CellmateProto.ClientQueryMessage> createNewRequestObserver() {
        return GrpcServiceGrpc.newStub(mChannel).onClientQuery(mResponseObserver);
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            mStateCallback = (StateCallback) context;
        } catch (ClassCastException e) {
            throw new RuntimeException(e);
        }
    }

    private View mView;
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mView = inflater.inflate(R.layout.identification_fragment, container, false);
        return mView;
    }

    ImageReader mImageReader;
    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextView = (TextView) view.findViewById(R.id.textInIdentiFrag);
        mInforText = (TextView) view.findViewById(R.id.inforText);

        setHasOptionsMenu(true);

        mLastTime = System.currentTimeMillis();
        mToast = Toast.makeText(getActivity(), "", Toast.LENGTH_SHORT);
        Camera camera = Camera.getInstance();
        Size captureSize = camera.getCaptureSize();
        mImageReader = ImageReader.newInstance(captureSize.getWidth(),captureSize.getHeight(),
                ImageFormat.JPEG, /*maxImages*/2);
        mImageReader.setOnImageAvailableListener(
                mOnImageAvailableListener, null);
        mHandler = new Handler();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mTargetObject = null;
        mRecentObjects = new ArrayList<>(CIRCULAR_BUFFER_LENGTH);

        copyAllPreferenceValue();

        mPoseMap = new HashMap<>();
        mLabels = new HashMap<>();
        System.out.println("mhost is --------------" + mHost);
        System.out.println("mhost is --------------" + Integer.valueOf(mPort));
        mChannel = ManagedChannelBuilder.forAddress(mHost, Integer.valueOf(mPort)).usePlaintext(true).build();
        mRequestObserver = createNewRequestObserver();
        GrpcServiceGrpc.GrpcServiceBlockingStub stub = GrpcServiceGrpc.newBlockingStub(mChannel);
        CellmateProto.Empty message = CellmateProto.Empty.newBuilder().build();
        CellmateProto.Models models = stub.getModels(message);
        List<CellmateProto.Model> modelList = models.getModelsList();
        for(CellmateProto.Model model : modelList) {
            List<Label> labelsInModel = new ArrayList<>();
            for(CellmateProto.Label label: model.getLabelsList()) {
                Point3 position = new Point3(label.getX(), label.getY(), label.getZ());
                labelsInModel.add(new Label(label.getRoomId(), position, label.getName()));
                System.out.println(label.getName());
            }
            mLabels.put(model.getId(), labelsInModel);
        }
    }

    LoaderCallbackInterface mLoaderCallback = new LoaderCallbackInterface() {
        @Override
        public void onManagerConnected(int i) {
            onresumeCallback();
        }

        @Override
        public void onPackageInstall(int i, InstallCallbackInterface installCallbackInterface) {

        }
    };
    private void onresumeCallback() {
        Activity activity = getActivity();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        double Fx = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_fx_key), activity.getString(R.string.camera_fx_val)));
        if(Fx > 0) {
            mInforText.setVisibility(View.GONE);
        } else {
            mInforText.setText("Camera is uncalibrated, for better use of the system,\n please go to calibration mode and calibrate");
        }
        double frameRate = Double.parseDouble(preferences.getString(activity.getString(R.string.query_rate_key), activity.getString(R.string.query_rate_val)));
        REQUEST_INTERVAL = (int)((1.0/frameRate) * 1000);
        copyAllPreferenceValue();
        mRequestObserver = createNewRequestObserver();
        mAttached = true;
        Camera camera = Camera.getInstance();
        Log.i("CellMate","control fragment register++++");
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
        Log.i("CellMate","control fragment unregister");
        camera.unregisterPreviewSurface(mImageReader.getSurface());

        mHandler.removeCallbacksAndMessages(null);
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

    private void visibility(Transform pose, List<String> nameList, List<Double> XList, List<Double>YList, List<Double>SizeList) {
        CameraModel camera = new CameraModel("CameraModel",
                                             Camera.getInstance().getCaptureSize(),
                                             (float)mFx, (float)mFy,
                                             (float)mCx, (float)mCy);
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
        tvec.put(0, 0, new double[]{poseInCamera.x(),poseInCamera.y(),poseInCamera.z()});

        // do the projection
        MatOfPoint3f objectPoints = new MatOfPoint3f();
        objectPoints.fromList(points3);
        projectPoints(objectPoints, rvec, tvec, K, new MatOfDouble(), planePoints);

        // find points in the image
        int width = camera.getImageSize().getWidth();
        int height = camera.getImageSize().getHeight();
        Point center = new Point(width / 2, height / 2);
        List<Point> points2 = planePoints.toList();
        Map<Double, Pair<String, Point>> resultMap = new HashMap<>();
        for (int i = 0; i < points3.size(); ++i) {
            String name = names.get(i);

            if (isInFrontOfCamera(points3.get(i), poseInCamera)) {
                double dist = Math.sqrt(points2.get(i).dot(center));
                resultMap.put(dist, new Pair<String, Point>(name, points2.get(i)));
            }
        }

        double size;
        if(width>height) {
            size = height/10;
        } else {
            size = width/10;
        }
        for(Map.Entry<Double, Pair<String, Point>> entry : resultMap.entrySet()) {
            Pair<String, Point> result = entry.getValue();
            nameList.add(result.first);
            XList.add(result.second.x);
            YList.add(result.second.y);
            SizeList.add(size);
        }
    }

    private boolean isInFrontOfCamera(Point3 point3, Transform pose) {
        double z = pose.r31() * point3.x + pose.r32() * point3.y + pose.r33() * point3.z + pose.z();
        return z > 0;
    }

    public interface StateCallback {
        void onObjectIdentified(List<String> name, List<Double> x, List<Double> y, List<Double> size, double width, double height);
        Transform getPose();
    }
}
