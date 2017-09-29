package edu.berkeley.cs.sdb.cellmate;

/**
 * Created by tongli on 9/28/17.
 */

public class Label {
    private int mRoomId;
    private double[] mPoint3;
    private String mName;

    public Label(int roomId, double[] point3, String name) {
            mRoomId = roomId;
            mPoint3 = point3;
            mName = name;
    }

    public int getmRoomId() {
        return mRoomId;
    }

    public double[] getmPoint3() {
        return mPoint3;
    }

    public String getmName() {
        return mName;
    }
}
