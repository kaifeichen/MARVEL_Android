package edu.berkeley.cs.sdb.cellmate;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
import android.support.v4.content.ContextCompat;
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

import com.splunk.mint.Mint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import edu.berkeley.cs.sdb.bosswave.BosswaveClient;
import edu.berkeley.cs.sdb.bosswave.PayloadObject;
import okhttp3.OkHttpClient;


public class CameraFragment extends Fragment implements FragmentCompat.OnRequestPermissionsResultCallback {
    private static final String LOG_TAG = "CellMate";
    private static final String CONTROL_TOPIC_PREFIX = "410.dev/plugctl/front/s.powerup.v0/";
    private static final String CONTROL_TOPIC_SUFFIX = "/i.binact/slot/state";
    private static final String MINT_API_KEY = "76da1102";
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    // A semaphore to prevent the app from exiting before closing the camera.
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private final int CIRCULAR_ARRAY_LENGTH = 10;
    private AutoFitTextureView mTextureView;
    private TextView mTextView;
    private Button mOnButton;
    private Button mOffButton;
    // ID of the current CameraDevice
    private String mCameraId;
    // A CameraCaptureSession for camera preview.
    private CameraCaptureSession mCaptureSession;
    // A reference to the opened CameraDevice.
    private CameraDevice mCameraDevice;
    // The android.util.Size of camera preview.
    private Size mPreviewSize;
    // An additional thread for running tasks that shouldn't block the UI.
    private HandlerThread mBackgroundThread;
    // A Handler for running tasks in the background.
    private Handler mBackgroundHandler;

    private ImageView mHighLight;
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageHandler(reader.acquireLatestImage()));
        }
    };
    // An ImageReader that handles still image capture.
    private ImageReader mImageReader;
    // CaptureRequest.Builder for the camera preview
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private final CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.i(LOG_TAG, "CameraDevice onOpened");
            mCameraOpenCloseLock.release();
            mCameraDevice = camera;
            createCameraPreviewSession();
            setButtonsEnabled(false, false);
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.i(LOG_TAG, "CameraDevice onDisconnected");
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.i(LOG_TAG, "CameraDevice onError");
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (activity != null) {
                activity.finish();
            }
        }
    };
    // Whether the current camera device supports Flash or not.
    private boolean mFlashSupported;
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            Log.i(LOG_TAG, "onSurfaceTextureAvailable, width=" + width + ",height=" + height);
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
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
    private final CameraCaptureSession.StateCallback mSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            // The camera is already closed
            if (mCameraDevice == null) {
                return;
            }

            // When the session is ready, we start displaying the preview.
            mCaptureSession = session;
            try {
                // Auto focus should be continuous for camera preview.
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                // Flash is automatically enabled when necessary.
                setAutoFlash(mPreviewRequestBuilder);

                CaptureRequest previewRequest = mPreviewRequestBuilder.build();
                // do not call anything
                mCaptureSession.setRepeatingRequest(previewRequest, null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            showToast("onConfigureFailed", Toast.LENGTH_LONG);
        }
    };
    // The HTTP Client used for transmitting image
    private OkHttpClient mHttpClient;
    // The Bosswave Client used for sending control command
    private BosswaveClient mBosswaveClient;
    // Whethre the Bosswave Client is connected
    private boolean mIsBosswaveConnected;
    private final BwCloseTask.Listener mBwCloseTaskListener = new BwCloseTask.Listener() {
        @Override
        public void onResponse(boolean success) {
            if (success) {
                showToast("Bosswave disconnected", Toast.LENGTH_SHORT);
                mIsBosswaveConnected = false;
                mBosswaveClient = null;
                // always try to reconnect
                initBosswaveClient();
            } else {
                showToast("Bosswave close failed", Toast.LENGTH_SHORT);
            }
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener mOnSharedPreferenceChanged = new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            // when BOSSWAVE router changes, we need to reconnect
            if (key.equals(getString(R.string.bosswave_router_addr_key)) || key.equals(getString(R.string.bosswave_router_port_key)) || key.equals(getString(R.string.bosswave_key_base64_key))) {
                if (mIsBosswaveConnected) {
                    new BwCloseTask(mBosswaveClient, mBwCloseTaskListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                } else {
                    initBosswaveClient();
                }
            }
        }
    };
    // The current recognized object name
    private String mTargetObject;
    private final GrpcReqImgTask.Listener mGrpcRecognitionListener = new GrpcReqImgTask.Listener() {
        @Override
        public void onResponse(String result, double x, double y, double width) { // null means network error
            Log.d(LOG_TAG, "TAG_TIME response " + System.currentTimeMillis()); // got response from server
            if (result == null) {
                showToast("Network error", Toast.LENGTH_SHORT);
                mTargetObject = null;
                mTextView.setText(getString(R.string.none));
                setButtonsEnabled(false, false);
            } else if (result.trim().equals("None")) {
                showToast("Nothing recognized", Toast.LENGTH_SHORT);
                mTargetObject = null;
                mTextView.setText(getString(R.string.none));
                setButtonsEnabled(false, false);
            } else {
                showToast(result + " recognized", Toast.LENGTH_SHORT);
                mTargetObject = result.trim();
                mTextView.setText(result);
                setButtonsEnabled(true, true);


                double right = 480 - y + width;
                double left = 480 - y - width;
                double bottom = x + width;
                double top = Math.max(0, x - width);
                Rect rect = new Rect((int)left,(int)top,(int)(right),(int)(bottom));

                Paint paint = new Paint();
                paint.setColor(Color.BLUE);
                paint.setStyle(Paint.Style.STROKE);

                Bitmap bmp = Bitmap.createBitmap(480, 640,Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                canvas.drawRect(rect,paint);
                mHighLight.setImageBitmap(bmp);
                System.out.println("width is " + width);
                System.out.println("Bottom is " + bottom);
                System.out.println("right is" + right);


            }
        }
    };
    private final BwInitTask.Listener mBwInitTaskListener = new BwInitTask.Listener() {
        @Override
        public void onResponse(boolean success, BosswaveClient client) {
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
        }
    };
    private final BwPubImgTask.Listener mBwPubImgTaskListener = new BwPubImgTask.Listener() {
        @Override
        public void onResponse(String response) {
            Log.d(LOG_TAG, "TAG_TIME response " + System.currentTimeMillis()); // got response from server
            if (response == null) {
                showToast("Network error", Toast.LENGTH_SHORT);
                mTargetObject = null;
                mTextView.setText(getString(R.string.none));
                setButtonsEnabled(false, false);
            } else if (response.trim().equals("None")) {
                showToast("Nothing recognized", Toast.LENGTH_SHORT);
                mTargetObject = null;
                mTextView.setText(getString(R.string.none));
                setButtonsEnabled(false, false);
            } else {
                showToast(response + " recognized", Toast.LENGTH_SHORT);
                mTargetObject = response.trim();
                mTextView.setText(response);
                setButtonsEnabled(true, true);
            }
        }
    };
    private final View.OnClickListener mOnButtonOnClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (mIsBosswaveConnected) {
                setButtonsEnabled(false, false);
                String topic = CONTROL_TOPIC_PREFIX + mTargetObject + CONTROL_TOPIC_SUFFIX;
                new BwPubCmdTask(mBosswaveClient, topic, new byte[]{1}, new PayloadObject.Type(new byte[]{1, 0, 1, 0}), mBwPubTaskListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } else {
                showToast("Bosswave is not connected", Toast.LENGTH_SHORT);
            }
        }
    };
    private final View.OnClickListener mOffButtonOnClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (mIsBosswaveConnected) {
                setButtonsEnabled(false, false);
                String topic = CONTROL_TOPIC_PREFIX + mTargetObject + CONTROL_TOPIC_SUFFIX;
                new BwPubCmdTask(mBosswaveClient, topic, new byte[]{0}, new PayloadObject.Type(new byte[]{1, 0, 1, 0}), mBwPubTaskListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } else {
                showToast("Bosswave is not connected", Toast.LENGTH_SHORT);
            }
        }
    };
    private int mNextObjectIndex;
    private List<String> mRecentObjects;
    private final HttpPostImgTask.Listener mRecognitionListener = new HttpPostImgTask.Listener() {
        @Override
        public void onResponse(String result) { // null means network error
            Log.d(LOG_TAG, "TAG_TIME response " + System.currentTimeMillis()); // got response from server

            if (result == null) {
                showToast("Network error", Toast.LENGTH_SHORT);
            }

            mRecentObjects.add(mNextObjectIndex % CIRCULAR_ARRAY_LENGTH, result.trim());
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

    public static CameraFragment newInstance() {
        return new CameraFragment();
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices       The list of sizes that the camera supports for the intended output class
     * @param surfaceWidth  The width of the texture view relative to sensor coordinate
     * @param surfaceHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth      The maximum width that can be chosen
     * @param maxHeight     The maximum height that can be chosen
     * @param aspectRatio   The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int surfaceWidth, int surfaceHeight, int maxWidth, int maxHeight, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight && option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= surfaceWidth && option.getHeight() >= surfaceHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(LOG_TAG, "Couldn't find any suitable preview size");
            return choices[0];
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

        mHighLight = (ImageView) view.findViewById(R.id.imageView);
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

        mHttpClient = new OkHttpClient();

        // Use onSharedPreferenceChanged for reconnection if user changes BOSSWAVE router
        mIsBosswaveConnected = false;
        mBosswaveClient = null;
        initBosswaveClient();

        mTargetObject = null;
        mNextObjectIndex = 0;
        mRecentObjects = new ArrayList<>(CIRCULAR_ARRAY_LENGTH);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        preferences.registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChanged);

        Mint.initAndStartSession(getActivity(), MINT_API_KEY);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        if (mTargetObject != null) {
            mTextView.setText(mTargetObject);
        } else {
            mTextView.setText(getString(R.string.none));
        }

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
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

    private void requestCameraPermission() {
        FragmentCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                showToast(getString(R.string.request_permission), Toast.LENGTH_LONG);
                requestCameraPermission();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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

    private void updatePreferenceCameraInfo() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        String cameraWidth = preferences.getString(getString(R.string.camera_width_key), getString(R.string.camera_width_val));
        String cameraHeight = preferences.getString(getString(R.string.camera_height_key), getString(R.string.camera_height_val));
        if (cameraWidth.equals(getString(R.string.camera_width_val)) || cameraHeight.equals(getString(R.string.camera_height_val))) {
            Activity activity = getActivity();
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            try {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);
                Rect sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString(getString(R.string.camera_width_key), Integer.toString(sensorRect.width()));
                editor.putString(getString(R.string.camera_height_key), Integer.toString(sensorRect.height()));
                editor.apply();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Sets up member variables related to camera.
     */
    private void setUpCameraOutputs(int surfaceWidth, int surfaceHeight) {
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // We use 640x480 if available
                // the preview image will be cropped around center by Android to fit targetImageSize
                // TODO: Android doesn't seem to crop the image for me, I have to build an ImageReader that resizes or crops images
                Size targetSize = new Size(640, 480);
                List<Size> imageSizes = Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
                if (!imageSizes.contains(targetSize)) {
                    throw new RuntimeException("640x480 size is not supported");
                }

                int maxImages = 5;
                mImageReader = ImageReader.newInstance(targetSize.getWidth(), targetSize.getHeight(), ImageFormat.JPEG, maxImages);
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

                // Find out if we need to swap dimension to get the preview size relative to sensor coordinate.
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                //noinspection ConstantConditions
                int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
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
                        Log.e(LOG_TAG, "Display rotation is invalid: " + displayRotation);
                }

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = surfaceWidth;
                int rotatedPreviewHeight = surfaceHeight;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = surfaceHeight;
                    rotatedPreviewHeight = surfaceWidth;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                // the preview size has to have the same aspect ratio as the camera sensor, otherwise the image will be skewed
                Rect sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                Size sensorAspectRatioSize = new Size(sensorRect.width(), sensorRect.height());

                // Danger, W.R.! Attempting to use too large a preview size could exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                // Another purpose is to makesure the preview aspect ratio is the same as the sensor aspect ratio
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth, maxPreviewHeight, sensorAspectRatioSize);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available == null ? false : available;

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Opens the camera specified by mCameraId.
     */
    private void openCamera(int width, int height) {
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }

        updatePreferenceCameraInfo(); // update preference values after we get camera set up
    }

    /**
     * Closes the current CameraDevice.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its Handler.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its Handler.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            List<Surface> surfaces = Arrays.asList(surface, mImageReader.getSurface());
            mPreviewRequestBuilder.addTarget(surface);
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(surfaces, mSessionStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
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
        if (mTextureView == null || mPreviewSize == null || activity == null) {
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

    /**
     * Compares two {@code Size}s based on their areas.
     */
    private static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    private class ImageHandler implements Runnable {
        private final Image mImage;
        private final double mFx;
        private final double mFy;
        private final double mCx;
        private final double mCy;

        private ImageHandler(Image image) {
            mImage = image;

            Activity activity = getActivity();
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
            mFx = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_fx_key), activity.getString(R.string.camera_fx_val)));
            mFy = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_fy_key), activity.getString(R.string.camera_fy_val)));
            mCx = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_cx_key), activity.getString(R.string.camera_cx_val)));
            mCy = Double.parseDouble(preferences.getString(activity.getString(R.string.camera_cy_key), activity.getString(R.string.camera_cy_val)));
        }

        @Override
        public void run() {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
            String connType = preferences.getString(getString(R.string.conn_type_key), getString(R.string.conn_type_val));
            String[] connTypes = getResources().getStringArray(R.array.conn_types);
            if (connType.equals(connTypes[0])) { // HTTP
                // AsyncTask task instance must be created and executed on the UI thread
//                mBackgroundHandler.post(new HttpPostImageRunnable(mImage, mFx, mFy, mCx, mCy));
                mBackgroundHandler.post(new GrpcPostImageRunnable(mImage, mFx, mFy, mCx, mCy));
            } else if (connType.equals(connTypes[1])) { // BOSSWAVE
                mBackgroundHandler.post(new BWPublishImageRunnable(mImage, mFx, mFy, mCx, mCy));
            } else if (connType.equals(connTypes[2])) { // GRPC
                mBackgroundHandler.post(new GrpcPostImageRunnable(mImage, mFx, mFy, mCx, mCy));
            } else {
                mImage.close();
                throw new RuntimeException("Connection Type is Undefined");
            }
        }
    }

    private class HttpPostImageRunnable implements Runnable {
        private final Image mImage;
        private final double mFx;
        private final double mFy;
        private final double mCx;
        private final double mCy;

        private HttpPostImageRunnable(Image image, double fx, double fy, double cx, double cy) {
            mImage = image;
            mFx = fx;
            mFy = fy;
            mCx = cx;
            mCy = cy;
        }

        @Override
        public void run() {
            try {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
                String cellmateServerAddr = preferences.getString(getString(R.string.cellmate_server_addr_key), getString(R.string.cellmate_server_addr_val));
                String cellmateServerPort = preferences.getString(getString(R.string.cellmate_server_port_key), getString(R.string.cellmate_server_port_val));
                String imagePostUrl = "http://" + cellmateServerAddr + ":" + cellmateServerPort + "/";
                new HttpPostImgTask(getActivity(), mHttpClient, imagePostUrl, mImage, mFx, mFy, mCx, mCy, mRecognitionListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class BWPublishImageRunnable implements Runnable {
        private final Image mImage;
        private final double mFx;
        private final double mFy;
        private final double mCx;
        private final double mCy;

        private BWPublishImageRunnable(Image image, double fx, double fy, double cx, double cy) {
            mImage = image;
            mFx = fx;
            mFy = fy;
            mCx = cx;
            mCy = cy;
        }

        @Override
        public void run() {
            try {
                String topic = "scratch.ns/cellmate";
                System.out.println(topic);
                new BwPubImgTask(mBosswaveClient, topic, mImage, mFx, mFy, mCx, mCy, mBwPubImgTaskListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class GrpcPostImageRunnable implements Runnable {
        private final Image mImage;
        private final double mFx;
        private final double mFy;
        private final double mCx;
        private final double mCy;

        private GrpcPostImageRunnable(Image image, double fx, double fy, double cx, double cy) {
            mImage = image;
            mFx = fx;
            mFy = fy;
            mCx = cx;
            mCy = cy;
        }

        @Override
        public void run() {
            try {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
                String grpcCellmateServerAddr = preferences.getString(getString(R.string.Grpc_server_addr_key), getString(R.string.cellmate_server_addr_val));
                String grpcCellmateServerPort = preferences.getString(getString(R.string.Grpc_server_port_key), getString(R.string.cellmate_server_port_val));
                new GrpcReqImgTask(getActivity(), grpcCellmateServerAddr, Integer.valueOf(grpcCellmateServerPort), mImage, mFx, mFy, mCx, mCy, mGrpcRecognitionListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
