package edu.berkeley.cs.sdb.cellmate;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;


public class PreviewFragment extends Fragment {
    private static final String LOG_TAG = "CellMate";
    Bitmap mBmp;
    private StateCallback mStateCallback;
    private AutoFitTextureView mTextureView;
    // The android.util.Size of camera preview.
    private Size mPreviewSize;
    Surface mTextureViewSurface;
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            Log.i(LOG_TAG, "onSurfaceTextureAvailable, width=" + width + ",height=" + height);

            mTextureViewSurface = new Surface(surfaceTexture);

//            configureTransform(width, height);
            Camera camera = Camera.getInstance();
            Log.i("CellMate","preview fragment register++++");
            camera.registerPreviewSurface(mTextureViewSurface);
            updateSensorOrientation(camera.getmSensorOrientation());
            configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            mTextureViewSurface = null;
            surfaceTexture.release();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }
    };
    private ImageView mHighLight;

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
        return inflater.inflate(R.layout.preview_fragment, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);

        mHighLight = (ImageView) view.findViewById(R.id.imageView);


    }

    @Override
    public void onResume() {
        super.onResume();

        Camera camera = Camera.getInstance();
        if(mTextureViewSurface != null) {
            Log.i("CellMate","preview fragment register++++");
            camera.registerPreviewSurface(mTextureViewSurface);
        }

    }


    @Override
    public void onPause() {
        Camera camera = Camera.getInstance();
        Log.i("CellMate","preview fragment unregister-----");
        camera.unregisterPreviewSurface(mTextureViewSurface);
        super.onPause();
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

    public void drawHighlight(String name, double x, double y, double size) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                if (x != -1) {
                    double right = 480 - y + size;
                    double left = 480 - y - size;
                    double bottom = x + size;
                    double top = Math.max(0, x - size);
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
            });
        }
    }

    public interface StateCallback {

    }
}
