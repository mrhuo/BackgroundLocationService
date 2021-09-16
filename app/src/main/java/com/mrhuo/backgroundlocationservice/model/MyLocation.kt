package com.mrhuo.backgroundlocationservice.model

import android.location.Location
import java.io.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

data class MyLocation(
    var provider: String,
    override var latitude: Double,
    override var longitude: Double,
    var altitude: Double,
    var accuracy: Float,
    var speed: Float,
    var bearing: Float,
    var time: Long,
    var satellite: Int = 0
): LatLng(latitude, longitude), Serializable {

    constructor(location: Location, satellite: Int = 0): this(
        location.provider,
        location.latitude.roundFormat(6),
        location.longitude.roundFormat(6),
        location.altitude.roundFormat(6),
        location.accuracy,
        location.speed,
        location.bearing,
        location.time,
        satellite
    )
    constructor(latitude: Double, longitude: Double): this(
        "gps",
        latitude,
        longitude,
        0.0,
        0f,
        0f,
        0f,
        Date().time,
        0
    )
    constructor() : this(0.0, 0.0)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MyLocation

        if (latitude != other.latitude) return false
        if (longitude != other.longitude) return false

        return true
    }

    override fun hashCode(): Int {
        var result = latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        return result
    }

    override fun toString(): String {
        return "MyLocation(" +
                "provider='$provider', " +
                "latitude=$latitude, " +
                "longitude=$longitude, " +
                "altitude=$altitude, " +
                "accuracy=$accuracy, " +
                "speed=$speed, " +
                "bearing=$bearing, " +
                "time=$time, " +
                "satellite=$satellite" +
                ")"
    }

    companion object {
        private fun Double?.roundFormat(scale: Int): Double {
            if (this == null) {
                return 0.0
            }
            return BigDecimal(this).setScale(scale, RoundingMode.HALF_UP).toDouble()
        }
    }

}