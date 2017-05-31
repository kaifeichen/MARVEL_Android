package edu.berkeley.cs.sdb.cellmate;

import android.os.AsyncTask;

import java.io.IOException;

import edu.berkeley.cs.sdb.bosswave.BosswaveClient;

class BwCloseTask extends AsyncTask<Void, Void, Boolean> {
    private final BosswaveClient mBosswaveClient;
    private final Listener mTaskListener;

    public BwCloseTask(BosswaveClient bosswaveClient, Listener listener) {
        mBosswaveClient = bosswaveClient;
        mTaskListener = listener;
    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        try {
            mBosswaveClient.close();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    @Override
    protected void onPostExecute(Boolean success) {
        mTaskListener.onResponse(success);
    }

    public interface Listener {
        void onResponse(boolean success);
    }
}
