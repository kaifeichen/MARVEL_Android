package edu.berkeley.cs.sdb.cellmate;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
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
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;


public class PreviewFragment extends Fragment {


    private static final String LOG_TAG = "CellMate";
    Bitmap mBmp;
    private AutoFitTextureView mTextureView;
    // The android.util.Size of camera preview.
    private Size mPreviewSize;
    private Size totalSize = null;
    private Size highlightFrameSize = null;
    Surface mTextureViewSurface;
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            Log.i(LOG_TAG, "onSurfaceTextureAvailable, width=" + width + ",height=" + height);
            totalSize = new Size(width, height);
            Camera camera = Camera.getInstance();
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
            mPreviewSize = camera.getPreviewSize();
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
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
//                System.out.println(motionEvent.getX() + "  " + motionEvent.getY());
                if(totalSize!=null && highlightFrameSize!=null) {
                    double x = motionEvent.getX();
                    double y = motionEvent.getY();
                    double ratio = totalSize.getWidth() * 1.0/highlightFrameSize.getWidth();
                    double newX = x/ratio;
                    double newY = y/ratio;
                    for(int i = 0; i < mName.size(); i++) {
                        if(newX >= mLeft.get(i) && newX <= mRight.get(i) && newY>=mTop.get(i) && newY<=mBottom.get(i)) {
                            mStateCallback.previewOnClicked(true, mName.get(i));
                            return true;
                        }

                    }
                    mStateCallback.previewOnClicked(false, null);

                } else {
                    mStateCallback.previewOnClicked(false, null);
                }
                return true;
            }
        });

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

    ArrayList<String> mName = new ArrayList<>();
    ArrayList<Double> mRight = new ArrayList<>();
    ArrayList<Double> mLeft = new ArrayList<>();
    ArrayList<Double> mBottom = new ArrayList<>();
    ArrayList<Double> mTop = new ArrayList<>();


    public void drawHighlight(List<String> name, List<Double> x, List<Double> y, List<Double> size, double width, double height) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                if (x.get(0) != -1) {
                    highlightFrameSize = new Size((int)width, (int)height);

                    Paint paint = new Paint();
                    paint.setColor(Color.BLUE);
                    paint.setStyle(Paint.Style.STROKE);
                    if (mBmp != null && !mBmp.isRecycled()) {
                        mBmp.recycle();
                    }



                    Bitmap mBmp = Bitmap.createBitmap((int)width, (int)height, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(mBmp);
                    mName.clear();
                    mRight.clear();
                    mLeft.clear();
                    mBottom.clear();
                    mTop.clear();
                    for(int i = 0; i < name.size(); i++) {
                        mName.add(name.get(i));
                        mRight.add(x.get(i) + size.get(i));
                        mLeft.add(x.get(i)  - size.get(i));
                        mBottom.add(y.get(i) + size.get(i));
                        mTop.add(y.get(i) - size.get(i));


//                        Rect rect2 = new Rect((int) 0, (int) 0, (int) (10), (int) (10));
//                        Rect rect3 = new Rect((int) 0, (int) height - 10, (int) (10), (int) (height));
//                        Rect rect4 = new Rect((int) width - 10, (int) 0, (int) (width), (int) (10));
//                        Rect rect5 = new Rect((int) width - 10, (int) height - 10, (int) (width), (int) (height));

//                        canvas.drawRect(rect2, paint);
//                        canvas.drawRect(rect3, paint);
//                        canvas.drawRect(rect4, paint);
//                        canvas.drawRect(rect5, paint);
                        Rect rect = new Rect(mLeft.get(i).intValue(), mTop.get(i).intValue(), mRight.get(i).intValue(), mBottom.get(i).intValue());
                        canvas.drawRect(rect, paint);
                        paint.setTextSize(size.get(i).floatValue());
                        canvas.drawText(name.get(i), mLeft.get(i).floatValue(), mBottom.get(i).floatValue(), paint);
                    }

                    mHighLight.setImageBitmap(mBmp);
                } else {
                    highlightFrameSize = null;
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



    private StateCallback mStateCallback;
    public interface StateCallback {
        void previewOnClicked(boolean isTargeting,String target);
    }
}
