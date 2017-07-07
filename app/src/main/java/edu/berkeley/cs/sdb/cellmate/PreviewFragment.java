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




            Camera camera = Camera.getInstance();


            mPreviewSize = camera.getBestPreviewSize(width,height);

            updateSensorOrientation();
            configureTransform(width, height);

            // We configure the size of default buffer to be the size of camera preview we want.
            surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            mTextureViewSurface = new Surface(surfaceTexture);

            Log.i("CellMate","preview fragment register++++");
            camera.registerPreviewSurface(mTextureViewSurface);
//            configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            Log.i(LOG_TAG, "onSurfaceTextureSizeChanged, width=" + width + ",height=" + height);
            Camera camera = Camera.getInstance();


            mPreviewSize = camera.getBestPreviewSize(width,height);

            updateSensorOrientation();
            configureTransform(width, height);
            surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            if(mTextureViewSurface != null) {
                camera.unregisterPreviewSurface(mTextureViewSurface);
            }
            mTextureViewSurface = new Surface(surfaceTexture);
            Log.i("CellMate","preview fragment register++++");
            camera.registerPreviewSurface(mTextureViewSurface);
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

    public static PreviewFragment newInstance() {
        PreviewFragment previewFragment = new PreviewFragment();

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
     *
     */
    public void updateSensorOrientation() {
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
                    double right = x + size;
                    double left = x - size;
                    double bottom = y + size;
                    double top = y - size;
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

    public void clearHighlight() {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                Bitmap mBmp = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888);
                mHighLight.setImageBitmap(mBmp);
            });
        }
    }




    public interface StateCallback {

    }
}
