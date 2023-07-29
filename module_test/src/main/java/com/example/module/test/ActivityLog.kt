package com.example.module.test

import android.util.Log
import com.example.module.api.BaseActivity
import com.lib.annotation.Inject

object ActivityLog {
    @Inject(target = BaseActivity::class, name = "onResume", before = true)
    fun onResume() {
        Log.i("xdebug", "onResume")
    }
}