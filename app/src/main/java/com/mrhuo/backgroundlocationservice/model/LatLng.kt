package com.mrhuo.backgroundlocationservice.model

import com.mrhuo.backgroundlocationservice.util.SphericalUtil
import java.io.Serializable

open class LatLng(open var latitude: Double, open var longitude: Double): Serializable {
    override fun toString(): String {
        return "LatLng(latitude=$latitude, longitude=$longitude)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LatLng) return false

        if (latitude != other.latitude) return false
        if (longitude != other.longitude) return false

        return true
    }

    override fun hashCode(): Int {
        var result = latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        return result
    }
}

fun LatLng.isValid(): Boolean {
    return this.latitude != 0.0 && this.longitude != 0.0
}

fun LatLng.distanceTo(latLng: LatLng): Float {
    return SphericalUtil.computeDistanceBetween(this, latLng).toFloat()
}

fun LatLng.distanceTo(lat: Double, lng: Double): Float {
    return this.distanceTo(LatLng(lat, lng))
}
