package com.example.module.api;

import android.util.Log;

public class FieldTestUtil {
    public static final String TAG = null;

    private static final int MAX_COUNT = Integer.parseInt("0");

    private String content;

    private Object user;

    private int test = Integer.parseInt("0");

    public FieldTestUtil() {
        initInfo();
    }

    public void initInfo() {

    }

    public void test() {
        Log.i(TAG, "old tag is FieldTestUtil, MAX_COUNT = " + MAX_COUNT);
        Log.i(TAG, "test = " + test);
        Log.i(TAG, "content = " + content);
        Log.i(TAG, "user = " + user);
    }
}
