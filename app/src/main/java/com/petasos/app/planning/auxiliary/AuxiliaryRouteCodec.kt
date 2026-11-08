package com.petasos.app.planning.auxiliary

import com.petasos.app.generation.StrictJson
import com.petasos.app.location.BoundaryCoordinate
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Collections

/** Only the app's authenticated server is addressed. Provider credentials never enter this model. */
class AuxiliaryRouteRequest(
    val origin: BoundaryCoordinate,
    val destination: BoundaryCoordinate,
    waypoints: List<BoundaryCoordinate> = emptyList(),
    val option: String = "traoptimal",
) {
    val waypoints: List<BoundaryCoordinate> = Collections.unmodifiableList(waypoints.toList())
    init {
        require((listOf(origin, destination) + this.waypoints).all(::validRouteCoordinate))
        require(this.waypoints.size <= 5 && option in OPTIONS)
    }
    override fun toString() = "AuxiliaryRouteRequest([redacted])"
    companion object { private val OPTIONS = setOf("traoptimal", "trafast", "tracomfort", "traavoidtoll", "traavoidcaronly") }
}

class AuxiliaryRouteResponse(
    val geometry: AuxiliaryRouteGeometry,
    val totalDistanceMeters: Long,
    val timingUsable: Boolean,
) {
    override fun toString() = "AuxiliaryRouteResponse([redacted])"
}

internal class AuxiliaryRouteException(val code: String) : IllegalArgumentException(code)

/** Strict independent wire contract; decimal coordinates do not weaken generation's integer schema. */
internal object AuxiliaryRouteCodec {
    const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    const val MAX_REQUEST_BYTES = 16 * 1024
    private val fields = setOf("schemaVersion", "status", "provider", "service", "sourceMode",
        "coordinateReferenceSystem", "activePleosRouteVerified", "collectionEvidence", "path",
        "timingSegments", "totalDistanceMeters", "totalDurationMs", "timingCoverageComplete",
        "timingTotalsMatch", "timingUsable")

    fun encode(request: AuxiliaryRouteRequest): ByteArray {
        fun point(value: BoundaryCoordinate): String {
            if (!validRouteCoordinate(value)) invalid()
            return "[${value.longitude},${value.latitude}]"
        }
        // All interpolated strings are validated fixed enums or finite numeric coordinates.
        val document = "{\"origin\":${point(request.origin)},\"destination\":${point(request.destination)}," +
            "\"waypoints\":[${request.waypoints.joinToString(",", transform = ::point)}],\"option\":\"${request.option}\"}"
        return document.toByteArray(Charsets.UTF_8).also { if (it.size > MAX_REQUEST_BYTES) invalid() }
    }

    fun decode(bytes: ByteArray): AuxiliaryRouteResponse = try {
        val root = obj(StrictJson.parseDecimalDocument(bytes, MAX_RESPONSE_BYTES), fields)
        if (root["schemaVersion"] != "0.8" || root["status"] != "ok" || root["provider"] != "naver_maps" ||
            root["service"] != "directions5" || root["sourceMode"] != "AUXILIARY" ||
            root["coordinateReferenceSystem"] != "EPSG:4326" || root["activePleosRouteVerified"] != false ||
            root["collectionEvidence"] != false) invalid()
        val coverage = boolean(root["timingCoverageComplete"])
        val totals = boolean(root["timingTotalsMatch"])
        val usable = boolean(root["timingUsable"])
        val pathRaw = root["path"] as? List<*> ?: invalid()
        if (pathRaw.size !in 2..AuxiliaryRouteGeometry.MAX_POINTS) invalid()
        val path = pathRaw.map { raw ->
            val pair = raw as? List<*> ?: invalid()
            if (pair.size != 2) invalid()
            BoundaryCoordinate(coordinate(pair[0]), coordinate(pair[1])).also { if (!validRouteCoordinate(it)) invalid() }
        }
        val rawGuides = root["timingSegments"] as? List<*> ?: invalid()
        if (rawGuides.size > AuxiliaryRouteGeometry.MAX_GUIDES) invalid()
        val guides = rawGuides.map { raw ->
            val g = obj(raw, setOf("startPointIndex", "endPointIndex", "durationMs", "distanceMeters"))
            AuxiliaryGuideInterval(integer(g["startPointIndex"], path.lastIndex.toLong()).toInt(),
                integer(g["endPointIndex"], path.lastIndex.toLong()).toInt(),
                integer(g["durationMs"], AuxiliaryRouteGeometry.MAX_DURATION_MS),
                integer(g["distanceMeters"], AuxiliaryRouteGeometry.MAX_DISTANCE_METERS))
        }
        val duration = integer(root["totalDurationMs"], AuxiliaryRouteGeometry.MAX_DURATION_MS)
        val distance = integer(root["totalDistanceMeters"], AuxiliaryRouteGeometry.MAX_DISTANCE_METERS)
        // The server always returns a non-degenerate path and positive summary totals. Missing
        // tail timing is representable; a fabricated gap or restart between guide indexes is not.
        if (duration == 0L || distance == 0L || (1 until path.size).none { path[it - 1] != path[it] } ||
            guides.isNotEmpty() && (guides.first().startPointIndex != 0 ||
                guides.zipWithNext().any { (a, b) -> a.endPointIndex != b.startPointIndex })) invalid()
        val actualCoverage = guides.isNotEmpty() && guides.first().startPointIndex == 0 &&
            guides.last().endPointIndex == path.lastIndex && guides.all { it.endPointIndex >= it.startPointIndex } &&
            guides.zipWithNext().all { (a, b) -> a.endPointIndex == b.startPointIndex }
        val actualTotals = guides.sumOf { it.durationMs } == duration
        // False flags withhold forecasting; true flags cannot override independently checked geometry/time.
        if (coverage && !actualCoverage || totals && !actualTotals || usable && (!coverage || !totals)) invalid()
        if (usable && guides.any { g ->
                val moving = (g.startPointIndex until g.endPointIndex).any { path[it] != path[it + 1] }
                (g.durationMs == 0L && (moving || g.distanceMeters > 0)) || (g.durationMs > 0 && !moving)
            }) invalid()
        val key = fingerprint(path, guides, duration, distance)
        AuxiliaryRouteResponse(AuxiliaryRouteGeometry(key, path, guides, duration), distance, usable)
    } catch (error: AuxiliaryRouteException) { throw error }
      catch (_: Exception) { invalid() }

    /** Error messages are never surfaced or retained; status and fixed code must agree. */
    fun decodeFailure(status: Int, bytes: ByteArray): AuxiliaryRouteFailureKind = try {
        val root = obj(StrictJson.parse(bytes), setOf("schemaVersion", "requestId", "status", "code", "retryable", "retryAfterMs"))
        if (root["schemaVersion"] != "0.8" || root["requestId"] != null || root["status"] != "error") invalid()
        boolean(root["retryable"])
        root["retryAfterMs"]?.let { integer(it, Long.MAX_VALUE) }
        when (status to root["code"]) {
            401 to "UNAUTHORIZED", 403 to "FORBIDDEN" -> AuxiliaryRouteFailureKind.AUTHORIZATION
            400 to "INVALID_REQUEST" -> AuxiliaryRouteFailureKind.INVALID_REQUEST
            404 to "NOT_FOUND", 429 to "RATE_LIMITED", 503 to "AUXILIARY_ROUTE_UNAVAILABLE",
            503 to "UPSTREAM_UNAVAILABLE" -> AuxiliaryRouteFailureKind.UNAVAILABLE
            504 to "ROUTE_TIMEOUT" -> AuxiliaryRouteFailureKind.TIMEOUT
            502 to "INVALID_ROUTE_RESPONSE", 502 to "ROUTE_RESPONSE_TOO_LARGE" -> AuxiliaryRouteFailureKind.INVALID_RESPONSE
            else -> invalid()
        }
    } catch (error: AuxiliaryRouteException) { throw error }
      catch (_: Exception) { invalid() }

    private fun fingerprint(path: List<BoundaryCoordinate>, guides: List<AuxiliaryGuideInterval>, duration: Long, distance: Long): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUTF("petasos-naver-auxiliary-v1")
            data.writeInt(path.size)
            path.forEach { data.writeDouble(it.longitude); data.writeDouble(it.latitude) }
            data.writeInt(guides.size)
            guides.forEach { data.writeInt(it.startPointIndex); data.writeInt(it.endPointIndex); data.writeLong(it.durationMs); data.writeLong(it.distanceMeters) }
            data.writeLong(duration); data.writeLong(distance)
        }
        return MessageDigest.getInstance("SHA-256").digest(output.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun obj(value: Any?, expected: Set<String>): Map<*, *> = (value as? Map<*, *>)?.takeIf { it.keys == expected } ?: invalid()
    private fun boolean(value: Any?): Boolean = value as? Boolean ?: invalid()
    private fun integer(value: Any?, maximum: Long): Long = (value as? Long)?.takeIf { it in 0..maximum } ?: invalid()
    private fun coordinate(value: Any?): Double = when (value) {
        is Long -> value.toDouble()
        is BigDecimal -> value.toDouble()
        else -> invalid()
    }.takeIf { it.isFinite() } ?: invalid()
    private fun invalid(): Nothing = throw AuxiliaryRouteException("INVALID_AUXILIARY_ROUTE")
}

private fun validRouteCoordinate(value: BoundaryCoordinate): Boolean =
    value.longitude.isFinite() && value.latitude.isFinite() && value.longitude in 120.0..135.0 && value.latitude in 30.0..45.0
