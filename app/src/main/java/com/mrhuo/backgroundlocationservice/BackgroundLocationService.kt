package com.mrhuo.backgroundlocationservice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.*
import kotlin.concurrent.fixedRateTimer


internal const val TAG = "BKLocationService"
internal const val ACTION_LOCATION = "${TAG}.action.LOCATION"
internal const val EXTRA_LOCATION_PROVIDER = "${TAG}.extra.LOCATION_PROVIDER"
internal const val EXTRA_LOCATION_NOTIFICATION_ID = "${TAG}.extra.LOCATION_NOTIFICATION_ID"
internal const val EXTRA_LOCATION_NOTIFICATION_TITLE = "${TAG}.extra.LOCATION_NOTIFICATION_TITLE"
internal const val EXTRA_LOCATION_MIN_TIME_MS = "${TAG}.extra.MIN_TIME_MS"
internal const val EXTRA_LOCATION_MIN_DISTANCE_M = "${TAG}.extra.DISTANCE_M"
internal const val ACTION_REPORT_LOCATION = "${TAG}.action.REPORT_LOCATION"
internal const val ACTION_REPORT_ERROR = "${TAG}.action.REPORT_ERROR"
internal const val ACTION_REPORT_PROVIDER_STATUS = "${TAG}.action.REPORT_PROVIDER_STATUS"
internal const val ACTION_REPORT_LOCATION_LOSS = "${TAG}.action.REPORT_LOCATION_LOSS"
internal const val NOTIFICATION_CHANNEL_ID = "LocationServiceChannel"

@SuppressLint("MissingPermission")
class BackgroundLocationService : Service() {
    private var mLocationManager: LocationManager? = null
    private var mLocationListener: LocationListener? = null
    private var mGNSSStatusCallback: GnssStatus.Callback? = null
    private var mIsServiceRunning = false
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
                val myLocation = MyLocation(location)
                myLocation.satellite = mSatelliteCount
                logger("onLocationChanged [${myLocation}]")
                reportLocation(this@BackgroundLocationService, myLocation)
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
                val locationNotificationId =
                    intent.getIntExtra(EXTRA_LOCATION_NOTIFICATION_ID, 0)
                val locationNotificationTitle =
                    intent.getStringExtra(EXTRA_LOCATION_NOTIFICATION_TITLE)
                logger(
                    "onStartCommand [" +
                            "provider=$provider, " +
                            "minTimeMS=$minTimeMS, " +
                            "minDistanceM=$minDistanceM, " +
                            "notificationId=$locationNotificationId, " +
                            "notificationTitle=$locationNotificationTitle" +
                            "]"
                )
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
                    mLocationManager?.requestLocationUpdates(
                        provider,
                        minTimeMS,
                        minDistanceM,
                        mLocationListener!!
                    )
                    mLocationLossNotifyTimer = fixedRateTimer(
                        "mIdleNotifyTimer",
                        initialDelay = 1000,
                        period = mLocationLossCheckInterval
                    ) {
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
                    createNotification(locationNotificationId, locationNotificationTitle)
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
                logger(java.lang.Exception("接收到无效的动作：${intent?.action}"))
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
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "定位通知渠道",
                    NotificationManager.IMPORTANCE_HIGH
                )
            val notificationManager =
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            notificationManager.createNotificationChannel(notificationChannel)
        }
    }

    private fun createNotification(notificationId: Int, title: String?) {
        if (notificationId < 1) {
            return
        }
        var _title = title
        if(_title == null || _title.isEmpty()) {
            _title = "定位服务运行中"
        }
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
        builder.setContentTitle(_title)
        builder.setChannelId(NOTIFICATION_CHANNEL_ID)
        //创建通知
        val notification: Notification = builder.build()
        //设置为前台服务
        startForeground(notificationId, notification)
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
                val intent = Intent(ACTION_REPORT_LOCATION)
                intent.putExtra("location", location)
                context.sendBroadcast(intent)
                mLastLocationTime = Date()
            } catch (e: java.lang.Exception) {
                logger(e)
            }
        }

        @JvmStatic
        private fun reportProviderStatusChanged(
            context: Context,
            provider: String,
            status: Boolean
        ) {
            try {
                logger("reportProviderStatusChanged [$provider]=${status}")
                val intent = Intent(ACTION_REPORT_PROVIDER_STATUS)
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
                val intent = Intent(ACTION_REPORT_LOCATION_LOSS)
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
                val intent = Intent(ACTION_REPORT_ERROR)
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
            minDistanceM: Float = 0f,
            notificationId: Int = 0x0220,
            notificationTitle: String? = null
        ) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                reportError(context, "请先申请定位权限【${Manifest.permission.ACCESS_FINE_LOCATION}, ${Manifest.permission.ACCESS_COARSE_LOCATION}】")
                return
            }
            try {
                val intent = Intent(context, BackgroundLocationService::class.java).apply {
                    action = ACTION_LOCATION
                    putExtra(EXTRA_LOCATION_PROVIDER, provider)
                    putExtra(EXTRA_LOCATION_MIN_TIME_MS, minTimeMS)
                    putExtra(EXTRA_LOCATION_MIN_DISTANCE_M, minDistanceM)
                    putExtra(EXTRA_LOCATION_NOTIFICATION_ID, notificationId)
                    putExtra(EXTRA_LOCATION_NOTIFICATION_TITLE, notificationTitle)
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
                filter.addAction(ACTION_REPORT_LOCATION)
                filter.addAction(ACTION_REPORT_ERROR)
                filter.addAction(ACTION_REPORT_PROVIDER_STATUS)
                filter.addAction(ACTION_REPORT_LOCATION_LOSS)
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

