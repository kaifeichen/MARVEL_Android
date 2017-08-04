package edu.berkeley.cs.sdb.cellmate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


public class Camera {

    private OrientationEventListener mOrientationEventListener;
    private int mDeviceOrientation;


    public enum States {
        IDLE,
        OPENED,
        OPENING
    }

    States mCameraState;
    private final Semaphore mCameraStateLock = new Semaphore(1);

    private static Camera mInstance;
    private static Context mContext;


    Surface mCaptureSurface;


    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;




    private static final String LOG_TAG = "CellMate";
    // A semaphore to prevent the app from exiting before closing the camera.
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private List<Surface> mSurfaces;
    private List<Surface> mCaptureSurfaces;
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
    private CaptureRequest mPreviewRequest;
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

                mPreviewRequest = mPreviewRequestBuilder.build();
                // Calling this method will replace any earlier repeating request
                mCaptureSession.setRepeatingRequest(mPreviewRequest, null, null);
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
            } else {
                Log.i("Cellmate","Empty surfaces in Update from onOpen");
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

    private Camera(Context context) {
        try {
            mCameraStateLock.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        mCameraState = States.IDLE;
        mCameraStateLock.release();
        mContext = context;
        mSurfaces = new LinkedList<>();
        mCaptureSurfaces = new LinkedList<>();
        mOrientationEventListener = new OrientationEventListener(mContext)
        {
            @Override
            public void onOrientationChanged(int orientation)
            {
                mDeviceOrientation =((orientation + 45) / 90 * 90)%360;
            }
        };
        mOrientationEventListener.enable();
    }

    public static synchronized Camera getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new Camera(context);
            setPreviewAndCaptureSize();
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


    /**
     * Register a surface where the camera will capture to
     *
     * @param surface
     */
    public void registerCaptureSurface(Surface surface) {
        if(mBackgroundThread == null) {
            startBackgroundThread();
        }
        mBackgroundHandler.post(()-> {
            if (!surface.isValid() || mCaptureSurfaces.contains(surface)) {
                return;
            }
            mCaptureSurfaces.add(surface);
            updatePreviewSession();
        });
    }

    /**
     * Unregister a surface where the camera will capture to
     *
     * @param surface
     */
    public void unregisterCaptureSurface(Surface surface) {
        if(mBackgroundThread == null) {
            startBackgroundThread();
        }
        mBackgroundHandler.post(()-> {
            mCaptureSurfaces.remove(surface);
            updatePreviewSession();
        });
    }


    /**
     * Register a surface where the camera will stream to
     *
     * @param surface
     */
    public void registerPreviewSurface(Surface surface) {
        if(mBackgroundThread == null) {
            startBackgroundThread();
        }
        mBackgroundHandler.post(()-> {
            if (!surface.isValid() || mSurfaces.contains(surface)) {
                return;
            }
            mSurfaces.add(surface);
            updatePreviewSession();
        });
    }

    /**
     * Unregister a surface where the camera will stream to
     *
     * @param surface
     */
    public void unregisterPreviewSurface(Surface surface) {
        if(mBackgroundThread == null) {
            startBackgroundThread();
        }
        mBackgroundHandler.post(()-> {
            mSurfaces.remove(surface);
            updatePreviewSession();
        });
    }


    public void openCamera() {
        if(mBackgroundThread == null) {
            startBackgroundThread();
        }


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
//        if (mCameraState!=States.OPENED) throw new AssertionError("Camera State wrong");
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
                CameraCharacteristics Characteristics = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera
                Integer facing = Characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = Characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
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
        Log.i("Cellmate","Get into update");
        //Avoid race condition because surfaces can be changed from different threads

        if(mCameraState == States.OPENED) {
            List<Surface> newSurfaces = new LinkedList<>();
            for(Surface s : mSurfaces) {
                if(s.isValid())
                    newSurfaces.add(s);
            }
            mSurfaces = newSurfaces;

            if(mSurfaces.isEmpty() && mCaptureSession != null && mCaptureSurfaces.isEmpty()) {
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


                    List<Surface> allSurfaces = new LinkedList<>();
                    allSurfaces.addAll(mSurfaces);
                    allSurfaces.addAll(mCaptureSurfaces);

                    // Here, we create a CameraCaptureSession for camera preview. It closes the previous session and its requests
                    mCameraDevice.createCaptureSession(allSurfaces, mSessionStateCallback, null);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            }
        }



    }

    private static Size mCaptureSize;
    private static Size mPreviewSize;
    private static Size mScreenSize;

    private static void setPreviewAndCaptureSize() {
        CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }


                if (map == null) {
                    continue;
                }

                Point displaySize = new Point();
                ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(displaySize);

                //Use screen size as a reference of aspect ratio to target capture size and preview size
                //When capture size and preview size aspect ratio is the same, we know that they get the same image with different resolution
                mScreenSize = new Size(displaySize.x,displaySize.y);
//                System.out.println("mScreenSize");
//                System.out.println(mScreenSize);
                //For capture, choose the smallest size but above 480 resolution
                //Assuming there is at least a size fit the aspect ratio of the screen
                for(Size option : map.getOutputSizes(ImageFormat.JPEG)) {
//                    System.out.println(option);
                    if(option.getWidth()*displaySize.x == option.getHeight()*displaySize.y
                            && Math.min(option.getWidth(), option.getHeight()) <= 480) {
                        if(mCaptureSize == null) {
                            mCaptureSize = option;
                        } else {
                            if(mCaptureSize.getHeight() * mCaptureSize.getWidth() < option.getHeight() * option.getWidth()) {
                                mCaptureSize = option;
                            }
                        }
                    }
                }
//                System.out.println("mCaptureSize");
//                System.out.println(mCaptureSize);



                //For preview, choose the largest size but under MAX_PREVIEW_SIZE
                //Assuming there is at least a size fit the aspect ratio of the screen
                for(Size option : map.getOutputSizes(SurfaceTexture.class)) {
//                    System.out.println(option);
                    if(option.getWidth()*displaySize.x == option.getHeight()*displaySize.y
                            && Math.min(option.getWidth(), option.getHeight()) <= MAX_PREVIEW_HEIGHT) {

                        if(mPreviewSize == null) {
                            mPreviewSize = option;
                        } else {
                            if(mPreviewSize.getHeight() * mPreviewSize.getWidth() < option.getHeight() * option.getWidth()) {
                                mPreviewSize = option;
                            }
                        }

                    }
                }
//                System.out.println("mpreviewSize");
//                System.out.println(mPreviewSize);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            e.printStackTrace();
        }

    }

    public Size getScreenSize(){
        return mScreenSize;
    }

    public Size getCaptureSize() {
        return mCaptureSize;
    }

    public Size getPreviewSize() {
        return mPreviewSize;
    }






    /**
     * Initiate a still image capture.
     */
    public void takePictureWithSurfaceRegisteredBefore(Surface surface) {
        System.out.println("in takePictureWithSurfaceRegisteredBefore");
        System.out.println("mSurfaces size is " + mSurfaces.size());
        if(mBackgroundThread == null) {
            startBackgroundThread();
        }
        mBackgroundHandler.post(()-> {
            mCaptureSurface = surface;
            lockFocus();
        });
    }

    /**
     * Initiate a still image capture.
     */
    public void takePictureWithoutSurfaceRegisteredBefore(Surface surface) {
        //Unimplemented
        return;
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        System.out.println("lockFocus");
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            System.out.println("before call to capture");
            captureStillPicture();
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void unlockFocus() {
        System.out.println("in unlockFocus");
        try {
            // Reset the auto-focus trigger
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            // After this, the camera will go back to the normal state of preview.
            mCaptureSession.setRepeatingRequest(mPreviewRequest, null,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }




    private void captureStillPicture() {
        System.out.println("captureStillPicture");
        try {
            if (null == mCameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mCaptureSurface);

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);


            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    unlockFocus();
                }
            };

            mCaptureSession.stopRepeating();
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }



    public int getDeviceOrientation() {
        return mDeviceOrientation;
    }


}
