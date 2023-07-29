package com.example.module.api

import android.util.Log

class TestCompanionKotlin {
    companion object {
        private const val TAG = "TestCompanion"
        private val COM_TAG: String? = "null"

        fun test() {
            Log.i(TAG, "test companion")
        }
    }

    fun testTag() {
        Log.i(COM_TAG, "before inject tag is null")
    }
}