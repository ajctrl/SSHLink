package com.example.sshlink

/** Pure state predicates used by TunnelService and covered by local unit tests. */
object TunnelLifecyclePolicy {
    enum class RestartDecision { RUN, STOP_NOT_DESIRED, STOP_RETRY_BLOCKED }

    fun restartDecision(desired: Boolean, retryBlocked: Boolean): RestartDecision = when {
        !desired -> RestartDecision.STOP_NOT_DESIRED
        retryBlocked -> RestartDecision.STOP_RETRY_BLOCKED
        else -> RestartDecision.RUN
    }

    fun shouldAutomaticallyReconnect(
        desired: Boolean,
        retryBlocked: Boolean,
        statusIsError: Boolean,
    ): Boolean = desired && !retryBlocked && !statusIsError

}
