package com.petasos.app.platform.automotive

import org.junit.Assert.*
import org.junit.Test

class VehiclePolicyTest {
    @Test fun unknownUxCannotEnablePreviewOrIndividualFeatures() {
        val policy = VehicleUxPolicy.unavailable()
        assertFalse(policy.permitsParkedPreview)
        assertFalse(policy.permitsFeature(1))
        assertEquals(0, policy.maxRestrictedStringLength)
        assertTrue(policy.requiresDistractionOptimization)
    }

    @Test fun previewRequiresKnownBaselineAndNoOptimizationRequirement() {
        assertTrue(ux(false, 0).permitsParkedPreview)
        assertFalse(ux(true, 0).permitsParkedPreview)
        assertFalse(ux(false, 4).permitsParkedPreview)
        assertFalse(ux(true, 4).permitsParkedPreview)
    }

    @Test fun featureFlagsArePreservedIndividuallyIncludingUnrecognizedBits() {
        val policy = ux(true, 4 or (1 shl 20))
        assertFalse(policy.permitsFeature(4))
        assertFalse(policy.permitsFeature(1 shl 20))
        assertTrue(policy.permitsFeature(8))
        assertFalse(policy.permitsFeature(0))
        assertFalse(policy.permitsFeature(-1))
    }

    @Test fun powerUnknownBlocksBothChannelsRegardlessOfStaleValues() {
        assertTrue(VehiclePowerPolicy.unavailable().audioBlocked)
        assertTrue(VehiclePowerPolicy.unavailable().microphoneBlocked)
        assertTrue(VehiclePowerPolicy(false, true, true).audioBlocked)
        assertTrue(VehiclePowerPolicy(false, true, true).microphoneBlocked)
    }

    @Test fun audioAndMicrophonePolicyRemainIndependent() {
        val listeningOnly = VehiclePowerPolicy(true, true, false)
        assertFalse(listeningOnly.audioBlocked)
        assertTrue(listeningOnly.microphoneBlocked)
        val noAudio = VehiclePowerPolicy(true, false, true)
        assertTrue(noAudio.audioBlocked)
        assertFalse(noAudio.microphoneBlocked)
    }

    private fun ux(requires: Boolean, flags: Int) = VehicleUxPolicy(true, requires, flags, 120, 20, 2)
}
