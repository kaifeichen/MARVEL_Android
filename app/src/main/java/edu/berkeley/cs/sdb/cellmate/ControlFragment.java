package edu.berkeley.cs.sdb.cellmate;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.preference.PreferenceManager;
import android.support.v13.app.FragmentCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.Base64;
import android.util.Log;
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
import java.util.List;
import java.util.Map;

import edu.berkeley.cs.sdb.bosswave.BosswaveClient;
import edu.berkeley.cs.sdb.bosswave.PayloadObject;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;


public class ControlFragment extends Fragment {
    private static final String LOG_TAG = "CellMate";
    private static final String CONTROL_TOPIC_PREFIX = "410.dev/plugctl/front/s.powerup.v0/";
    private static final String CONTROL_TOPIC_SUFFIX = "/i.binact/slot/state";
    private static final int REQUEST_INTERVAL = 500;
    private final int CIRCULAR_BUFFER_LENGTH = 10;
    Bitmap mBmp;
    String mHost;
    String mPort;
    double mFx;
    double mFy;
    double mCx;
    double mCy;
    private StateCallback mStateCallback;
    ManagedChannel mChannel;
    StreamObserver<CellmateProto.ClientQueryMessage> mRequestObserver;
    private TextView mTextView;
    private Button mOnButton;
    private Button mOffButton;
    private ImageView mHighLight;
    private Long mLastTime;
    // We keep a toast reference so it can be updated instantly
    private Toast mToast;
    private final BwPubCmdTask.Listener mBwPubTaskListener = (String response) -> {
        showToast("Control command sent: " + response, Toast.LENGTH_SHORT);
        setButtonsEnabled(true, true);
    };
    // The Bosswave Client used for sending control command
    private BosswaveClient mBosswaveClient;
    // Whethre the Bosswave Client is connected
    private boolean mIsBosswaveConnected;
    private final BwCloseTask.Listener mBwCloseTaskListener = (boolean success) -> {
        if (success) {
            showToast("Bosswave disconnected", Toast.LENGTH_SHORT);
            mIsBosswaveConnected = false;
            mBosswaveClient = null;
            // always try to reconnect
            initBosswaveClient();
        } else {
            showToast("Bosswave close failed", Toast.LENGTH_SHORT);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener mOnSharedPreferenceChanged = (SharedPreferences sharedPreferences, String key) -> {
        // when BOSSWAVE router changes, we need to reconnect
        if (key.equals(getString(R.string.bosswave_router_addr_key)) || key.equals(getString(R.string.bosswave_router_port_key)) || key.equals(getString(R.string.bosswave_key_base64_key))) {
            if (mIsBosswaveConnected) {
                new BwCloseTask(mBosswaveClient, mBwCloseTaskListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } else {
                initBosswaveClient();
            }
        }
    };
    // The current recognized object name
    private String mTargetObject;
    private final BwInitTask.Listener mBwInitTaskListener = (boolean success, BosswaveClient client) -> {
        if (success) {
            showToast("Bosswave connected", Toast.LENGTH_SHORT);
            mBosswaveClient = client;
            mIsBosswaveConnected = true;
            if (mTargetObject != null) {
                setButtonsEnabled(true, true);
            }
        } else {
            mBosswaveClient = null;
            showToast("Bosswave connection failed", Toast.LENGTH_SHORT);
        }
    };
    private final View.OnClickListener mOnButtonOnClickListener = (View v) -> {
        if (mIsBosswaveConnected) {
            setButtonsEnabled(false, false);
            String topic = CONTROL_TOPIC_PREFIX + mTargetObject + CONTROL_TOPIC_SUFFIX;
            new BwPubCmdTask(mBosswaveClient, topic, new byte[]{1}, new PayloadObject.Type(new byte[]{1, 0, 1, 0}), mBwPubTaskListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            showToast("Bosswave is not connected", Toast.LENGTH_SHORT);
        }
    };
    private final View.OnClickListener mOffButtonOnClickListener = (View v) -> {
        if (mIsBosswaveConnected) {
            setButtonsEnabled(false, false);
            String topic = CONTROL_TOPIC_PREFIX + mTargetObject + CONTROL_TOPIC_SUFFIX;
            new BwPubCmdTask(mBosswaveClient, topic, new byte[]{0}, new PayloadObject.Type(new byte[]{1, 0, 1, 0}), mBwPubTaskListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            showToast("Bosswave is not connected", Toast.LENGTH_SHORT);
        }
    };
    private List<String> mRecentObjects;
    StreamObserver<CellmateProto.ServerRespondMessage> mResponseObserver = new StreamObserver<CellmateProto.ServerRespondMessage>() {
        @Override
        public void onNext(CellmateProto.ServerRespondMessage value) {
//            mStateCallback.onObjectIdentified(value.getName(),value.getX(),value.getY(),value.getWidth());
            final Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    Log.d(LOG_TAG, "TAG_TIME response " + System.currentTimeMillis()); // got response from server

                    showToast(value.getName() + " recognized", Toast.LENGTH_SHORT);

                    mRecentObjects.add(value.getName());
                    if (mRecentObjects.size() > CIRCULAR_BUFFER_LENGTH) {
                        mRecentObjects.remove(0);
                    }
                    mTargetObject = findCommon(mRecentObjects);

                    if (mTargetObject == null || mTargetObject.equals("None")) {
                        mTargetObject = null;
                        mTextView.setText(getString(R.string.none));
                        setButtonsEnabled(false, false);
                    } else {
                        mTextView.setText(mTargetObject);
                        setButtonsEnabled(true, true);
                        double x = value.getX();
                        double y = value.getY();
                        double width = value.getWidth();
                        if (x != -1) {
                            double right = 480 - y + width;
                            double left = 480 - y - width;
                            double bottom = x + width;
                            double top = Math.max(0, x - width);
                            Rect rect = new Rect((int) left, (int) top, (int) (right), (int) (bottom));

                            Paint paint = new Paint();
                            paint.setColor(Color.BLUE);
                            paint.setStyle(Paint.Style.STROKE);
                            if (mBmp != null && !mBmp.isRecycled()) {
                                mBmp.recycle();
                            }
                            Bitmap mBmp = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888);
                            Canvas canvas = new Canvas(mBmp);
                            canvas.drawRect(rect, paint);
                            mHighLight.setImageBitmap(mBmp);
                        } else {
                            if (mBmp != null && !mBmp.isRecycled()) {
                                mBmp.recycle();
                            }
                            Bitmap mBmp = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888);
                            mHighLight.setImageBitmap(mBmp);
                        }
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
        Long time = System.currentTimeMillis();
        Image image = reader.acquireLatestImage();
        if (time - mLastTime > REQUEST_INTERVAL) {
            ByteString data = ByteString.copyFrom(image.getPlanes()[0].getBuffer());
            sendRequestToServer(data);
            mLastTime = time;
        }
        image.close();

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

    public static ControlFragment newInstance() {
        return new ControlFragment();
    }

    private void sendRequestToServer(ByteString data) {
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
                    .build();
            mRequestObserver.onNext(request);
            mRequestObserver.onNext(request);
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

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.control_fragment, container, false);
    }

    ImageReader mImageReader;
    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextView = (TextView) view.findViewById(R.id.text);
        mOnButton = (Button) view.findViewById(R.id.on);
        mOnButton.setOnClickListener(mOnButtonOnClickListener);
        mOffButton = (Button) view.findViewById(R.id.off);
        mOffButton.setOnClickListener(mOffButtonOnClickListener);

        mHighLight = (ImageView) view.findViewById(R.id.imageView);
        setButtonsEnabled(false, false);
        setHasOptionsMenu(true);

        mLastTime = System.currentTimeMillis();
        mToast = Toast.makeText(getActivity(), "", Toast.LENGTH_SHORT);
        Camera camera = Camera.getInstance();
        Size cameraSize = camera.getCameraSize();
        mImageReader = ImageReader.newInstance(cameraSize.getWidth(), cameraSize.getHeight(),
                ImageFormat.JPEG, /*maxImages*/2);
        mImageReader.setOnImageAvailableListener(
                mOnImageAvailableListener, null);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // Use onSharedPreferenceChanged for reconnection if user changes BOSSWAVE router
        mIsBosswaveConnected = false;
        mBosswaveClient = null;
        initBosswaveClient();

        mTargetObject = null;
        mRecentObjects = new ArrayList<>(CIRCULAR_BUFFER_LENGTH);

        Activity activity = getActivity();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        preferences.registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChanged);


        copyAllPreferenceValue();
        mRequestObserver = createNewRequestObserver();

    }

    @Override
    public void onResume() {
        super.onResume();

        copyAllPreferenceValue();
        mRequestObserver = createNewRequestObserver();

//        Camera camera = Camera.getInstance();
//        camera.registerPreviewSurface(mImageReader.getSurface());
    }

    @Override
    public void onPause() {
        mTargetObject = null;
        mTextView.setText(getString(R.string.none));
        mRequestObserver.onCompleted();
        mChannel.shutdown();

//        Camera camera = Camera.getInstance();
//        camera.unregisterPreviewSurface(mImageReader.getSurface());
        super.onPause();
    }

    private void initBosswaveClient() {
        // Use onSharedPreferenceChanged for reconnection if user changes BOSSWAVE router
        if (!mIsBosswaveConnected && mBosswaveClient == null) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
            String bosswaveRouterAddr = preferences.getString(getString(R.string.bosswave_router_addr_key), getString(R.string.bosswave_router_addr_val));
            int bosswaveRouterPort = Integer.parseInt(preferences.getString(getString(R.string.bosswave_router_port_key), getString(R.string.bosswave_router_port_val)));
            try {
                String bosswaveKey = preferences.getString(getString(R.string.bosswave_key_base64_key), getString(R.string.bosswave_key_base64_val));
                final byte[] mKey = Base64.decode(bosswaveKey, Base64.DEFAULT);
                File tempKeyFile = File.createTempFile("key", null, null);
                tempKeyFile.deleteOnExit();
                FileOutputStream fos = new FileOutputStream(tempKeyFile);
                fos.write(mKey);
                fos.close();
                new BwInitTask(tempKeyFile, mBwInitTaskListener, bosswaveRouterAddr, bosswaveRouterPort).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
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

    /**
     * Enables or disables click events for all buttons.
     *
     * @param on  true to make the On button clickable, false otherwise
     * @param off true to make the Off button clickable, false otherwise
     */
    private void setButtonsEnabled(final boolean on, final boolean off) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                mOnButton.setEnabled(on);
                mOffButton.setEnabled(off);
            });
        }
    }



    public interface StateCallback {
        void onObjectIdentified(String name, double x, double y, double size);
    }
}
