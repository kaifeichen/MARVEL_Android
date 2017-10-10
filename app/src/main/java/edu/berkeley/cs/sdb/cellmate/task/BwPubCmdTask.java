package edu.berkeley.cs.sdb.cellmate.task;

import android.os.AsyncTask;

import java.io.IOException;
import java.util.concurrent.Semaphore;

import edu.berkeley.cs.sdb.bosswave.BosswaveClient;
import edu.berkeley.cs.sdb.bosswave.BosswaveResponse;
import edu.berkeley.cs.sdb.bosswave.ChainElaborationLevel;
import edu.berkeley.cs.sdb.bosswave.PayloadObject;
import edu.berkeley.cs.sdb.bosswave.PublishRequest;
import edu.berkeley.cs.sdb.bosswave.ResponseHandler;

class BwPubCmdTask extends AsyncTask<Void, Void, String> {
    private final BosswaveClient mBosswaveClient;
    private final String mTopic;
    private final byte[] mData;
    private final PayloadObject.Type mType;
    private final Listener mTaskListener;
    private final Semaphore mSem;
    private String mResult;
    private final ResponseHandler mResponseHandler = new ResponseHandler() {
        @Override
        public void onResponseReceived(BosswaveResponse response) {
            mResult = response.getStatus();
            mSem.release();
        }
    };

    public BwPubCmdTask(BosswaveClient bosswaveClient, String topic, byte[] data, PayloadObject.Type type, Listener listener) {
        mBosswaveClient = bosswaveClient;
        mTopic = topic;
        mData = data;
        mType = type;
        mTaskListener = listener;
        mSem = new Semaphore(0);
    }

    @Override
    protected String doInBackground(Void... voids) {
        try {
            PublishRequest.Builder builder = new PublishRequest.Builder(mTopic);
            builder.setAutoChain(true);
            builder.setChainElaborationLevel(ChainElaborationLevel.FULL);
            builder.clearPayloadObjects();
            PayloadObject po = new PayloadObject(mType, mData);
            builder.addPayloadObject(po);
            PublishRequest request = builder.build();

            mBosswaveClient.publish(request, mResponseHandler);
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            mSem.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return mResult;
    }

    @Override
    protected void onPostExecute(String response) {
        mTaskListener.onResponse(response);
    }

    public interface Listener {
        void onResponse(String response);
    }
}