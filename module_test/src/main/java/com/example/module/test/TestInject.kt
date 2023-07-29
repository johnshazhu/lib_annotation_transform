package com.example.module.test

import android.util.Log
import com.example.module.api.TestInjectApi
import com.lib.annotation.Inject

object TestInject {
    @Inject(target = TestInjectApi::class, name = "test", replace = true)
    fun test() {
        Log.i("xdebug", "inject test success")
    }
}