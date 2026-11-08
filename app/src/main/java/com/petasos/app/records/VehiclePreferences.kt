package com.petasos.app.records

/** A missing vehicle preference is not location consent; null revision/time means no stored row. */
data class VehicleRecordingSettings(
    val locationEnabled: Boolean,
    val version: Long?,
    val updatedAt: Long?,
) {
    init {
        require((version == null) == (updatedAt == null))
        require(version == null || version >= 1)
        require(version != null || !locationEnabled)
    }
    override fun toString() = "VehicleRecordingSettings([redacted])"
}

/** Vehicle-wide explicit choices, never inferred from a conversation or a saved summary. */
class VehiclePreferences(val recording: VehicleRecordingSettings, keywords: List<String>) {
    val keywords: List<String> = keywords.toList()
    val locationEnabled: Boolean get() = recording.locationEnabled
    val version: Long? get() = recording.version
    val updatedAt: Long? get() = recording.updatedAt
    override fun toString() = "VehiclePreferences([redacted])"
}
