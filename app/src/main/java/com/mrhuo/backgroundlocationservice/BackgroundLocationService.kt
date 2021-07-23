package com.mrhuo.backgroundlocationservice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.*
import kotlin.concurrent.fixedRateTimer


internal const val TAG = "BKLocationService"
internal const val ACTION_LOCATION = "CRLocationService.action.LOCATION"
internal const val EXTRA_LOCATION_PROVIDER = "CRLocationService.extra.LOCATION_PROVIDER"
internal const val EXTRA_LOCATION_MIN_TIME_MS = "CRLocationService.extra.MIN_TIME_MS"
internal const val EXTRA_LOCATION_MIN_DISTANCE_M = "CRLocationService.extra.DISTANCE_M"
internal const val BROADCAST_RECEIVER_REPORT_LOCATION = "NaviLocationBroadcastReceiver.REPORT_LOCATION"
internal const val BROADCAST_RECEIVER_REPORT_ERROR = "NaviLocationBroadcastReceiver.REPORT_ERROR"
internal const val BROADCAST_RECEIVER_REPORT_PROVIDER_STATUS = "NaviLocationBroadcastReceiver.REPORT_PROVIDER_STATUS"
internal const val BROADCAST_RECEIVER_REPORT_LOCATION_LOSS = "NaviLocationBroadcastReceiver.REPORT_LOCATION_LOSS"
internal const val NOTIFICATION_ID = 0x0220

@SuppressLint("MissingPermission")
class BackgroundLocationService : Service() {
    private var mLocationManager: LocationManager? = null
    private var mLocationListener: LocationListener? = null
    private var mGNSSStatusCallback: GnssStatus.Callback? = null
    private var mIsServiceRunning  = false
    private val mServiceCreateAt: Date = Date()

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mIsServiceRunning) {
            return super.onStartCommand(intent, flags, startId)
        }
        destroyLocationManager()
        mLocationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (mLocationManager == null) {
            reportError(this, "LocationManager 为 null")
            return super.onStartCommand(intent, flags, startId)
        }
        mLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val crLocation = MyLocation(location)
                crLocation.satellite = mSatelliteCount
                logger("onLocationChanged [${crLocation}]")
                reportLocation(this@BackgroundLocationService, crLocation)
            }

            override fun onProviderDisabled(provider: String) {
                logger("onProviderDisabled [$provider]")
                reportProviderStatusChanged(this@BackgroundLocationService, provider, false)
            }

            override fun onProviderEnabled(provider: String) {
                logger("onProviderEnabled [$provider]")
                reportProviderStatusChanged(this@BackgroundLocationService, provider, true)
            }
        }
        when (intent?.action) {
            ACTION_LOCATION -> {
                val provider = intent.getStringExtra(EXTRA_LOCATION_PROVIDER) ?: "gps"
                val minTimeMS = intent.getLongExtra(EXTRA_LOCATION_MIN_TIME_MS, 0)
                val minDistanceM = intent.getFloatExtra(EXTRA_LOCATION_MIN_DISTANCE_M, 0f)
                logger("onStartCommand [provider=$provider, minTimeMS=$minTimeMS, minDistanceM=$minDistanceM]")
                if (provider.isEmpty()) {
                    reportError(this, "设备[${provider}]不可用")
                    return super.onStartCommand(intent, flags, startId)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        mGNSSStatusCallback = object : GnssStatus.Callback() {
                            override fun onSatelliteStatusChanged(status: GnssStatus) {
                                super.onSatelliteStatusChanged(status)
                                mSatelliteCount = status.satelliteCount
                            }
                        }
                        mLocationManager?.registerGnssStatusCallback(mGNSSStatusCallback!!)
                    }
                    mLocationManager?.requestLocationUpdates(provider, minTimeMS, minDistanceM, mLocationListener!!)
                    mLocationLossNotifyTimer = fixedRateTimer("mIdleNotifyTimer", initialDelay = 1000, period = mLocationLossCheckInterval) {
                        val lossTime = if (mLastLocationTime != null) {
                            Date().time - mLastLocationTime!!.time
                        } else {
                            //初次创建服务，但是二倍的检测周期之后，持续通知信号丢失
                            (Date().time - mServiceCreateAt.time) / 2
                        }
                        if (lossTime >= mLocationLossCheckInterval) {
                            reportLocationLoss(this@BackgroundLocationService, lossTime)
                        }
                    }
                    logger("服务[$TAG]已启动")
                    mIsServiceRunning = true
                    createNotification()
                } catch (ex: Exception) {
                    logger(ex)
                    if (ex.message != null && ex.message!!.isNotEmpty()) {
                        reportError(this, ex.message!!)
                    }
                    onDestroy()
                    return super.onStartCommand(intent, flags, startId)
                }
                return START_STICKY
            }
            else -> {
                logger(java.lang.Exception("接收到无效的指令动作：${intent?.action}"))
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun destroyLocationManager() {
        removeNotification()
        mLocationLossNotifyTimer?.cancel()
        mLocationLossNotifyTimer = null
        mLastLocationTime = null
        if (mLocationListener != null) {
            mLocationManager?.removeUpdates(mLocationListener!!)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (mGNSSStatusCallback != null) {
                mLocationManager?.unregisterGnssStatusCallback(mGNSSStatusCallback!!)
            }
        }
        mLocationListener = null
        mLocationManager = null
    }

    override fun onDestroy() {
        destroyLocationManager()
        mIsServiceRunning = false
        logger("服务[$TAG]已销毁")
    }

    private fun removeNotification() {
        stopForeground(true)
    }

    private fun createNotificationChannel() {
        var notificationChannel: NotificationChannel? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationChannel =
                NotificationChannel("LocationServiceChannel", "定位通知渠道", NotificationManager.IMPORTANCE_HIGH)
            val notificationManager = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            notificationManager.createNotificationChannel(notificationChannel)
        }
    }

    private fun createNotification() {
        createNotificationChannel()
        //使用兼容版本
        val builder = NotificationCompat.Builder(this)
        //设置状态栏的通知图标
        builder.setSmallIcon(R.drawable.icon_background_service_logo)
        //设置通知栏横条的图标
        builder.setLargeIcon(
            BitmapFactory.decodeResource(
                getResources(),
                R.drawable.icon_background_service_logo
            )
        )
        //禁止用户点击删除按钮删除
        builder.setAutoCancel(false)
        //禁止滑动删除
        builder.setOngoing(true)
        //右上角的时间显示
        builder.setShowWhen(true)
        //设置通知栏的标题内容
        builder.setContentTitle("定位服务运行中")
        builder.setChannelId("LocationServiceChannel")
        //创建通知
        val notification: Notification = builder.build()
        //设置为前台服务
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val mLocationLossCheckInterval = 3000L
        private var mSatelliteCount = 0
        private var mLocationLossNotifyTimer: Timer? = null
        private var mLastLocationTime: Date? = null

        @JvmStatic
        private fun reportLocation(context: Context, location: MyLocation) {
            try {
                logger("reportLocation [$location]")
                val intent = Intent(BROADCAST_RECEIVER_REPORT_LOCATION)
                intent.putExtra("location", location)
                context.sendBroadcast(intent)
                mLastLocationTime = Date()
            } catch (e: java.lang.Exception) {
                logger(e)
            }
        }

        @JvmStatic
        private fun reportProviderStatusChanged(context: Context, provider: String, status: Boolean) {
            try {
                logger("reportProviderStatusChanged [$provider]=${status}")
                val intent = Intent(BROADCAST_RECEIVER_REPORT_PROVIDER_STATUS)
                intent.putExtra("provider", provider)
                intent.putExtra("status", status)
                context.sendBroadcast(intent)
            } catch (e: java.lang.Exception) {
                logger(e)
            }
        }

        @JvmStatic
        private fun reportLocationLoss(context: Context, lossTime: Long) {
            try {
                logger("reportLocationLoss [lossTime]=${lossTime}")
                val intent = Intent(BROADCAST_RECEIVER_REPORT_LOCATION_LOSS)
                intent.putExtra("lossTime", lossTime)
                context.sendBroadcast(intent)
            } catch (e: java.lang.Exception) {
                logger(e)
            }
        }

        @JvmStatic
        private fun reportError(context: Context, errorMessage: String?) {
            try {
                logger("reportError [$errorMessage]")
                val intent = Intent(BROADCAST_RECEIVER_REPORT_ERROR)
                intent.putExtra("error", errorMessage)
                context.sendBroadcast(intent)
            } catch (e: java.lang.Exception) {
                logger(e)
            }
        }

        @JvmStatic
        fun startLocationService(
            context: Context,
            provider: String = "gps",
            minTimeMS: Long = 0,
            minDistanceM: Float = 0f
        ) {
            try {
                val intent = Intent(context, BackgroundLocationService::class.java).apply {
                    action = ACTION_LOCATION
                    putExtra(EXTRA_LOCATION_PROVIDER, provider)
                    putExtra(EXTRA_LOCATION_MIN_TIME_MS, minTimeMS)
                    putExtra(EXTRA_LOCATION_MIN_DISTANCE_M, minDistanceM)
                }
                context.startService(intent)
                logger("[${context}] startLocationService success")
            } catch (e: java.lang.Exception) {
                logger(e)
            }
        }

        @JvmStatic
        fun stopLocationService(context: Context) {
            try {
                context.stopService(Intent(context, BackgroundLocationService::class.java))
                logger("[${context}] stopLocationService success")
            } catch (e: java.lang.Exception) {
                logger(e)
            }
        }

        fun <T : BackgroundLocationServiceReceiver> registerLocationReceiver(
            context: Context,
            receiver: T
        ) {
            unregisterLocationReceiver(context, receiver)
            try {
                val filter = IntentFilter()
                filter.addAction(BROADCAST_RECEIVER_REPORT_LOCATION)
                filter.addAction(BROADCAST_RECEIVER_REPORT_ERROR)
                filter.addAction(BROADCAST_RECEIVER_REPORT_PROVIDER_STATUS)
                filter.addAction(BROADCAST_RECEIVER_REPORT_LOCATION_LOSS)
                context.registerReceiver(receiver, filter)
                logger("[${context}] registerLocationReceiver success")
            } catch (e: java.lang.Exception) {
                logger(e)
            }
        }

        fun <T : BackgroundLocationServiceReceiver> unregisterLocationReceiver(
            context: Context,
            receiver: T
        ) {
            try {
                context.unregisterReceiver(receiver)
                logger("[${context}] unregisterLocationReceiver success")
            } catch (e: java.lang.Exception) {
            }
        }
    }
}

