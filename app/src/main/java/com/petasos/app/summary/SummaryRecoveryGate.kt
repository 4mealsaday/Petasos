package com.petasos.app.summary

/** Recovery must commit before any worker claims a turn, regardless of Android startup ordering. */
class SummaryRecoveryGate {
    private var recovered: List<SummarySchedule>? = null

    @Synchronized fun ensure(recover: () -> List<SummarySchedule>): List<SummarySchedule> =
        recovered ?: recover().toList().also { recovered = it }
}
