package edu.berkeley.cs.sdb.cellmate.data;

import android.support.annotation.NonNull;

/**
 * Created by tongli on 12/5/17.
 */

public class SortingElement implements Comparable<SortingElement>{
    private int index;
    private double value;
    private int rankSum = 0;
    public SortingElement(int index) {
        this.index = index;
    }

    @Override
    public int compareTo(SortingElement other) {
        return Double.compare(value, other.value);
    }

    public void setValue(double value) {
        this.value = value;
    }

    public int getIndex() {
        return index;
    }

    public void addRank(int rank) {
        rankSum += rank;
    }

    public int getRankSum() {
        return rankSum;
    }
}
