package edu.berkeley.cs.sdb.cellmate;


import android.content.Context;
import android.media.Image;
import android.os.AsyncTask;
import android.widget.Toast;

import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;


class GrpcReqImgTask extends AsyncTask<Void, Void, String> {
    private final Context mContext;
    private final String mHost;
    private final int mPort;
    private final Image mImage;
    private final double mFx;
    private final double mFy;
    private final double mCx;
    private final double mCy;
    private final int mWidth;
    private final int mHeight;
    private final Listener mListener;
    private Exception mException;

    public GrpcReqImgTask(Context context, String host, int port, Image image, double fx, double fy, double cx, double cy, Listener listener) {
        mContext = context;
        mHost = host;
        mPort = port;
        mImage = image;
        mFx = fx;
        mFy = fy;
        mCx = cx;
        mCy = cy;
        mWidth = image.getWidth();
        mHeight = image.getHeight();
        mListener = listener;
        mException = null;
    }

    @Override
    protected String doInBackground(Void... voids) {
        String result = null;
        ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        mImage.close(); // thread safe?
        try {
            ManagedChannel channel = ManagedChannelBuilder.forAddress(mHost, mPort).usePlaintext(true).build();
            GrpcServiceGrpc.GrpcServiceBlockingStub mStub = GrpcServiceGrpc.newBlockingStub(channel);
            CellmateProto.ClientQueryMessage request = CellmateProto.ClientQueryMessage.newBuilder()
                    .setImage(ByteString.copyFrom(bytes))
                    .setFx(mFx)
                    .setFy(mFy)
                    .setCx(mCx)
                    .setCy(mCy)
                    .setHeight(mHeight)
                    .setWidth(mWidth)
                    .build();
            CellmateProto.ServerRespondMessage response = mStub.onClientQuery(request);
            if(response.getX() == -1) {
                result = response.getName();
            } else {
                result = response.getName() + " " + String.valueOf(response.getX()) + " " + String.valueOf(response.getY());
            }

        } catch (StatusRuntimeException e) {
            e.printStackTrace();
            mException = e;
        }
        return result; // null means network error
    }

    @Override
    protected void onPostExecute(String result) {
        if (mException != null && mException instanceof StatusRuntimeException) {
            // onPostExecute is called on the UI/main thread
            Toast.makeText(mContext, "Network Error", Toast.LENGTH_LONG);
        }
        mListener.onResponse(result);
    }

    public interface Listener {
        void onResponse(String result); // null means network error
    }
}
