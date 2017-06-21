package edu.berkeley.cs.sdb.cellmate;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraManager;
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


public class PreviewFragment extends Fragment implements FragmentCompat.OnRequestPermissionsResultCallback {
    private static final String LOG_TAG = "CellMate";
    private static final String CONTROL_TOPIC_PREFIX = "410.dev/plugctl/front/s.powerup.v0/";
    private static final String CONTROL_TOPIC_SUFFIX = "/i.binact/slot/state";

    private StateCallback mStateCallback;
    private final int CIRCULAR_ARRAY_LENGTH = 10;
    private AutoFitTextureView mTextureView;
    private Surface mPreviewSurface;
    private TextView mTextView;
    private Button mOnButton;
    private Button mOffButton;
    // The android.util.Size of camera preview.
    private Size mPreviewSize;
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = (ImageReader reader) -> {
        Image image = reader.acquireLatestImage();
        assert image.getFormat() == ImageFormat.JPEG;
        ByteString data = ByteString.copyFrom(image.getPlanes()[0].getBuffer());
        image.close();
        postImage(data);
    };
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            Log.i(LOG_TAG, "onSurfaceTextureAvailable, width=" + width + ",height=" + height);

            mPreviewSurface = new Surface(surface);

            configureTransform(width, height);
            mStateCallback.onSurfaceAvailable(mPreviewSurface);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            mPreviewSurface = null;
            surface.release();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };
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
    private int mNextObjectIndex;
    private List<String> mRecentObjects;
    private final GrpcReqImgTask.Listener mGrpcRecognitionListener = (String result) -> { // null means network error
        Log.d(LOG_TAG, "TAG_TIME response " + System.currentTimeMillis()); // got response from server

        if (result == null) {
            showToast("Network error", Toast.LENGTH_SHORT);
            return;
        }

        mRecentObjects.add(mNextObjectIndex % CIRCULAR_ARRAY_LENGTH, result);
        mTargetObject = findCommon(mRecentObjects);


        if (mTargetObject == null || mTargetObject.equals("None")) {
            mTargetObject = null;
            mTextView.setText(getString(R.string.none));
            setButtonsEnabled(false, false);
        } else {
            showToast(result + " recognized", Toast.LENGTH_SHORT);
            mTextView.setText(mTargetObject);
            setButtonsEnabled(true, true);
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

    public static PreviewFragment newInstance(Size size) {
        PreviewFragment previewFragment = new PreviewFragment();

        Bundle args = new Bundle();
        args.putSize("size", size);
        previewFragment.setArguments(args);

        return previewFragment;
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
        return inflater.inflate(R.layout.camera_fragment, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        mTextView = (TextView) view.findViewById(R.id.text);
        mOnButton = (Button) view.findViewById(R.id.on);
        mOnButton.setOnClickListener(mOnButtonOnClickListener);
        mOffButton = (Button) view.findViewById(R.id.off);
        mOffButton.setOnClickListener(mOffButtonOnClickListener);

        setButtonsEnabled(false, false);
        setHasOptionsMenu(true);

        mToast = Toast.makeText(getActivity(), "", Toast.LENGTH_SHORT);

        // hide action bar
        ActionBar actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
        actionBar.setDisplayShowHomeEnabled(false);
        actionBar.setDisplayShowTitleEnabled(false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // Use onSharedPreferenceChanged for reconnection if user changes BOSSWAVE router
        mIsBosswaveConnected = false;
        mBosswaveClient = null;
        initBosswaveClient();

        mTargetObject = null;
        mNextObjectIndex = 0;
        mRecentObjects = new ArrayList<>(CIRCULAR_ARRAY_LENGTH);

        Activity activity = getActivity();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        preferences.registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChanged);
    }

    @Override
    public void onResume() {
        super.onResume();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we open
        // camera in the camera Fragment and start preview from there. Otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener.
    }

    @Override
    public void onPause() {
        mTargetObject = null;
        mTextView.setText(getString(R.string.none));
        super.onPause();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.settings:
                Intent intent = new Intent(getActivity(), SettingsActivity.class);
                startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Update UI based on a sensor orientation
     *
     * @param sensorOrientation
     */
    public void updateSensorOrientation(int sensorOrientation) {
        Activity activity = getActivity();
        // Find out if we need to swap dimension to get the preview size relative to sensor coordinate.
        int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        boolean swappedDimensions = false;
        switch (displayRotation) {
            case Surface.ROTATION_0:
            case Surface.ROTATION_180:
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    swappedDimensions = true;
                }
                break;
            case Surface.ROTATION_90:
            case Surface.ROTATION_270:
                if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true;
                }
                break;
            default:
                throw new RuntimeException("Display rotation is invalid: " + displayRotation);
        }

        mPreviewSize = getArguments().getSize("size");

        if (swappedDimensions) {
            mPreviewSize = new Size(mPreviewSize.getHeight(), mPreviewSize.getWidth());
        }

        // We fit the aspect ratio of TextureView to the size of preview we picked.
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        } else {
            mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
        }
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
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
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

    private void postImage(ByteString data) {
        Activity activity = getActivity();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        double fx = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_fx_key), activity.getString(R.string.camera_fx_val)));
        double fy = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_fy_key), activity.getString(R.string.camera_fy_val)));
        double cx = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_cx_key), activity.getString(R.string.camera_cx_val)));
        double cy = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_cy_key), activity.getString(R.string.camera_cy_val)));
        String grpcCellmateServerAddr = preferences.getString(getString(R.string.grpc_server_addr_key), getString(R.string.grpc_server_addr_val));
        String grpcCellmateServerPort = preferences.getString(getString(R.string.grpc_server_port_key), getString(R.string.grpc_server_port_val));

        new GrpcReqImgTask(grpcCellmateServerAddr, Integer.valueOf(grpcCellmateServerPort), data, fx, fy, cx, cy, mGrpcRecognitionListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public interface StateCallback {
        void onSurfaceAvailable(Surface surface);
    }
}
