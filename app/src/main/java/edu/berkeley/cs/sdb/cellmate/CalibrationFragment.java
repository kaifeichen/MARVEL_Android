package edu.berkeley.cs.sdb.cellmate;

/**
 * Created by tongli on 6/28/17.
 */

import android.app.Activity;
import android.app.Fragment;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;


public class CalibrationFragment extends Fragment {
    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;
    private List<ByteString> mImages;
    private int mTotal = 3;
    private Toast mToast;

    TextView mProgressText;
    ProgressBar mProgressBar;
    Button mCaptureButton;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.calibration_fragment_layout, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        mProgressText = (TextView) view.findViewById(R.id.progressText);
        mProgressBar = (ProgressBar) view.findViewById(R.id.progressBar);
        mProgressBar.setMax(mTotal);
        mCaptureButton = (Button) view.findViewById(R.id.capture);
        mToast = Toast.makeText(getActivity(), "", Toast.LENGTH_SHORT);
        mCaptureButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Code here executes on main thread after user presses button
                setButtonsEnabled(false);
                takePicture();
            }
        });
        Camera camera = Camera.getInstance();
        Size cameraSize = camera.getCameraSize();
        mImages = new ArrayList<>();
        mImageReader = ImageReader.newInstance(cameraSize.getWidth(), cameraSize.getHeight(),
                ImageFormat.JPEG, /*maxImages*/2);
        mImageReader.setOnImageAvailableListener(
                mOnImageAvailableListener, null);
        super.onViewCreated(view, savedInstanceState);
    }

    private void takePicture() {
        System.out.println("in takePicture");
        Camera camera = Camera.getInstance();
        camera.takePictureWithSurfaceRegisteredBefore(mImageReader.getSurface());
    }

    @Override
    public void onResume() {
        super.onResume();

        Camera camera = Camera.getInstance();
        Log.i("CellMate","calibration fragment register++++");
        camera.registerCaptureSurface(mImageReader.getSurface());
    }

    @Override
    public void onPause() {
        Camera camera = Camera.getInstance();
        Log.i("CellMate","calibration fragment unregister----");
        camera.unregisterCaptureSurface(mImageReader.getSurface());


        super.onPause();
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);
                mImages.add(ByteString.copyFrom(bytes));
                mProgressBar.setProgress(mImages.size());
                mProgressText.setText(String.valueOf(mImages.size()) + "/" + String.valueOf(mTotal));
                if(mImages.size() >= mTotal) {
                    mProgressText.setText("Calibrating...");
                    final Activity activity = getActivity();
                    if (activity != null) {
                        activity.runOnUiThread(new GrpcPostImageRunnable());
                    }
                }

            } finally {
                if (image != null) {
                    image.close();
                }
                setButtonsEnabled(true);
            }
        }

    };

    /**
     * Enables or disables click events for all buttons.
     *
     */
    private void setButtonsEnabled(final boolean onOrOff) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> {
                mCaptureButton.setEnabled(onOrOff);
            });
        }
    }

    private class GrpcPostImageRunnable implements Runnable {
        public GrpcPostImageRunnable() {

        }

        @Override
        public void run() {
            try {
                Activity activity = getActivity();
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
                String Host = preferences.getString(getString(R.string.calibration_server_addr_key), getString(R.string.calibration_server_addr_val));
                String Port = preferences.getString(getString(R.string.calibration_server_port_key), getString(R.string.calibration_server_port_val));
                new GrpcSendTask(Host, Integer.valueOf(Port), mImages, mGrpcCalibrationListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private GrpcSendTask.Listener mGrpcCalibrationListener = new GrpcSendTask.Listener() {
        @Override
        public void onResponse(double[] matrix) { // null means network error
            if (matrix == null) {
                showToast("Network error", Toast.LENGTH_SHORT);
            } else {
//                Activity activity = getActivity();
//                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
//                SharedPreferences.Editor editor = preferences.edit();
//                editor.putLong(getString(R.string.camera_fx_key), 10);
//                editor.commit();

                showToast(String.valueOf(matrix[0]) + " " +
                        String.valueOf(matrix[1]) + " " +
                        String.valueOf(matrix[2]) + " " +
                        String.valueOf(matrix[3]) + " " , Toast.LENGTH_LONG);
            }
        }
    };

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
}
