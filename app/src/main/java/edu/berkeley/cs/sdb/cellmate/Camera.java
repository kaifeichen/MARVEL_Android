package edu.berkeley.cs.sdb.cellmate;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v13.app.FragmentCompat;
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

    public enum States {
        IDLE,
        OPENED,
        OPENING
    }

    States mCameraState;
    private final Semaphore mCameraStateLock = new Semaphore(1);

    private static Camera mInstance;
    private static Context mContext;
    private final Size mSize;
    private int mSensorOrientation;




    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final String LOG_TAG = "CellMate";
    // A semaphore to prevent the app from exiting before closing the camera.
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private List<Surface> mSurfaces;
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
    private final CameraCaptureSession.StateCallback mSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            // The camera is already closed
            if (mCameraDevice == null || mSurfaces.isEmpty()) {
                return;
            }
            try {
                mCameraStateLock.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (mCameraState!=States.OPENED) throw new AssertionError("Camera State wrong");
            mCameraStateLock.release();
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
            } catch (IllegalStateException e) {
                //session close because another session was created
                //the new session will trigger its onConfigured later
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            throw new RuntimeException("on camera configure failed");
        }


    };
    private final CameraDevice.StateCallback mCameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.i(LOG_TAG, "CameraDevice onOpened");
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            try {
                mCameraStateLock.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (mCameraState!=States.OPENING) throw new AssertionError("Camera State wrong");
            mCameraState = States.OPENED;
            mCameraStateLock.release();
            if(!mSurfaces.isEmpty()) {
                Log.i("Cellmate","Update from onOpen");
                updatePreviewSession();
            }

        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.i(LOG_TAG, "CameraDevice onDisconnected");
            mCameraOpenCloseLock.release();
            mCameraDevice = null;
            cameraDevice.close();
            throw new RuntimeException("Camera Device onDisconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            Log.i(LOG_TAG, "CameraDevice onError");
            mCameraOpenCloseLock.release();
            mCameraDevice = null;
            cameraDevice.close();
            throw new RuntimeException("Camera Device onError");
        }

    };

    private Camera(Context context, Size size) {
        try {
            mCameraStateLock.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mCameraState = States.IDLE;
        mCameraStateLock.release();
        mContext = context;
        mSize = size;
        mSurfaces = new LinkedList<>();
    }

    public static synchronized Camera getInstance(Context context, Size size) {
        if (mInstance == null) {
            mInstance = new Camera(context, size);
        }
        return mInstance;
    }

    public static synchronized Camera getInstance() {
        assert(mInstance != null);
        return mInstance;
    }


    public boolean isOpen(){
        return mCameraState == States.OPENED;
    }




    public int getmSensorOrientation(){
        return mSensorOrientation;
    }






    /**
     * Register a surface where the camera will stream to
     *
     * @param surface
     */
    public void registerPreviewSurface(Surface surface) {
        mBackgroundHandler.post(()-> {
            if (!surface.isValid() || mSurfaces.contains(surface)) {
                return;
            }
            boolean success = mSurfaces.add(surface);
            updatePreviewSession();
        });
    }

    /**
     * Unregister a surface where the camera will stream to
     *
     * @param surface
     */
    public void unregisterPreviewSurface(Surface surface) {
        mBackgroundHandler.post(()-> {
            mSurfaces.remove(surface);
            updatePreviewSession();
        });
    }

    public void openCamera() {
        startBackgroundThread();

        Log.i("Camera Device", "openCamera");
        try {
            mCameraStateLock.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (mCameraState!=States.IDLE) throw new AssertionError("Camera State wrong");
        mCameraState = States.OPENING;
        mCameraStateLock.release();

        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw new RuntimeException("Camera Permission onError");
        }

        mCameraId = selectCamera();
        CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mCameraStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current CameraDevice.
     */
    public void closeCamera() {
        Log.i("Camera Device", "closeCamera");
        if(mBackgroundThread != null) {
            stopBackgroundThread();
        }

        try {
            mCameraStateLock.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (mCameraState!=States.OPENED) throw new AssertionError("Camera State wrong");
        mCameraState = States.IDLE;
        mCameraStateLock.release();

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
        if (mCameraState!=States.OPENING) throw new AssertionError("Camera State wrong");
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

                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

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
        Log.i("Cellmate","Get into update");
        //Avoid race condition because surfaces canbe changed from different threads

        if(mCameraState == States.OPENED) {
            List<Surface> newSurfaces = new LinkedList<>();
            for(Surface s : mSurfaces) {
                if(s.isValid())
                    newSurfaces.add(s);
            }
            mSurfaces = newSurfaces;

            if(mSurfaces.isEmpty() && mCaptureSession != null) {
                mCaptureSession.close();
            } else {
                try {
                    // We set up a CaptureRequest.Builder with the output Surface.
                    mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    for(Surface s : mSurfaces) {
                        if (s.isValid()) {
                            mPreviewRequestBuilder.addTarget(s);
                        }
                    };

                    // Here, we create a CameraCaptureSession for camera preview. It closes the previous session and its requests
                    mCameraDevice.createCaptureSession(mSurfaces, mSessionStateCallback, null);
                } catch (CameraAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }



    }

    public Size getCameraSize(){
        return mSize;
    }


//    public interface StateCallback {
//        void onSensorOrientationChanged(int sensorOrientation);
//    }


}
