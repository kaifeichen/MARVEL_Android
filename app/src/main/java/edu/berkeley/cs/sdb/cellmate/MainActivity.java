package edu.berkeley.cs.sdb.cellmate;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.view.Surface;

import com.splunk.mint.Mint;

public class MainActivity extends AppCompatActivity implements CameraFragment.StateCallback, PreviewFragment.StateCallback {
    private static final String MINT_API_KEY = "76da1102";

    @Override
    public void onSensorOrientationChanged(int sensorOrientation) {
        PreviewFragment previewFragment = (PreviewFragment) getFragmentManager().findFragmentById(android.R.id.content);
        previewFragment.updateSensorOrientation(sensorOrientation);
    }

    @Override
    public void onSurfaceAvailable(Surface surface) {
        CameraFragment cameraFragment = (CameraFragment) getFragmentManager().findFragmentByTag(getString(R.string.camera_fragment));
        cameraFragment.registerPreviewSurface(surface);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Mint.initAndStartSession(this, MINT_API_KEY);

        if (savedInstanceState == null) {
            Size size = new Size(640, 480);
            CameraFragment cameraFragment = CameraFragment.newInstance(size);
            getFragmentManager().beginTransaction().add(cameraFragment, getString(R.string.camera_fragment)).commit();

            PreviewFragment previewFragment = PreviewFragment.newInstance(size);
            getFragmentManager().beginTransaction().replace(android.R.id.content, previewFragment).commit();
        }
    }
}
