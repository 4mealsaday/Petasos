package com.petasos.app.demo

/** Worker-owned explicit actions. Time itself is never a fabricated playback completion. */
internal class DemoReplayTimeline {
    data class Entry(val atMs: Long, val command: DemoControllerCommand)
    private val entries = mutableListOf<Entry>()
    private var pauseBeforeBranch = false
    var positionMs: Long = 0
        private set

    fun moveTo(position: Long) { require(position in 0..DURATION_MS); positionMs = position }

    fun record(command: DemoControllerCommand) {
        require(command in REPLAYABLE)
        // Editing after a rewind creates a branch; events from that old future cannot leak back.
        entries.removeAll { it.atMs > positionMs }
        if (pauseBeforeBranch && command != DemoControllerCommand.STOP) {
            // Seeking ends paused. Record that checkpoint only when the user edits this branch;
            // browsing another time alone must not rewrite the retained original future.
            entries += Entry(positionMs, DemoControllerCommand.STOP)
        }
        pauseBeforeBranch = false
        entries += Entry(positionMs, command)
    }

    fun through(target: Long): List<Entry> {
        require(target in 0..DURATION_MS)
        return entries.filter { it.atMs <= target }.toList()
    }

    /**
     * The first cancelling worker owns the Stop record, even if a revoked output callback already
     * stopped the runtime before this call. The later queued Stop sees the recorded Stop and is a
     * no-op for history. Seek's temporary pause deliberately does not call this method.
     */
    fun stopAndRecord(position: Long, wasPlaying: Boolean, stop: () -> Boolean): Boolean {
        moveTo(position)
        val lastIntent = entries.lastOrNull { it.atMs <= position &&
            (it.command == DemoControllerCommand.PLAY || it.command == DemoControllerCommand.STOP) }
        val needsStop = wasPlaying || lastIntent?.command == DemoControllerCommand.PLAY
        val stopped = stop()
        if (stopped && needsStop) record(DemoControllerCommand.STOP)
        return stopped
    }

    fun pausedAfterSeek() { pauseBeforeBranch = true }
    fun clear() { entries.clear(); positionMs = 0; pauseBeforeBranch = false }

    companion object {
        const val DURATION_MS = 180_000L
        private val REPLAYABLE = setOf(DemoControllerCommand.PLAY, DemoControllerCommand.STOP,
            DemoControllerCommand.NEXT, DemoControllerCommand.PREVIOUS,
            DemoControllerCommand.ROUTE_ANSEONG, DemoControllerCommand.ROUTE_YUSEONG,
            DemoControllerCommand.OBSERVATION_GAP)
    }
}

/** Monotonic, bounded steps; no catch-up crosses a background/paused interval. */
internal class DemoRealtimeClock {
    private var anchorMs: Long? = null
    val running: Boolean get() = anchorMs != null
    fun start(nowMs: Long) { anchorMs = nowMs }
    fun stop() { anchorMs = null }
    fun pending(nowMs: Long): Long = anchorMs?.let { (nowMs - it).coerceIn(0, 1_000) } ?: 0
    fun accepted(deltaMs: Long) {
        require(deltaMs in 0..1_000)
        anchorMs = anchorMs?.plus(deltaMs)
    }
}
