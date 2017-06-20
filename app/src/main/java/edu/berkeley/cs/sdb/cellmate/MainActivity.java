package edu.berkeley.cs.sdb.cellmate;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.view.Surface;

import com.splunk.mint.Mint;

public class MainActivity extends AppCompatActivity {
    private static final String MINT_API_KEY = "76da1102";

    CameraFragment mCameraFragment;
    PreviewFragment mPreviewFragment;
    private final PreviewFragment.OnSurfaceStateListener mPreviewOnSurfaceStateListener = new PreviewFragment.OnSurfaceStateListener() {
        @Override
        public void onSurfaceAvailable(Surface surface) {
            mCameraFragment.registerPreviewSurface(surface);
        }

        @Override
        public void onSurfaceDestroyed(Surface surface) {
            mCameraFragment.unregisterPreviewSurface(surface);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Mint.initAndStartSession(this, MINT_API_KEY);

        if (savedInstanceState == null) {
            mCameraFragment = CameraFragment.newInstance(new Size(640, 480));
            mPreviewFragment = PreviewFragment.newInstance(mPreviewOnSurfaceStateListener);

            getFragmentManager().beginTransaction().replace(android.R.id.content, mCameraFragment).commit();
            getFragmentManager().beginTransaction().replace(android.R.id.content, mPreviewFragment).commit();
        }
    }
}
