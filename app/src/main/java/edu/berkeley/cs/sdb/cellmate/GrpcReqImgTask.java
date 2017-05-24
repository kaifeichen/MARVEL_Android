package edu.berkeley.cs.sdb.cellmate;


import android.os.AsyncTask;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import io.grpc.StatusRuntimeException;



import android.media.Image;


import com.google.protobuf.ByteString;

import java.io.PrintWriter;
import java.io.StringWriter;


/**
 * Created by tongli on 5/21/17.
 */

public class GrpcReqImgTask extends AsyncTask<Void, Void, String> {
    private static final String LOG_TAG = "cellmate";


    private ManagedChannel mChannel;

    private String mHost;
    private int mPort;
    private Image mImage;
    private double mFx;
    private double mFy;
    private double mCx;
    private double mCy;
    private double mthetaAcce;
    private double mthetaGrav;
    private Listener mListener;

    public interface Listener {
        void onResponse(String result); // null means network error
    }

    public GrpcReqImgTask(String host, int port, Image image, double fx, double fy, double cx, double cy, double thetaAcce, double thetaGrav, Listener listener) {
        mHost = host;
        mPort = port;
        mImage = image;
        mFx = fx;
        mFy = fy;
        mCx = cx;
        mCy = cy;
        mthetaAcce = thetaAcce;
        mthetaGrav = thetaGrav;
        mListener = listener;
    }

    @Override
    protected String doInBackground(Void... voids) {
        String result = null;
        try {
            mChannel = ManagedChannelBuilder.forAddress(mHost, mPort).usePlaintext(true).build();
            GrpcServiceGrpc.GrpcServiceBlockingStub mStub = GrpcServiceGrpc.newBlockingStub(mChannel);
            CellmateProto.ClientQueryMessage request = CellmateProto.ClientQueryMessage.newBuilder()
                    .setImage(ByteString.copyFrom(ImgCodec.compressJPEG(mImage)))
                    .setFx(mFx)
                    .setFy(mFy)
                    .setCx(mCx)
                    .setCy(mCy)
                    .setHeight(mImage.getHeight())
                    .setWidth(mImage.getWidth())
                    .build();
            CellmateProto.ServerRespondMessage response = mStub.onClientQuery(request);
            result = response.getFoundName();
        } catch (StatusRuntimeException e) {
            e.printStackTrace();
        }
        return result; // null means network error
    }



    @Override
    protected void onPostExecute(String result) {
        mImage.close();
        mListener.onResponse(result);
    }
}
