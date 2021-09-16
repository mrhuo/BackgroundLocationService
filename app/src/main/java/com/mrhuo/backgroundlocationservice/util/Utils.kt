package com.mrhuo.backgroundlocationservice.util
import android.util.Log
import com.mrhuo.backgroundlocationservice.TAG

internal fun logger(msg: Any?) {
    if (msg != null) {
        if (msg is Throwable) {
            msg.printStackTrace()
            Log.e(TAG, "[${Thread.currentThread().name}-${Thread.currentThread().id}] " + (msg.message ?: "发生异常，错误未知！"))
            return
        }
        Log.d(TAG, "[${Thread.currentThread().name}-${Thread.currentThread().id}] $msg")
    }
}