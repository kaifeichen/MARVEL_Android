package edu.berkeley.cs.sdb.cellmate;


import android.media.Image;
import android.os.AsyncTask;

import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;


class GrpcReqImgTask extends AsyncTask<Void, Void, String> {
    private final String mHost;
    private final int mPort;
    private final ByteString mData;
    private final double mFx;
    private final double mFy;
    private final double mCx;
    private final double mCy;
    private final Listener mListener;
    private double mX;
    private double mY;
    private double mQRWidth;
    private ManagedChannel mChannel;


    public GrpcReqImgTask(String host, int port, ByteString data, double fx, double fy, double cx, double cy, Listener listener) {
        mHost = host;
        mPort = port;
        mData = data;
        mFx = fx;
        mFy = fy;
        mCx = cx;
        mCy = cy;
        mListener = listener;
        mX = -1;
        mY = -1;
    }

    @Override
    protected String doInBackground(Void... voids) {
        String result = null;

        try {
            mChannel = ManagedChannelBuilder.forAddress(mHost, mPort).usePlaintext(true).build();
            GrpcServiceGrpc.GrpcServiceBlockingStub stub = GrpcServiceGrpc.newBlockingStub(mChannel);
            CellmateProto.ClientQueryMessage request = CellmateProto.ClientQueryMessage.newBuilder()
                    .setImage(mData)
                    .setFx(mFx)
                    .setFy(mFy)
                    .setCx(mCx)
                    .setCy(mCy)
                    .build();
            CellmateProto.ServerRespondMessage response = stub.onClientQuery(request);
            if(response.getX() == -1) {
                result = response.getName();
            } else {
                mX = response.getX();
                mY = response.getY();
                mQRWidth = response.getWidth();
                result = response.getName();
            }
        } catch (StatusRuntimeException e) {
            e.printStackTrace();
        }
        return result; // null means network error
    }

    @Override
    protected void onPostExecute(String result) {
        mListener.onResponse(result, mX, mY, mQRWidth);
    }

    public interface Listener {
        void onResponse(String result, double x, double y, double width); // null means network error
    }
}
