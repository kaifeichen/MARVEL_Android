package edu.berkeley.cs.sdb.cellmate.task;

import android.os.AsyncTask;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import edu.berkeley.cs.sdb.bosswave.BosswaveClient;
import edu.berkeley.cs.sdb.bosswave.BosswaveResponse;
import edu.berkeley.cs.sdb.bosswave.ResponseHandler;

class BwInitTask extends AsyncTask<Void, Void, Boolean> {
    private final File mKeyFile;
    private final Listener mTaskListener;
    private final Semaphore mSem;
    private final AtomicBoolean mSuccess;
    private final String mBosswaveRouterAddr;
    private final int mBbosswaveRouterPort;
    private final ResponseHandler mResponseHandler = new ResponseHandler() {
        @Override
        public void onResponseReceived(BosswaveResponse response) {
            if (response.getStatus().equals("okay")) {
                System.out.println("bowsss wave inite successfullllllllly");
                mSuccess.set(true);
            } else {

                mSuccess.set(false);
            }
            mSem.release();
        }
    };
    private BosswaveClient mBosswaveClient;

    public BwInitTask(File keyFile, Listener listener, String bosswaveRouterAddr, int bosswaveRouterPort) {
        mKeyFile = keyFile;
        mTaskListener = listener;
        mSem = new Semaphore(0);
        mSuccess = new AtomicBoolean(false);
        mBosswaveRouterAddr = bosswaveRouterAddr;
        mBbosswaveRouterPort = bosswaveRouterPort;

    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        try {
            mBosswaveClient = new BosswaveClient(mBosswaveRouterAddr, mBbosswaveRouterPort);
            // Set the Bosswave entity to be used for subsequent operations
            mBosswaveClient.setEntityFromFile(mKeyFile, mResponseHandler);
            // Enable auto chain by default
            mBosswaveClient.overrideAutoChainTo(true);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        try {
            mSem.acquire();
            return mSuccess.getAndSet(false);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    protected void onPostExecute(Boolean success) {
        mTaskListener.onResponse(success, mBosswaveClient);
    }

    public interface Listener {
        void onResponse(boolean success, BosswaveClient client);
    }
}
