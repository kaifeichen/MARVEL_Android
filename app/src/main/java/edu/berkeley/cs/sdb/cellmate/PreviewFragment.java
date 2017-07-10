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
import android.graphics.Point;
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
import android.view.WindowManager;
import android.widget.ImageView;



public class PreviewFragment extends Fragment {

    private static final String LOG_TAG = "CellMate";
    Bitmap mBmp;
    private StateCallback mStateCallback;
    private AutoFitTextureView mTextureView;
    // The android.util.Size of camera preview.
    private Size mPreviewSize;
    Surface mTextureViewSurface;
    private Size mSurfaceTextureSize;
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            Log.i(LOG_TAG, "onSurfaceTextureAvailable, width=" + width + ",height=" + height);



            mSurfaceTextureSize = new Size(width, height);

            Camera camera = Camera.getInstance();


//            mPreviewSize = camera.getBestPreviewSize(width,height);
            mPreviewSize = camera.getPreviewSize();
            Log.i(LOG_TAG, "mPreviewSize, width=" + mPreviewSize.getWidth() + ",height=" + mPreviewSize.getHeight());
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
            Log.i(LOG_TAG, "mPreviewSize, width=" + mPreviewSize.getWidth() + ",height=" + mPreviewSize.getHeight());
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



    public void drawHighlight(String name, double x, double y, double size, double width, double height) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                if (x != -1) {



                    double ratio = height/mSurfaceTextureSize.getHeight();
                    double newWidth = width/ratio;
                    double newHeight = height/ratio;
                    double newX = x/ratio;
                    double newY = y/ratio;
                    double newSize = size/ratio;
                    double margin = (newWidth - mSurfaceTextureSize.getWidth())/2;
                    newX = newX - margin;

                    double finalX = newX;
                    double finalY = newY;
                    double finalWidth = mSurfaceTextureSize.getWidth();
                    double finalHeight = mSurfaceTextureSize.getHeight();
                    double finalSize = newSize;

//                    double finalX = x;
//                    double finalY = y;
//                    double finalWidth = width;
//                    double finalHeight = height;
//                    double finalSize = size;



                    Paint paint = new Paint();
                    paint.setColor(Color.BLUE);
                    paint.setStyle(Paint.Style.STROKE);
                    if (mBmp != null && !mBmp.isRecycled()) {
                        mBmp.recycle();
                    }



                    Bitmap mBmp = Bitmap.createBitmap((int)finalWidth, (int)finalHeight, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(mBmp);
                    double right = finalX + finalSize;
                    double left = finalX - finalSize;
                    double bottom = finalY + finalSize;
                    double top = finalY - finalSize;

                    Rect rect = new Rect((int) (left), (int) (top), (int) (right), (int) (bottom));
                    Rect rect2 = new Rect((int) 0, (int) 0, (int) (10), (int) (10));
                    Rect rect3 = new Rect((int) 0, (int) finalHeight - 10, (int) (10), (int) (finalHeight));
                    Rect rect4 = new Rect((int) finalWidth - 10, (int) 0, (int) (finalWidth), (int) (10));
                    Rect rect5 = new Rect((int) finalWidth - 10, (int) finalHeight - 10, (int) (finalWidth), (int) (finalHeight));
                    canvas.drawRect(rect, paint);
                    canvas.drawRect(rect2, paint);
                    canvas.drawRect(rect3, paint);
                    canvas.drawRect(rect4, paint);
                    canvas.drawRect(rect5, paint);
                    mHighLight.setImageBitmap(mBmp);


                    Point displaySize = new Point();
                    ((WindowManager) getActivity().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getSize(displaySize);
                    ratio = displaySize.x/finalWidth;

                    mHighLight.setTranslationY((float)(-1*(displaySize.y - finalHeight*ratio)/2));
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
