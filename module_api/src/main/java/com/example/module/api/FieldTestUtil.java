package com.example.module.api;

import android.util.Log;

public class FieldTestUtil {
    public static final String TAG = null;

    private static final int MAX_COUNT = Integer.parseInt("0");

    private int test = Integer.parseInt("0");

    public void test() {
        Log.i(TAG, "old tag is FieldTestUtil, MAX_COUNT = " + MAX_COUNT);
        Log.i(TAG, "test = " + test);
    }
}
