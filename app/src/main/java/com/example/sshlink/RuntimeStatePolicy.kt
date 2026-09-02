package com.example.sshlink

/** Resolves process-local display state against durable Start/retry-block intent. */
object RuntimeStatePolicy {
    data class DisplayState(
        val status: TunnelState.Status,
        val detail: String,
        val desired: Boolean,
    )

    fun resolve(
        memoryStatus: TunnelState.Status,
        memoryDetail: String,
        memoryDesired: Boolean,
        persistedDesired: Boolean,
        retryBlocked: Boolean,
        retryReason: String,
    ): DisplayState {
        if (memoryDesired || memoryStatus != TunnelState.Status.STOPPED) {
            return DisplayState(memoryStatus, memoryDetail, memoryDesired)
        }
        if (!persistedDesired) {
            return DisplayState(TunnelState.Status.STOPPED, "Stopped", false)
        }
        if (retryBlocked) {
            return DisplayState(
                TunnelState.Status.ERROR,
                retryReason.ifBlank { "Manual action required before reconnecting" },
                true,
            )
        }
        return DisplayState(
            TunnelState.Status.STOPPED,
            "Tunnel is requested but the service is not currently connected",
            true,
        )
    }
}
