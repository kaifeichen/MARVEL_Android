package edu.berkeley.cs.sdb.cellmate;

import org.opencv.core.Point3;

/**
 * Created by tongli on 9/28/17.
 */

public class Label {
    private int mRoomId;
    Point3 mPoint3;
    private String mName;

    public Label(int roomId, Point3 point3, String name) {
            mRoomId = roomId;
            mPoint3 = point3;
            mName = name;
    }

    public int getRoomId() {
        return mRoomId;
    }

    public Point3 getPoint3() {
        return mPoint3;
    }

    public String getName() {
        return mName;
    }
}
