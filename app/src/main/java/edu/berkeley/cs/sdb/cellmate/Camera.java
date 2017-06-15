package edu.berkeley.cs.sdb.cellmate;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


public class Camera {
    private static Camera mInstance;
    private static Context mContext;


    private static final String LOG_TAG = "CellMate";

    private final Size mSize;
    private List<OnCameraOpenedListener> mCameraOpenedListeners;
    private List<Surface> mSurfaces;
    // A semaphore to prevent the app from exiting before closing the camera.
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
    // ID of the current CameraDevice
    private String mCameraId;
    // A CameraCaptureSession for camera preview.
    private CameraCaptureSession mCaptureSession;
    // A reference to the opened CameraDevice.
    private CameraDevice mCameraDevice;
    // An additional thread for running tasks that shouldn't block the UI.
    private HandlerThread mBackgroundThread;
    // A Handler for running tasks in the background.
    private Handler mBackgroundHandler;
    // CaptureRequest.Builder for the camera preview
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private final CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.i(LOG_TAG, "CameraDevice onOpened");
            mCameraOpenCloseLock.release();
            mCameraDevice = camera;
            updatePreviewSession();
            mCameraOpenedListeners.forEach((listener) -> listener.onOpened());
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.i(LOG_TAG, "CameraDevice onDisconnected");
            mCameraOpenCloseLock.release();
            mCameraDevice = null;
            camera.close();
            throw new RuntimeException("Camera Device onDisconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.i(LOG_TAG, "CameraDevice onError");
            mCameraOpenCloseLock.release();
            mCameraDevice = null;
            camera.close();
            throw new RuntimeException("Camera Device onError");
        }
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

                CaptureRequest previewRequest = mPreviewRequestBuilder.build();
                // Calling this method will replace any earlier repeating request
                mCaptureSession.setRepeatingRequest(previewRequest, null, null);
            } catch (CameraAccessException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            throw new RuntimeException("on camera configure failed");
        }
    };

    private Camera(Context context, Size size) {
        mContext = context;
        mSize = size;
        mCameraOpenedListeners = new LinkedList<>();
        mSurfaces = new LinkedList<>();
    }

    public static synchronized Camera getInstance(Context context, Size size) {
        if (mInstance == null) {
            mInstance = new Camera(context, size);
        }
        return mInstance;
    }

    public boolean registerStateListener(OnCameraOpenedListener listener) {
        if (mCameraOpenedListeners.contains(listener)) {
            return false;
        }
        return mCameraOpenedListeners.add(listener);
    }

    public boolean unregisterStateListener(OnCameraOpenedListener listener) {
        return mCameraOpenedListeners.remove(listener);
    }

    /**
     * Register a surface where the camera will stream to
     *
     * @param surface
     * @return whether the operation succeeded
     */
    public boolean registerPreviewSurface(Surface surface) {
        if (mSurfaces.contains(surface)) {
            return false;
        }
        boolean success = mSurfaces.add(surface);
        updatePreviewSession();
        return success;
    }

    /**
     * Unregister a surface where the camera will stream to
     *
     * @param surface
     * @return whether the operation succeeded
     */
    public boolean unregisterPreviewSurface(Surface surface) {
        boolean success = mSurfaces.remove(surface);
        updatePreviewSession();
        return success;
    }

    public void open() {
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw new RuntimeException("Camera Permission onError");
        }
        mCameraId = selectCamera();
        CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current CameraDevice.
     */
    public void close() {
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
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Sets up member variables related to camera.
     */
    private String selectCamera() {
        CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                List<Size> imageSizes = Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
                if (!imageSizes.contains(mSize)) {
                    continue;
                }
                return cameraId;
            }
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }

        throw new RuntimeException("Cannot find a camera to use.");
    }


    /**
     * Starts a background thread and its Handler.
     */
    public void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its Handler.
     */
    public void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void updatePreviewSession() {
        if (mSurfaces.isEmpty()) {
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
        } else {
            try {
                // We set up a CaptureRequest.Builder with the output Surface.
                mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mSurfaces.forEach((surface) -> mPreviewRequestBuilder.addTarget(surface));

                // Here, we create a CameraCaptureSession for camera preview. It closes the previous session and its requests
                mCameraDevice.createCaptureSession(mSurfaces, mSessionStateCallback, null);
            } catch (CameraAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public interface OnCameraOpenedListener {
        void onOpened();
    }
}
