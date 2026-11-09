package com.petasos.app.location

import com.petasos.app.BuildConfig
import com.petasos.app.capability.location.AndroidLocationMetadata
import com.petasos.app.capability.location.LocationSample
import kotlin.math.ceil

/**
 * Public Android Location semantics: epoch milliseconds, same-boot elapsed nanoseconds, and
 * hasAccuracy-backed horizontal radius in meters at 68% confidence. This is not Pleos confidence.
 * The accuracy cap is explicit implementation tuning; it does not approve any Region/LandmarkPolicy.
 */
class AndroidLocationMeaningAdapter(private val maximumAccuracyMeters: Double) : LocationMeaningAdapter {
    init { require(maximumAccuracyMeters.isFinite() && maximumAccuracyMeters > 0) }

    override fun interpret(sample: LocationSample): VerifiedLocationMeaning? {
        val metadata = sample.androidMetadata ?: return null
        val accuracy = metadata.horizontalAccuracyMeters ?: return null
        if (!accuracy.isFinite() || accuracy < 0 || sample.rawTimestampMillis < 0 ||
            sample.receivedAtElapsedRealtimeMillis < 0 ||
            metadata.receivedAtElapsedRealtimeNanos / NANOS_PER_MILLI != sample.receivedAtElapsedRealtimeMillis) return null
        val age = measurementAgeMs(metadata) ?: return null
        return VerifiedLocationMeaning(VERSION, "epoch_ms", sample.rawTimestampMillis, accuracy,
            maximumAccuracyMeters, measurementAgeAtReceiptMs = age)
    }

    companion object {
        const val VERSION = "android-location-v1"
        const val PROVIDER_PREFIX = "android-location-manager:"
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Fixed measurement-millisecond origin includes any supplied elapsed timestamp uncertainty. */
        fun measurementAgeMs(metadata: AndroidLocationMetadata): Long? {
            val provider = metadata.provider ?: return null
            if (provider.isBlank() || provider.length > 200 || (metadata.isMock && !BuildConfig.DEMO) ||
                metadata.elapsedRealtimeNanos < 0 || metadata.receivedAtElapsedRealtimeNanos < metadata.elapsedRealtimeNanos) return null
            val uncertainty = metadata.elapsedRealtimeUncertaintyNanos
            if (uncertainty != null && (!uncertainty.isFinite() || uncertainty < 0 || uncertainty > Long.MAX_VALUE.toDouble())) return null
            // Floor the measurement once in the same millisecond clock as the receipt/deadline.
            // Rounding their difference instead could renew a cached fix's deadline by 1 ms.
            val ageMillis = metadata.receivedAtElapsedRealtimeNanos / NANOS_PER_MILLI -
                metadata.elapsedRealtimeNanos / NANOS_PER_MILLI
            val uncertaintyMillis = if (uncertainty == null) 0 else ceil(uncertainty / NANOS_PER_MILLI).toLong()
            if (ageMillis > Long.MAX_VALUE - uncertaintyMillis) return null
            return ageMillis + uncertaintyMillis
        }

        /** Source identity survives doubtful/mock samples without granting them valid quality. */
        fun provider(metadata: AndroidLocationMetadata): String? = metadata.provider
            ?.takeIf { it.isNotBlank() && it.length <= 200 }?.let { PROVIDER_PREFIX + it }

        /** Readback uses the original monotonic evidence, not a wall-clock fallback or naked age. */
        fun restoredAgeMs(confidence: Map<*, *>, receivedElapsedMs: Long): Long? {
            if (confidence["adapterVersion"] != VERSION) return null
            val declared = confidence["measurementAgeAtReceiptMs"] as? Long ?: return null
            if (declared < 0) return null
            val raw = confidence["androidLocation"] as? Map<*, *> ?: return null
            val provider = raw["provider"] as? String ?: return null
            val measured = raw["elapsedRealtimeNanos"] as? Long ?: return null
            val received = raw["receivedAtElapsedRealtimeNanos"] as? Long ?: return null
            val mock = raw["isMock"] as? Boolean ?: return null
            val accuracy = (raw["horizontalAccuracyMeters"] as? String)?.toDoubleOrNull() ?: return null
            if (!accuracy.isFinite() || accuracy < 0 || received / NANOS_PER_MILLI != receivedElapsedMs) return null
            val uncertainty = when (val value = raw["elapsedRealtimeUncertaintyNanos"]) {
                null -> null
                is String -> value.toDoubleOrNull() ?: return null
                else -> return null
            }
            val calculated = measurementAgeMs(AndroidLocationMetadata(provider, measured, received, accuracy, mock, uncertainty)) ?: return null
            return declared.takeIf { it == calculated }
        }
    }
}
