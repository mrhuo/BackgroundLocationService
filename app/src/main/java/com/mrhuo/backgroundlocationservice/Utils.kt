package com.mrhuo.backgroundlocationservice
import android.util.Log

internal fun logger(msg: Any?) {
    if (msg != null) {
        if (msg is Throwable) {
            msg.printStackTrace()
            Log.e(TAG, "[${Thread.currentThread().id}] " + (msg.message ?: "发生异常，错误未知！"))
            return
        }
        Log.i(TAG, "[${Thread.currentThread().id}] $msg")
    }
}