package com.example.module.test;

import android.util.Log;

import com.example.module.api.BaseActivity;
import com.example.module.api.FieldTestUtil;
import com.example.module.api.TestCompanionKotlin;
import com.lib.annotation.Inject;

public class NonStaticInject {
    @Inject(target = FieldTestUtil.class)
    public static final String TAG = "MODIFIED_TAG";

    @Inject(target = FieldTestUtil.class)
    private static final int MAX_COUNT = 100;

    @Inject(target = TestCompanionKotlin.Companion.class)
    public static final String COM_TAG = "Companion_TAG";

    @Inject(target = TestCompanionKotlin.Companion.class, name = "test")
    public void logOnResume() {
        Log.i("xdebug", "logTest");
    }

    @Inject(target = BaseActivity.class, name = "test")
    public void test(int num, String date) {
        Log.i("xdebug", "logTest " + num + ", date = " + date);
    }
}
