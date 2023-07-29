package com.example.app

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import com.example.module.api.BaseActivity
import com.example.module.api.FieldTestUtil
import com.example.module.api.TestCompanionKotlin
import com.example.module.api.TestInjectApi
import java.util.*

class MainActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.test).setOnClickListener {
            Toast.makeText(this, "hello", Toast.LENGTH_SHORT).show()
        }

        TestInjectApi.test()
        FieldTestUtil().test()
        Log.i("xdebug", FieldTestUtil.TAG)
        TestCompanionKotlin.test()
        TestCompanionKotlin().testTag()
    }

    override fun onResume() {
        super.onResume()
        test(1, Date(System.currentTimeMillis()).toString())
    }
}