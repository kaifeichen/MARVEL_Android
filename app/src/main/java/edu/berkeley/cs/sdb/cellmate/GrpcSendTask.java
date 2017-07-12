package edu.berkeley.cs.sdb.cellmate;

/**
 * Created by tongli on 6/28/17.
 */





import android.os.AsyncTask;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;


import android.media.Image;
import android.os.Build;
import android.os.SystemClock;
import android.util.Size;


import com.google.protobuf.ByteString;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Created by tongli on 6/6/17.
 */

public class GrpcSendTask extends AsyncTask<Void, Void, CalibrationProto.CameraMatrix>{
    public enum QueryType {
        QUERY,
        CALIBRATE
    }
    private ManagedChannel mChannel;
    private String mHost;
    private int mPort;
    List<ByteString> mImages;
    private Listener mListener;
    CalibrationProto.CameraMatrix mResults;
    //Either "QUERY" or "CALIBRATE"
    QueryType mQueryType;
    String mDeviceId;
    Size mCaptureSize;
    public interface Listener {
        void onResponse(CalibrationProto.CameraMatrix results); // null means network error
    }

    public GrpcSendTask(String host, int port, List<ByteString> images, QueryType queryType , String deviceID, Size captureSize, Listener listener) {
        mHost = host;
        mPort = port;
        mImages = images;
        mListener = listener;
        mResults = null;
        mQueryType = queryType;
        mDeviceId = deviceID;
        mCaptureSize = captureSize;
    }

    @Override
    protected CalibrationProto.CameraMatrix doInBackground(Void... voids) {
        final CountDownLatch finishLatch = new CountDownLatch(1);
        mChannel = ManagedChannelBuilder.forAddress(mHost, mPort).usePlaintext(true).build();
        CalibrationServiceGrpc.CalibrationServiceStub mStub = CalibrationServiceGrpc.newStub(mChannel);
        StreamObserver<CalibrationProto.CameraMatrix> responseObserver = new StreamObserver<CalibrationProto.CameraMatrix>() {
            @Override
            public void onNext(CalibrationProto.CameraMatrix matrix) {
                mResults = matrix;
                System.out.println("onNext");
            }

            @Override
            public void onError(Throwable t) {
                finishLatch.countDown();
                System.out.println("on error");
                t.printStackTrace();
            }

            @Override
            public void onCompleted() {
                finishLatch.countDown();
                System.out.println("onComplete");
            }
        };


        StreamObserver<CalibrationProto.Image> requestObserver = mStub.calibrate(responseObserver);
        System.out.println("device name is " + getDeviceName());
        try {
            if(mQueryType == QueryType.QUERY) {
                CalibrationProto.Image queryMessage =
                        CalibrationProto.Image.newBuilder().
                                setCaptureWidth(mCaptureSize.getWidth()).
                                setCaptureHeight(mCaptureSize.getHeight()).
                                setDeviceId(mDeviceId).
                                setPhoneModel(getDeviceName()).
                                setMessageType("QUERY").
                                build();
                requestObserver.onNext(queryMessage);
                if (finishLatch.getCount() == 0) {
                    // RPC completed or errored before we finished sending.
                    // Sending further requests won't error, but they will just be thrown away.
                    return null;
                }
            } else if(mQueryType == QueryType.CALIBRATE) {
                for (int i = 0; i < mImages.size(); ++i) {
                    CalibrationProto.Image nextImage =
                            CalibrationProto.Image.newBuilder().
                                    setCaptureWidth(mCaptureSize.getWidth()).
                                    setCaptureHeight(mCaptureSize.getHeight()).
                                    setDeviceId(mDeviceId).
                                    setPhoneModel(getDeviceName()).
                                    setImage(mImages.get(i)).
                                    setMessageType("CALIBRATE").
                                    build();
                    requestObserver.onNext(nextImage);
                    if (finishLatch.getCount() == 0) {
                        // RPC completed or errored before we finished sending.
                        // Sending further requests won't error, but they will just be thrown away.
                        return null;
                    }
                    System.out.println("send image");
                }
            } else {
                throw new IllegalStateException("QueryType is undefined");
            }
        } catch (RuntimeException e) {
            // Cancel RPC
            requestObserver.onError(e);
            throw e;
        }
        // Mark the end of requests
        requestObserver.onCompleted();

        // Receiving happens asynchronously
        try {
            if (!finishLatch.await(1, TimeUnit.MINUTES)) {
                System.out.println("recordRoute can not finish within 1 minutes");
                return null;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return mResults;
    }

    @Override
    protected void onPostExecute(CalibrationProto.CameraMatrix results) {
        System.out.println();
        mListener.onResponse(results);
    }

    public  String getDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return model.toUpperCase();
        }
        return manufacturer.toUpperCase() + " " + model;
    }
}
