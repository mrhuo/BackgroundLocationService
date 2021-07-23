package com.mrhuo.backgroundlocationservice

import java.util.*

object PolyUtil {
    /**
     * Returns tan(latitude-at-lng3) on the great circle (lat1, lng1) to (lat2, lng2). lng1==0.
     * See http://williams.best.vwh.net/avform.htm .
     */
    private fun tanLatGC(lat1: Double, lat2: Double, lng2: Double, lng3: Double): Double {
        return (Math.tan(lat1) * Math.sin(lng2 - lng3) + Math.tan(lat2) * Math.sin(lng3)) / Math.sin(
            lng2
        )
    }

    /**
     * Returns mercator(latitude-at-lng3) on the Rhumb line (lat1, lng1) to (lat2, lng2). lng1==0.
     */
    private fun mercatorLatRhumb(lat1: Double, lat2: Double, lng2: Double, lng3: Double): Double {
        return (MathUtil.mercator(lat1) * (lng2 - lng3) + MathUtil.mercator(lat2) * lng3) / lng2
    }

    /**
     * Computes whether the vertical segment (lat3, lng3) to South Pole intersects the segment
     * (lat1, lng1) to (lat2, lng2).
     * Longitudes are offset by -lng1; the implicit lng1 becomes 0.
     */
    private fun intersects(
        lat1: Double, lat2: Double, lng2: Double,
        lat3: Double, lng3: Double, geodesic: Boolean
    ): Boolean {
        // Both ends on the same side of lng3.
        if (lng3 >= 0 && lng3 >= lng2 || lng3 < 0 && lng3 < lng2) {
            return false
        }
        // Point is South Pole.
        if (lat3 <= -Math.PI / 2) {
            return false
        }
        // Any segment end is a pole.
        if (lat1 <= -Math.PI / 2 || lat2 <= -Math.PI / 2 || lat1 >= Math.PI / 2 || lat2 >= Math.PI / 2) {
            return false
        }
        if (lng2 <= -Math.PI) {
            return false
        }
        val linearLat = (lat1 * (lng2 - lng3) + lat2 * lng3) / lng2
        // Northern hemisphere and point under lat-lng line.
        if (lat1 >= 0 && lat2 >= 0 && lat3 < linearLat) {
            return false
        }
        // Southern hemisphere and point above lat-lng line.
        if (lat1 <= 0 && lat2 <= 0 && lat3 >= linearLat) {
            return true
        }
        // North Pole.
        if (lat3 >= Math.PI / 2) {
            return true
        }
        // Compare lat3 with latitude on the GC/Rhumb segment corresponding to lng3.
        // Compare through a strictly-increasing function (tan() or mercator()) as convenient.
        return if (geodesic) Math.tan(lat3) >= tanLatGC(
            lat1,
            lat2,
            lng2,
            lng3
        ) else MathUtil.mercator(lat3) >= mercatorLatRhumb(lat1, lat2, lng2, lng3)
    }

    fun containsLocation(point: MyLocation, polygon: List<MyLocation>, geodesic: Boolean): Boolean {
        return containsLocation(point.latitude, point.longitude, polygon, geodesic)
    }

    /**
     * Computes whether the given point lies inside the specified polygon.
     * The polygon is always considered closed, regardless of whether the last point equals
     * the first or not.
     * Inside is defined as not containing the South Pole -- the South Pole is always outside.
     * The polygon is formed of great circle segments if geodesic is true, and of rhumb
     * (loxodromic) segments otherwise.
     */
    fun containsLocation(
        latitude: Double,
        longitude: Double,
        polygon: List<MyLocation>,
        geodesic: Boolean
    ): Boolean {
        val size = polygon.size
        if (size == 0) {
            return false
        }
        val lat3 = Math.toRadians(latitude)
        val lng3 = Math.toRadians(longitude)
        val ln = polygon[size - 1]
        var lat1 = Math.toRadians(ln.latitude)
        var lng1 = Math.toRadians(ln.longitude)
        var nIntersect = 0
        for (it in polygon) {
            val dLng3 = MathUtil.wrap(lng3 - lng1, -Math.PI, Math.PI)
            // Special case: point equal to vertex is inside.
            if (lat3 == lat1 && dLng3 == 0.0) {
                return true
            }
            val lat2 = Math.toRadians(it.latitude)
            val lng2 = Math.toRadians(it.longitude)
            // Offset longitudes by -lng1.
            if (intersects(
                    lat1,
                    lat2,
                    MathUtil.wrap(lng2 - lng1, -Math.PI, Math.PI),
                    lat3,
                    dLng3,
                    geodesic
                )
            ) {
                ++nIntersect
            }
            lat1 = lat2
            lng1 = lng2
        }
        return nIntersect and 1 != 0
    }

    const val DEFAULT_TOLERANCE = 0.1 // meters.

    /**
     * Computes whether the given point lies on or near the edge of a polygon, within a specified
     * tolerance in meters. The polygon edge is composed of great circle segments if geodesic
     * is true, and of Rhumb segments otherwise. The polygon edge is implicitly closed -- the
     * closing segment between the first point and the last point is included.
     */
    fun isLocationOnEdge(
        point: MyLocation, polygon: List<MyLocation>, geodesic: Boolean,
        tolerance: Double
    ): Boolean {
        return isLocationOnEdgeOrPath(point, polygon, true, geodesic, tolerance)
    }

    /**
     * Same as [.isLocationOnEdge]
     * with a default tolerance of 0.1 meters.
     */
    fun isLocationOnEdge(point: MyLocation, polygon: List<MyLocation>, geodesic: Boolean): Boolean {
        return isLocationOnEdge(point, polygon, geodesic, DEFAULT_TOLERANCE)
    }

    /**
     * Computes whether the given point lies on or near a polyline, within a specified
     * tolerance in meters. The polyline is composed of great circle segments if geodesic
     * is true, and of Rhumb segments otherwise. The polyline is not closed -- the closing
     * segment between the first point and the last point is not included.
     */
    fun isLocationOnPath(
        point: MyLocation, polyline: List<MyLocation>,
        geodesic: Boolean, tolerance: Double
    ): Boolean {
        return isLocationOnEdgeOrPath(point, polyline, false, geodesic, tolerance)
    }

    /**
     * Same as [.isLocationOnPath]
     *
     *
     * with a default tolerance of 0.1 meters.
     */
    fun isLocationOnPath(
        point: MyLocation, polyline: List<MyLocation>,
        geodesic: Boolean
    ): Boolean {
        return isLocationOnPath(point, polyline, geodesic, DEFAULT_TOLERANCE)
    }

    private fun isLocationOnEdgeOrPath(
        point: MyLocation, poly: List<MyLocation>, closed: Boolean,
        geodesic: Boolean, toleranceEarth: Double
    ): Boolean {
        val idx = locationIndexOnEdgeOrPath(point, poly, closed, geodesic, toleranceEarth)
        return idx >= 0
    }

    /**
     * Computes whether (and where) a given point lies on or near a polyline, within a specified tolerance.
     * The polyline is not closed -- the closing segment between the first point and the last point is not included.
     *
     * @param point     our needle
     * @param poly      our haystack
     * @param geodesic  the polyline is composed of great circle segments if geodesic
     * is true, and of Rhumb segments otherwise
     * @param tolerance tolerance (in meters)
     * @return -1 if point does not lie on or near the polyline.
     * 0 if point is between poly[0] and poly[1] (inclusive),
     * 1 if between poly[1] and poly[2],
     * ...,
     * poly.size()-2 if between poly[poly.size() - 2] and poly[poly.size() - 1]
     */
    fun locationIndexOnPath(
        point: MyLocation, poly: List<MyLocation>,
        geodesic: Boolean, tolerance: Double
    ): Int {
        return locationIndexOnEdgeOrPath(point, poly, false, geodesic, tolerance)
    }

    /**
     * Same as [.locationIndexOnPath]
     *
     *
     * with a default tolerance of 0.1 meters.
     */
    fun locationIndexOnPath(
        point: MyLocation, polyline: List<MyLocation>,
        geodesic: Boolean
    ): Int {
        return locationIndexOnPath(point, polyline, geodesic, DEFAULT_TOLERANCE)
    }

    /**
     * Computes whether (and where) a given point lies on or near a polyline, within a specified tolerance.
     * If closed, the closing segment between the last and first points of the polyline is not considered.
     *
     * @param point          our needle
     * @param poly           our haystack
     * @param closed         whether the polyline should be considered closed by a segment connecting the last point back to the first one
     * @param geodesic       the polyline is composed of great circle segments if geodesic
     * is true, and of Rhumb segments otherwise
     * @param toleranceEarth tolerance (in meters)
     * @return -1 if point does not lie on or near the polyline.
     * 0 if point is between poly[0] and poly[1] (inclusive),
     * 1 if between poly[1] and poly[2],
     * ...,
     * poly.size()-2 if between poly[poly.size() - 2] and poly[poly.size() - 1]
     */
    fun locationIndexOnEdgeOrPath(
        point: MyLocation, poly: List<MyLocation>, closed: Boolean,
        geodesic: Boolean, toleranceEarth: Double
    ): Int {
        val size = poly.size
        if (size == 0) {
            return -1
        }
        val tolerance = toleranceEarth / MathUtil.EARTH_RADIUS
        val havTolerance = MathUtil.hav(tolerance)
        val lat3 = Math.toRadians(point.latitude)
        val lng3 = Math.toRadians(point.longitude)
        val ln = poly[if (closed) size - 1 else 0]
        var lat1 = Math.toRadians(ln.latitude)
        var lng1 = Math.toRadians(ln.longitude)
        var idx = 0
        if (geodesic) {
            for (it in poly) {
                val lat2 = Math.toRadians(it.latitude)
                val lng2 = Math.toRadians(it.longitude)
                if (isOnSegmentGC(lat1, lng1, lat2, lng2, lat3, lng3, havTolerance)) {
                    return Math.max(0, idx - 1)
                }
                lat1 = lat2
                lng1 = lng2
                idx++
            }
        } else {
            // We project the points to mercator space, where the Rhumb segment is a straight line,
            // and compute the geodesic distance between point3 and the closest point on the
            // segment. This method is an approximation, because it uses "closest" in mercator
            // space which is not "closest" on the sphere -- but the error is small because
            // "tolerance" is small.
            val minAcceptable = lat3 - tolerance
            val maxAcceptable = lat3 + tolerance
            var y1 = MathUtil.mercator(lat1)
            val y3 = MathUtil.mercator(lat3)
            val xTry = DoubleArray(3)
            for (it in poly) {
                val lat2 = Math.toRadians(it.latitude)
                val y2 = MathUtil.mercator(lat2)
                val lng2 = Math.toRadians(it.longitude)
                if (Math.max(lat1, lat2) >= minAcceptable && Math.min(
                        lat1,
                        lat2
                    ) <= maxAcceptable
                ) {
                    // We offset longitudes by -lng1; the implicit x1 is 0.
                    val x2 = MathUtil.wrap(lng2 - lng1, -Math.PI, Math.PI)
                    val x3Base = MathUtil.wrap(lng3 - lng1, -Math.PI, Math.PI)
                    xTry[0] = x3Base
                    // Also explore wrapping of x3Base around the world in both directions.
                    xTry[1] = x3Base + 2 * Math.PI
                    xTry[2] = x3Base - 2 * Math.PI
                    for (x3 in xTry) {
                        val dy = y2 - y1
                        val len2 = x2 * x2 + dy * dy
                        val t: Double = if (len2 <= 0) 0.0 else MathUtil.clamp(
                            (x3 * x2 + (y3 - y1) * dy) / len2,
                            0.0,
                            1.0
                        )
                        val xClosest = t * x2
                        val yClosest = y1 + t * dy
                        val latClosest = MathUtil.inverseMercator(yClosest)
                        val havDist = MathUtil.havDistance(lat3, latClosest, x3 - xClosest)
                        if (havDist < havTolerance) {
                            return Math.max(0, idx - 1)
                        }
                    }
                }
                lat1 = lat2
                lng1 = lng2
                y1 = y2
                idx++
            }
        }
        return -1
    }

    /**
     * Returns sin(initial bearing from (lat1,lng1) to (lat3,lng3) minus initial bearing
     * from (lat1, lng1) to (lat2,lng2)).
     */
    private fun sinDeltaBearing(
        lat1: Double, lng1: Double, lat2: Double, lng2: Double,
        lat3: Double, lng3: Double
    ): Double {
        val sinLat1 = Math.sin(lat1)
        val cosLat2 = Math.cos(lat2)
        val cosLat3 = Math.cos(lat3)
        val lat31 = lat3 - lat1
        val lng31 = lng3 - lng1
        val lat21 = lat2 - lat1
        val lng21 = lng2 - lng1
        val a = Math.sin(lng31) * cosLat3
        val c = Math.sin(lng21) * cosLat2
        val b = Math.sin(lat31) + 2 * sinLat1 * cosLat3 * MathUtil.hav(lng31)
        val d = Math.sin(lat21) + 2 * sinLat1 * cosLat2 * MathUtil.hav(lng21)
        val denom = (a * a + b * b) * (c * c + d * d)
        return if (denom <= 0) 1.0 else (a * d - b * c) / Math.sqrt(denom)
    }

    private fun isOnSegmentGC(
        lat1: Double, lng1: Double, lat2: Double, lng2: Double,
        lat3: Double, lng3: Double, havTolerance: Double
    ): Boolean {
        val havDist13 = MathUtil.havDistance(lat1, lat3, lng1 - lng3)
        if (havDist13 <= havTolerance) {
            return true
        }
        val havDist23 = MathUtil.havDistance(lat2, lat3, lng2 - lng3)
        if (havDist23 <= havTolerance) {
            return true
        }
        val sinBearing = sinDeltaBearing(lat1, lng1, lat2, lng2, lat3, lng3)
        val sinDist13 = MathUtil.sinFromHav(havDist13)
        val havCrossTrack = MathUtil.havFromSin(sinDist13 * sinBearing)
        if (havCrossTrack > havTolerance) {
            return false
        }
        val havDist12 = MathUtil.havDistance(lat1, lat2, lng1 - lng2)
        val term = havDist12 + havCrossTrack * (1 - 2 * havDist12)
        if (havDist13 > term || havDist23 > term) {
            return false
        }
        if (havDist12 < 0.74) {
            return true
        }
        val cosCrossTrack = 1 - 2 * havCrossTrack
        val havAlongTrack13 = (havDist13 - havCrossTrack) / cosCrossTrack
        val havAlongTrack23 = (havDist23 - havCrossTrack) / cosCrossTrack
        val sinSumAlongTrack = MathUtil.sinSumFromHav(havAlongTrack13, havAlongTrack23)
        return sinSumAlongTrack > 0 // Compare with half-circle == PI using sign of sin().
    }

    /**
     * Simplifies the given poly (polyline or polygon) using the Douglas-Peucker decimation
     * algorithm.  Increasing the tolerance will result in fewer points in the simplified polyline
     * or polygon.
     *
     *
     * When the providing a polygon as input, the first and last point of the list MUST have the
     * same latitude and longitude (i.e., the polygon must be closed).  If the input polygon is not
     * closed, the resulting polygon may not be fully simplified.
     *
     *
     * The time complexity of Douglas-Peucker is O(n^2), so take care that you do not call this
     * algorithm too frequently in your code.
     *
     * @param poly      polyline or polygon to be simplified.  Polygon should be closed (i.e.,
     * first and last points should have the same latitude and longitude).
     * @param tolerance in meters.  Increasing the tolerance will result in fewer points in the
     * simplified poly.
     * @return a simplified poly produced by the Douglas-Peucker algorithm
     */
    fun simplify(poly: MutableList<MyLocation>, tolerance: Double): List<MyLocation?> {
        val n = poly.size
        require(n >= 1) { "Polyline must have at least 1 point" }
        require(tolerance > 0) { "Tolerance must be greater than zero" }
        val closedPolygon = isClosedPolygon(poly)
        var lastPoint: MyLocation? = null

        // Check if the provided poly is a closed polygon
        if (closedPolygon) {
            // Add a small offset to the last point for Douglas-Peucker on polygons (see #201)
            val OFFSET = 0.00000000001
            lastPoint = poly[poly.size - 1]
            // MyLocation.latitude and .longitude are immutable, so replace the last point
            poly.removeAt(poly.size - 1)
            poly.add(MyLocation(lastPoint!!.latitude + OFFSET, lastPoint.longitude + OFFSET))
        }
        var idx: Int
        var maxIdx = 0
        val stack = Stack<IntArray>()
        val dists = DoubleArray(n)
        dists[0] = 1.0
        dists[n - 1] = 1.0
        var maxDist: Double
        var dist = 0.0
        var current: IntArray
        if (n > 2) {
            val stackVal = intArrayOf(0, n - 1)
            stack.push(stackVal)
            while (stack.size > 0) {
                current = stack.pop()
                maxDist = 0.0
                idx = current[0] + 1
                while (idx < current[1]) {
                    dist = distanceToLine(
                        poly[idx], poly[current[0]],
                        poly[current[1]]
                    )
                    if (dist > maxDist) {
                        maxDist = dist
                        maxIdx = idx
                    }
                    ++idx
                }
                if (maxDist > tolerance) {
                    dists[maxIdx] = maxDist
                    val stackValCurMax = intArrayOf(current[0], maxIdx)
                    stack.push(stackValCurMax)
                    val stackValMaxCur = intArrayOf(maxIdx, current[1])
                    stack.push(stackValMaxCur)
                }
            }
        }
        if (closedPolygon) {
            // Replace last point w/ offset with the original last point to re-close the polygon
            poly.removeAt(poly.size - 1)
            if (lastPoint!= null) {
                poly.add(lastPoint)
            }
        }

        // Generate the simplified line
        idx = 0
        val simplifiedLine = ArrayList<MyLocation?>()
        for (l in poly) {
            if (dists[idx] != 0.0) {
                simplifiedLine.add(l)
            }
            idx++
        }
        return simplifiedLine
    }

    /**
     * Returns true if the provided list of points is a closed polygon (i.e., the first and last
     * points are the same), and false if it is not
     *
     * @param poly polyline or polygon
     * @return true if the provided list of points is a closed polygon (i.e., the first and last
     * points are the same), and false if it is not
     */
    fun isClosedPolygon(poly: List<MyLocation?>): Boolean {
        val firstPoint = poly[0]
        val lastPoint = poly[poly.size - 1]
        return firstPoint!!.equals(lastPoint)
    }

    /**
     * Computes the distance on the sphere between the point p and the line segment start to end.
     *
     * @param p     the point to be measured
     * @param start the beginning of the line segment
     * @param end   the end of the line segment
     * @return the distance in meters (assuming spherical earth)
     */
    fun distanceToLine(p: MyLocation, start: MyLocation, end: MyLocation): Double {
        if (start.equals(end)) {
            return SphericalUtil.computeDistanceBetween(end, p)
        }
        val s0lat = Math.toRadians(p.latitude)
        val s0lng = Math.toRadians(p.longitude)
        val s1lat = Math.toRadians(start.latitude)
        val s1lng = Math.toRadians(start.longitude)
        val s2lat = Math.toRadians(end.latitude)
        val s2lng = Math.toRadians(end.longitude)
        val s2s1lat = s2lat - s1lat
        val s2s1lng = s2lng - s1lng
        val u = (((s0lat - s1lat) * s2s1lat + (s0lng - s1lng) * s2s1lng)
                / (s2s1lat * s2s1lat + s2s1lng * s2s1lng))
        if (u <= 0) {
            return SphericalUtil.computeDistanceBetween(p, start)
        }
        if (u >= 1) {
            return SphericalUtil.computeDistanceBetween(p, end)
        }
        val su = MyLocation(
            start.latitude + u * (end.latitude - start.latitude),
            start.longitude + u * (end.longitude - start.longitude)
        )
        return SphericalUtil.computeDistanceBetween(p, su)
    }

    /**
     * Decodes an encoded path string into a sequence of LatLngs.
     */
    fun decode(encodedPath: String): List<MyLocation> {
        val len = encodedPath.length

        // For speed we preallocate to an upper bound on the final length, then
        // truncate the array before returning.
        val path: MutableList<MyLocation> = ArrayList()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < len) {
            var result = 1
            var shift = 0
            var b: Int
            do {
                b = encodedPath[index++].toInt() - 63 - 1
                result += b shl shift
                shift += 5
            } while (b >= 0x1f)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            result = 1
            shift = 0
            do {
                b = encodedPath[index++].toInt() - 63 - 1
                result += b shl shift
                shift += 5
            } while (b >= 0x1f)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            path.add(MyLocation(lat * 1e-5, lng * 1e-5))
        }
        return path
    }

    /**
     * Encodes a sequence of LatLngs into an encoded path string.
     */
    fun encode(path: List<MyLocation>): String {
        var lastLat: Long = 0
        var lastLng: Long = 0
        val result = StringBuffer()
        for (it in path) {
            val lat = Math.round(it.latitude * 1e5)
            val lng = Math.round(it.longitude * 1e5)
            val dLat = lat - lastLat
            val dLng = lng - lastLng
            encode(dLat, result)
            encode(dLng, result)
            lastLat = lat
            lastLng = lng
        }
        return result.toString()
    }

    private fun encode(v: Long, result: StringBuffer) {
        var v = v
        v = if (v < 0) (v shl 1).inv() else v shl 1
        while (v >= 0x20) {
            result.append(Character.toChars(((0x20 or (v and 0x1f).toInt()) + 63)))
            v = v shr 5
        }
        result.append(Character.toChars((v + 63).toInt()))
    }
}
