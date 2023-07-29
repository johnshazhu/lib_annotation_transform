package com.example.module.test;

import android.util.Log;
import android.view.View;

import com.lib.annotation.Inject;

import java.util.HashMap;
import java.util.Map;

public class ViewClickUtil {
    private static final Map<Integer, Long> clickTimeMap = new HashMap<>();
    private ViewClickUtil() {}

    @Inject(target = View.OnClickListener.class, name = "onClick", before = true)
    public static boolean onClick(View v) {
        long lastClickTime = 0;
        long now = System.currentTimeMillis();
        int identityHashCode = System.identityHashCode(v);

        synchronized (clickTimeMap) {
            if (clickTimeMap.containsKey(identityHashCode)) {
                lastClickTime = clickTimeMap.get(identityHashCode);
            }

            Log.i("xdebug", "lastClickTime : " + lastClickTime + ", now : " + now + ", diff : " + (now - lastClickTime));
            if (Math.abs(now - lastClickTime) < 1000) {
                clickTimeMap.remove(identityHashCode);
                Log.e("xdebug", "isQuickClick true");
                return true;
            }

            clickTimeMap.put(identityHashCode, now);
        }
        return false;
    }
}
