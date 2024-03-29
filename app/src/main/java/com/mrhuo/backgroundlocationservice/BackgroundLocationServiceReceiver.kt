package com.mrhuo.backgroundlocationservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mrhuo.backgroundlocationservice.model.MyLocation
import com.mrhuo.backgroundlocationservice.util.logger
import kotlin.math.roundToInt

abstract class BackgroundLocationServiceReceiver : BroadcastReceiver() {
    final override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null || intent.extras?.size() == 0) {
            return
        }
        when (intent.action) {
            ACTION_REPORT_ERROR -> {
                val errorMessage = intent.getStringExtra("error")
                if (errorMessage != null) {
                    onReceiveError(errorMessage)
                }
            }
            ACTION_REPORT_LOCATION -> {
                val location: MyLocation? = intent.getSerializableExtra("location") as MyLocation?
                if (location != null) {
                    onReceiveLocation(location)
                } else {
                    onReceiveError("未获取到经纬度")
                }
            }
            ACTION_REPORT_LOCATION_LOSS -> {
                val lossTime = intent.getLongExtra("lossTime", 0)
                onLocationLoss(lossTime)
            }
            ACTION_REPORT_PROVIDER_STATUS -> {
                val provider = intent.getStringExtra("provider") ?: ""
                val status = intent.getBooleanExtra("status", false)
                onProviderStatusChanged(provider, status)
            }
        }
    }

    abstract fun onReceiveLocation(location: MyLocation)

    open fun onLocationLoss(lossTime: Long) {
        logger("GPS信号已丢失 ${(lossTime / 1000.0).roundToInt()} 秒")
    }

    open fun onProviderStatusChanged(provider: String, status: Boolean) {
        logger("设备[${provider}]状态变为 $status")
    }

    open fun onReceiveError(errorMessage: String) {
        logger(errorMessage)
    }
}