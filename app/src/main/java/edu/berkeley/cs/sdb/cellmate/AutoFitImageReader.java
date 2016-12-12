package edu.berkeley.cs.sdb.cellmate;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.Surface;

import java.util.concurrent.atomic.AtomicBoolean;

public class AutoFitImageReader implements AutoCloseable {

    private CameraCharacteristics mCharacteristics;
    private Context mContext;
    private ImageReader mImageReader;
    private OnImageAvailableListener mListener;
    private AtomicBoolean mCaptureRequest;
    private int mCameraWidth;
    private int mCameraHeight;
    private double mCameraFx;
    private double mCameraFy;
    private double mCameraCx;
    private double mCameraCy;

    public interface OnImageAvailableListener {
        void onImageAvailable(Image image, double fx, double fy, double cx, double cy);
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image;
            try {
                image = reader.acquireLatestImage();
            } catch (IllegalStateException e) {
                e.printStackTrace();
                return;
            }

            if (image == null) {
                return;
            }

            // Stop transmitting images after one result comes back
            if (mCaptureRequest.getAndSet(false)) {
                // TODO: do the rotation and scaling here, this is a worker thread
                int width = image.getWidth();
                int height = image.getHeight();

                double aspectRatioCam = (double) mCameraWidth / mCameraHeight;
                double aspectRatioImg = (double) width / height;
                if (aspectRatioCam != aspectRatioImg) { // aspect ratio has to be the same
                    throw new RuntimeException("Image and Camera Aspect Ratios Are Not the Same");
                }

                // TODO: predict intrinsics on the server side
                double scale = (double) width / (double) mCameraWidth;
                double fx = mCameraFx * scale;
                double fy = mCameraFy * scale;
                double cx = mCameraCx * scale;
                double cy = mCameraCy * scale;
                if (getRotateCount() % 2 == 0) {
                    mListener.onImageAvailable(image, fx, fy, cx, cy);
                } else {
                    mListener.onImageAvailable(image, fy, fx, cy, cx);
                }
            } else {
                image.close();
            }
        }
    };

    public AutoFitImageReader(Context context, CameraCharacteristics characteristics, int width, int height, int format, int maxImages) {
        mContext = context;
        mCharacteristics = characteristics;

        // TODO this is a temporary hack. Should force view to be in 4:3 so we won't have to change ImageReader aspect ratio
        Rect sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (sensorSize.width() / 9 * 16 == sensorSize.height() || sensorSize.height() / 9 * 16 == sensorSize.width()) {
            // hardcoded common 16:9 resolution
            mImageReader = ImageReader.newInstance(1280, 720, format, maxImages);
        } else {
            mImageReader = ImageReader.newInstance(width, height, format, maxImages);
        }

        mCaptureRequest = new AtomicBoolean(false);
    }

    public Surface getSurface() {
        return mImageReader.getSurface();
    }

    public void close() {
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    public void setOnImageAvailableListener(OnImageAvailableListener listener, Handler handler) {
        mListener = listener;
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, handler);
    }

    /**
     * Request the ImageReader to call the listener with the next imediate image.
     * This method is thread safe.
     */
    public boolean requestCapture() {
        // Assume the camera is not changed between here and when the image is available
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mContext);
        mCameraWidth = Integer.parseInt(preferences.getString(mContext.getString(R.string.camera_width_key), mContext.getString(R.string.camera_width_val)));
        mCameraHeight = Integer.parseInt(preferences.getString(mContext.getString(R.string.camera_height_key), mContext.getString(R.string.camera_height_val)));
        mCameraFx = Double.parseDouble(preferences.getString(mContext.getString(R.string.camera_fx_key), mContext.getString(R.string.camera_fx_val)));
        mCameraFy = Double.parseDouble(preferences.getString(mContext.getString(R.string.camera_fy_key), mContext.getString(R.string.camera_fy_val)));
        mCameraCx = Double.parseDouble(preferences.getString(mContext.getString(R.string.camera_cx_key), mContext.getString(R.string.camera_cx_val)));
        mCameraCy = Double.parseDouble(preferences.getString(mContext.getString(R.string.camera_cy_key), mContext.getString(R.string.camera_cy_val)));
        if (mCameraWidth == Integer.parseInt(mContext.getString(R.string.camera_width_val))
                || mCameraHeight == Integer.parseInt(mContext.getString(R.string.camera_height_val))
                || mCameraFx == Double.parseDouble(mContext.getString(R.string.camera_fx_val))
                || mCameraFy == Double.parseDouble(mContext.getString(R.string.camera_fy_val))
                || mCameraCx == Double.parseDouble(mContext.getString(R.string.camera_cx_val))
                || mCameraCy == Double.parseDouble(mContext.getString(R.string.camera_cy_val))) {
            return false;
        } else {
            if (!mCaptureRequest.getAndSet(true)) { // one capture request at a time
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Returns the number of times image has to be rotated clockwise to be in user perspective.
     *
     * @return number of times image has to be rotated 90 degrees clockwise.
     */
    private int getRotateCount() {
        int sensorRotation = mCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) / 90;
        int userRotation = ((Activity) mContext).getWindowManager().getDefaultDisplay().getRotation();
        if (sensorRotation - userRotation < 0) {
            return sensorRotation - userRotation + 4; // loop around to positive value
        } else {
            return sensorRotation - userRotation;
        }
    }
}