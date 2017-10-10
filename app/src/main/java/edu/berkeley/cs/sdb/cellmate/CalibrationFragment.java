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
import android.provider.Settings.Secure;

import edu.berkeley.cs.sdb.cellmate.task.GrpcSendTask;


public class CalibrationFragment extends Fragment {
    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;
    private List<ByteString> mImages;
    private int mTotal = 20;
    private Toast mToast;

    TextView mProgressText;
    String mProgressTextContents;
    ProgressBar mProgressBar;
    Button mCaptureButton;
    String mCaptureButtonContents;
    GrpcSendTask.QueryType mType;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // retain this fragment
        setRetainInstance(true);
        mImages = new ArrayList<>();
        mProgressTextContents = "Connecting to Server\nAuto Calibrating";
        mCaptureButtonContents = getString(R.string.capture);
        mToast = Toast.makeText(getActivity(), "", Toast.LENGTH_SHORT);
        mType = GrpcSendTask.QueryType.QUERY;

    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.calibration_fragment_layout, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        mProgressText = (TextView) view.findViewById(R.id.progressText);
        mProgressText.setText(mProgressTextContents);
        mProgressBar = (ProgressBar) view.findViewById(R.id.progressBar);
        mProgressBar.setMax(mTotal);
        mProgressBar.setProgress(mImages.size());
        mCaptureButton = (Button) view.findViewById(R.id.capture);
        mCaptureButton.setText(mCaptureButtonContents);
        setButtonsEnabled(false);
        mCaptureButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Code here executes on main thread after user presses button
                if(mCaptureButton.getText() == getString(R.string.capture)) {
                    setButtonsEnabled(false);
                    takePicture();
                } else if(mCaptureButton.getText() == getString(R.string.calibrate_again)){
                    mCaptureButton.setText(getString(R.string.capture));
                    mCaptureButtonContents = getString(R.string.capture);
                    mImages.clear();
                    mProgressBar.setProgress(0);
                    mProgressTextContents = String.valueOf(mImages.size()) + "/" + String.valueOf(mTotal);
                    mProgressText.setText(mProgressTextContents);
                    mType = GrpcSendTask.QueryType.CALIBRATE;
                }

            }
        });

        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(new GrpcPostImageRunnable(GrpcSendTask.QueryType.QUERY));
        }

        Camera camera = Camera.getInstance();
        Size captureSize = camera.getCaptureSize();
        mImageReader = ImageReader.newInstance(captureSize.getWidth(),captureSize.getHeight(),
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
                mProgressTextContents = String.valueOf(mImages.size()) + "/" + String.valueOf(mTotal);
                mProgressText.setText(mProgressTextContents);
                if(mImages.size() >= mTotal) {
                    mProgressTextContents = "Calibrating...";
                    mProgressText.setText(mProgressTextContents);
                    final Activity activity = getActivity();
                    if (activity != null) {
                        activity.runOnUiThread(new GrpcPostImageRunnable(GrpcSendTask.QueryType.CALIBRATE));
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
        GrpcSendTask.QueryType mType;
        public GrpcPostImageRunnable(GrpcSendTask.QueryType type) {
            mType = type;
        }

        @Override
        public void run() {
            try {
                String android_id = Secure.getString(getActivity().getContentResolver(),
                        Secure.ANDROID_ID);
                Camera camera = Camera.getInstance();
                Size captureSize = camera.getCaptureSize();

                Activity activity = getActivity();
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
                String Host = preferences.getString(getString(R.string.calibration_server_addr_key), getString(R.string.calibration_server_addr_val));
                String Port = preferences.getString(getString(R.string.calibration_server_port_key), getString(R.string.calibration_server_port_val));
                new GrpcSendTask(Host, Integer.valueOf(Port), mImages, mType, android_id, captureSize, mGrpcCalibrationListener).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private GrpcSendTask.Listener mGrpcCalibrationListener = new GrpcSendTask.Listener() {
        @Override
        public void onResponse(CalibrationProto.CameraMatrix matrix) { // null means network error
            System.out.println("grpc sendtask onResponse");
            if(mType == GrpcSendTask.QueryType.QUERY) {
                System.out.println("grpc sendtask onResponse -> QUERY");
                if (matrix == null) {
                    System.out.println("grpc sendtask onResponse -> QUERY -> null");
                    //Calibration Fragment just started
                    //But cannot connect to server, either server is died, or phone no wifi
                    mProgressTextContents = "Auto Calibration Failed, Please make sure:\n" +
                            "1. Your phone has internet access\n" +
                            "2. Calibration server host and port in setting is correctly settled\n" +
                            "3. Calibration server is on\n" +
                            "Then start calibration fragment in setting again" ;
                    mProgressText.setText(mProgressTextContents);
                } else {
                    System.out.println(matrix.getResultMessage());
                    if(matrix.getResultMessage().compareTo("Succeed") == 0) {
                        System.out.println("grpc sendtask onResponse -> succeed");
                        Activity activity = getActivity();
                        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putString(getString(R.string.camera_fx_key), Double.toString(matrix.getFx()));
                        editor.putString(getString(R.string.camera_fy_key), Double.toString(matrix.getFy()));
                        editor.putString(getString(R.string.camera_cx_key), Double.toString(matrix.getCx()));
                        editor.putString(getString(R.string.camera_cy_key), Double.toString(matrix.getCy()));
                        editor.apply();
                        mProgressTextContents = "Calibration Done, Result is saved to Settings";
                        mProgressText.setText(mProgressTextContents);
                    } else if(matrix.getResultMessage().compareTo("Model did not found") == 0){
                        System.out.println("grpc sendtask onResponse -> Model did not found");
                        mProgressTextContents = "There is no saved record of this phone, please manually calibrate";
                        mProgressText.setText(mProgressTextContents);
                    } else {
                        System.out.println("grpc sendtask onResponse -> None");
                        throw new IllegalStateException("Response in illegal state");
                    }
                    mCaptureButton.setText(getString(R.string.calibrate_again));
                    mCaptureButtonContents = getString(R.string.calibrate_again);
                    setButtonsEnabled(true);
                }
            } else if(mType == GrpcSendTask.QueryType.CALIBRATE){
                if (matrix == null) {
                    mProgressTextContents = "Calibration Failed, Please make sure:\n" +
                            "1. Your phone has internet access\n" +
                            "2. Calibration server host and port in setting is correctly settled" +
                            "3. THe calibration server is on";
                    mProgressText.setText(mProgressTextContents);
                } else {
                    if(matrix.getResultMessage().compareTo("Succeed") == 0) {
                        Activity activity = getActivity();
                        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
                        SharedPreferences.Editor editor = preferences.edit();
                        editor.putString(getString(R.string.camera_fx_key), Double.toString(matrix.getFx()));
                        editor.putString(getString(R.string.camera_fy_key), Double.toString(matrix.getFy()));
                        editor.putString(getString(R.string.camera_cx_key), Double.toString(matrix.getCx()));
                        editor.putString(getString(R.string.camera_cy_key), Double.toString(matrix.getCy()));
                        editor.apply();

                        mProgressTextContents = "Calibration Done, Result is saved to Settings";
                        mProgressText.setText(mProgressTextContents);
                    } else if(matrix.getResultMessage().compareTo("Invalid")==0) {
                        mProgressTextContents = "Calibration Failed, Please make sure:\n" +
                                "You are takeing the picture of chessboard";
                        mProgressText.setText(mProgressTextContents);
                    }
                }
                mCaptureButton.setText(getString(R.string.calibrate_again));
                mCaptureButtonContents = getString(R.string.calibrate_again);
            } else {
                throw new IllegalStateException("Calibration fragment in illegal state");
            }
        }
    };
}
