package edu.berkeley.cs.sdb.cellmate;

import android.content.Context;
import android.media.Image;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static java.text.DateFormat.getDateTimeInstance;

class HttpPostImgTask extends AsyncTask<Void, Void, String> {
    private static final String LOG_TAG = "cellmate";

    private final Context mContext;
    private final OkHttpClient mHttpClient;
    private final String mUrl;
    private final Image mImage;
    private final double mFx;
    private final double mFy;
    private final double mCx;
    private final double mCy;
    private final Listener mListener;
    private Exception mException;

    public HttpPostImgTask(Context context, OkHttpClient httpClient, String url, Image image, double fx, double fy, double cx, double cy, Listener listener) {
        mContext = context;
        mHttpClient = httpClient;
        mUrl = url;
        mImage = image;
        mFx = fx;
        mFy = fy;
        mCx = cx;
        mCy = cy;
        mListener = listener;
        mException = null;
    }

    @Override
    protected String doInBackground(Void... voids) {
        String timeStamp = getDateTimeInstance().format(new Date());
        ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        mImage.close();
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", timeStamp, RequestBody.create(MediaType.parse("application/octet-stream"), bytes))
                .addFormDataPart("fx", Double.toString(mFx))
                .addFormDataPart("fy", Double.toString(mFy))
                .addFormDataPart("cx", Double.toString(mCx))
                .addFormDataPart("cy", Double.toString(mCy))
                .build();
        Request request = new Request.Builder()
                .url(mUrl)
                .post(requestBody)
                .build();

        String result = null;
        try {
            Response response = mHttpClient.newCall(request).execute();
            if (response.isSuccessful()) {
                result = response.body().string();
            } else {
                Log.e(LOG_TAG, "HTTP Error " + response.code() + ":" + response.message());
            }
        } catch (IOException e) {
            e.printStackTrace();
            mException = e;
        }

        return result; // null means network error
    }

    @Override
    protected void onPostExecute(String result) {
        if (mException != null && mException instanceof IOException) {
            // onPostExecute is called on the UI/main thread
            Toast.makeText(mContext, "Network Error", Toast.LENGTH_LONG);
        }
        mListener.onResponse(result);
    }

    public interface Listener {
        void onResponse(String result); // null means network error
    }
}
