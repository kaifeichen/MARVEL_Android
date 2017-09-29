package edu.berkeley.cs.sdb.cellmate;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v13.app.FragmentCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import edu.berkeley.cs.sdb.bosswave.BosswaveClient;
import edu.berkeley.cs.sdb.bosswave.PayloadObject;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point3;
import org.opencv.core.Point;

import static org.opencv.calib3d.Calib3d.Rodrigues;
import static org.opencv.core.Core.norm;
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
    HashMap<Long, float[]> poseMap;


    private Long mLastTime;
    // We keep a toast reference so it can be updated instantly
    private Toast mToast;
//    private final BwPubCmdTask.Listener mBwPubTaskListener = (String response) -> {
//        showToast("Control command sent: " + response, Toast.LENGTH_SHORT);
//        setButtonsEnabled(true, true);
//    };
    // The Bosswave Client used for sending control command
//    private BosswaveClient mBosswaveClient;
//    // Whethre the Bosswave Client is connected
//    private boolean mIsBosswaveConnected;
//    private final BwCloseTask.Listener mBwCloseTaskListener = (boolean success) -> {
//        if (success) {
//            showToast("Bosswave disconnected", Toast.LENGTH_SHORT);
//            mIsBosswaveConnected = false;
//            mBosswaveClient = null;
//            // always try to reconnect
//            initBosswaveClient();
//        } else {
//            showToast("Bosswave close failed", Toast.LENGTH_SHORT);
//        }
//    };
//    private final SharedPreferences.OnSharedPreferenceChangeListener mOnSharedPreferenceChanged = (SharedPreferences sharedPreferences, String key) -> {
//        // when BOSSWAVE router changes, we need to reconnect
//        if(getActivity() != null) {
//            if (key.equals(getString(R.string.bosswave_router_addr_key)) || key.equals(getString(R.string.bosswave_router_port_key)) || key.equals(getString(R.string.bosswave_key_base64_key))) {
//                if (mIsBosswaveConnected) {
//                    new BwCloseTask(mBosswaveClient, mBwCloseTaskListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
//                } else {
//                    initBosswaveClient();
//                }
//            }
//        }
//    };
    // The current recognized object name
    private String mTargetObject;
//    private final BwInitTask.Listener mBwInitTaskListener = (boolean success, BosswaveClient client) -> {
//        if (success) {
//            showToast("Bosswave connected", Toast.LENGTH_SHORT);
//            mBosswaveClient = client;
//            mIsBosswaveConnected = true;
//            if (mTargetObject != null) {
//                setButtonsEnabled(true, true);
//            }
//        } else {
//            mBosswaveClient = null;
//            showToast("Bosswave connection failed", Toast.LENGTH_SHORT);
//        }
//    };
//    private final View.OnClickListener mOnButtonOnClickListener = (View v) -> {
//        if (mIsBosswaveConnected) {
//            setButtonsEnabled(false, false);
//            String topic = CONTROL_TOPIC_PREFIX + mTargetObject + CONTROL_TOPIC_SUFFIX;
//            new BwPubCmdTask(mBosswaveClient, topic, new byte[]{1}, new PayloadObject.Type(new byte[]{1, 0, 1, 0}), mBwPubTaskListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
//        } else {
//            showToast("Bosswave is not connected", Toast.LENGTH_SHORT);
//        }
//    };
//    private final View.OnClickListener mOffButtonOnClickListener = (View v) -> {
//        if (mIsBosswaveConnected) {
//            setButtonsEnabled(false, false);
//            String topic = CONTROL_TOPIC_PREFIX + mTargetObject + CONTROL_TOPIC_SUFFIX;
//            new BwPubCmdTask(mBosswaveClient, topic, new byte[]{0}, new PayloadObject.Type(new byte[]{1, 0, 1, 0}), mBwPubTaskListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
//        } else {
//            showToast("Bosswave is not connected", Toast.LENGTH_SHORT);
//        }
//    };

    private float[] getPoseFromMessage(CellmateProto.ServerRespondMessage value) {
        float[] pose = new float[16];
        pose[0] = value.getR11();
        pose[1] = value.getR21();
        pose[2] = value.getR31();
        pose[3] = 0;
        pose[4] = value.getR12();
        pose[5] = value.getR22();
        pose[6] = value.getR32();
        pose[7] = 0;
        pose[8] = value.getR13();
        pose[9] = value.getR23();
        pose[10] = value.getR33();
        pose[11] = 0;
        pose[12] = value.getTx();
        pose[13] = value.getTy();
        pose[14] = value.getTz();
        pose[15] = 1;
        return pose;
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
                        float[] Plocal0 = poseMap.get(value.getId());
                        float[] Plocal0inv = new float[16];
                        android.opengl.Matrix.invertM(Plocal0inv, 0, Plocal0, 0);
                        poseMap.remove(value.getId());
                        float[] Plocal1 = mStateCallback.getPose();
                        float[] Pmodel0 = getPoseFromMessage(value);
                        float[] deltaLocalTransform = new float[16];
                        android.opengl.Matrix.multiplyMM(deltaLocalTransform, 0, Plocal1, 0, Plocal0inv, 0);
                        float[] Pmodel1 = new float[16];
                        android.opengl.Matrix.multiplyMM(Pmodel1, 0, deltaLocalTransform, 0, Pmodel0, 0);

                        ArrayList<String> nameList = new ArrayList<>();
                        ArrayList<Double> XList = new ArrayList<>();
                        ArrayList<Double> YList = new ArrayList<>();
                        ArrayList<Double> SizeList = new ArrayList<>();
                        visibility(Pmodel1, nameList, XList, YList, SizeList);
                        mStateCallback.onObjectIdentified(value.getNameList(), value.getXList(), value.getYList(),value.getSizeList() , value.getWidth(), value.getHeight());

//                        showToast(value.getName() + " recognized", Toast.LENGTH_SHORT);

//                        mRecentObjects.addAll(value.getNameList());
//                        if (mRecentObjects.size() > CIRCULAR_BUFFER_LENGTH) {
//                            mRecentObjects.remove(0);
//                        }
//                        mTargetObject = findCommon(mRecentObjects);
//                        if (mTargetObject == null || mTargetObject.equals("None")) {
//                            mTargetObject = null;
//                            mTextView.setText(getString(R.string.none));
//                        } else {
//                            mStateCallback.onObjectIdentified(value.getName(), value.getX(), value.getY(),value.getSize() , value.getWidth(), value.getHeight());
//                            mTextView.setText(mTargetObject);
//                        }
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
                poseMap.put(time, mStateCallback.getPose());
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

    private static String findCommon(List<String> objects) {
        Map<String, Integer> map = new HashMap<>();

        for (String obj : objects) {
            Integer val = map.get(obj);
            map.put(obj, val == null ? 1 : val + 1);
        }

        Map.Entry<String, Integer> max = null;

        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (max == null || e.getValue() >= max.getValue()) {
                max = e;
            }
        }

        return max.getKey();
    }



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
        mChannel = ManagedChannelBuilder.forAddress(mHost, Integer.valueOf(mPort)).usePlaintext(true).build();
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

        // Use onSharedPreferenceChanged for reconnection if user changes BOSSWAVE router
//        mIsBosswaveConnected = false;
//        mBosswaveClient = null;
//        initBosswaveClient();

        mTargetObject = null;
        mRecentObjects = new ArrayList<>(CIRCULAR_BUFFER_LENGTH);

        Activity activity = getActivity();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
//        preferences.registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChanged);


        copyAllPreferenceValue();
        mRequestObserver = createNewRequestObserver();

    }

    @Override
    public void onResume() {
        super.onResume();

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

//    private void initBosswaveClient() {
//        // Use onSharedPreferenceChanged for reconnection if user changes BOSSWAVE router
//        if (!mIsBosswaveConnected && mBosswaveClient == null) {
//            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
//            String bosswaveRouterAddr = preferences.getString(getString(R.string.bosswave_router_addr_key), getString(R.string.bosswave_router_addr_val));
//            int bosswaveRouterPort = Integer.parseInt(preferences.getString(getString(R.string.bosswave_router_port_key), getString(R.string.bosswave_router_port_val)));
//            try {
//                String bosswaveKey = preferences.getString(getString(R.string.bosswave_key_base64_key), getString(R.string.bosswave_key_base64_val));
//                final byte[] mKey = Base64.decode(bosswaveKey, Base64.DEFAULT);
//                File tempKeyFile = File.createTempFile("key", null, null);
//                tempKeyFile.deleteOnExit();
//                FileOutputStream fos = new FileOutputStream(tempKeyFile);
//                fos.write(mKey);
//                fos.close();
//                new BwInitTask(tempKeyFile, mBwInitTaskListener, bosswaveRouterAddr, bosswaveRouterPort).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
//    }
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

//    /**
//     * Enables or disables click events for all buttons.
//     *
//     * @param on  true to make the On button clickable, false otherwise
//     * @param off true to make the Off button clickable, false otherwise
//     */
//    private void setButtonsEnabled(final boolean on, final boolean off) {
//        final Activity activity = getActivity();
//        if (activity != null) {
//            activity.runOnUiThread(() -> {
//                mOnButton.setEnabled(on);
//                mOffButton.setEnabled(off);
//            });
//        }
//    }

    Map<Integer, List<String>> mLabels = new HashMap<>();


    private List<FoundItem> visibility(int dbId, CameraModel camera, Transform post) {
        List<FoundItem> results = new LinkedList<>();
        if (mLabels.get(dbId).isEmpty() {
            return results;
        }

        List<Point3> points3 = new LinkedList<>();
        List<String> names = new LinkedList<>();
        for (int i = 0; i < mLabels.size(); i++) {
            points3.add(mLabels.get(i).getPoint3());
            names.add(mLabels.get(i).getName());
        }

        MatOfPoint2f planePoints;

        Mat K = camera.K();
        // change the base coordiate of the transform from the world coordinate to the
        // image coordinate
        Transform poseInCamera = pose.inverse();
        Mat R = new Mat(3, 3, CV_64FC1);
        R.put(1, 1, new double[]{poseInCamera.r11()});
        R.put(1, 2, new double[]{poseInCamera.r12()});
        R.put(1, 3, new double[]{poseInCamera.r13()});
        R.put(2, 1, new double[]{poseInCamera.r21()});
        R.put(2, 2, new double[]{poseInCamera.r22()});
        R.put(2, 3, new double[]{poseInCamera.r23()});
        R.put(3, 1, new double[]{poseInCamera.r31()});
        R.put(3, 2, new double[]{poseInCamera.r32()});
        R.put(3, 3, new double[]{poseInCamera.r33()});
        Mat rvec(1, 3, CV_64FC1);
        Rodrigues(R, rvec);
        Mat tvec = new Mat(1, 3, CV_64FC1);
        tvec.put(1, 1, new double[]{poseInCamera.x()});
        tvec.put(1, 1, new double[]{poseInCamera.y()});
        tvec.put(1, 1, new double[]{poseInCamera.z()});

        // do the projection
        MatOfPoint3f objectPoints = new MatOfPoint3f();
        objectPoints.fromList(points3);
        projectPoints(objectPoints, rvec, tvec, K, new MatOfDouble(), planePoints);

        // find points in the image
        int width = camera.getImageSize().width;
        int height = camera.getImageSize().height;
        Point center = new Point(width / 2, height / 2);
        List<Point> points2 = planePoints.toList();
        Map<Double, Pair<String, Point>> resultMap;
        for (int i = 0; i < points3.size(); ++i) {
            String name = names.get(i);

            if (isInFrontOfCamera(points3.get(i), poseInCamera)) {
                double dist = norm(points2.get(i), center);
                resultMap.put(dist, new Pair<String, Point>(name, points2.get(i)));
            }
        }

        double size;
        if(width>height) {
            size = height/10;
        } else {
            size = width/10;
        }
        for(Map.Entry<Double, Pair<String, Point>> entry : resultMap) {
            Pair<String, Point> result = entry.getValue();
            results.add(new FoundItem(result.first, result.second.x, result.second.y, size , width, height));
        }
        return results;
    }

    private boolean isInFrontOfCamera(Point3 point3, Transform pose) {
        double z = pose.r31() * point3.x + pose.r32() * point3.y + pose.r33() * point3.z + pose.z();
        return z > 0;
    }

    public interface StateCallback {
        void onObjectIdentified(List<String> name, List<Double> x, List<Double> y, List<Double> size, double width, double height);
        float[] getPose();
    }


}
