package com.coldchain.handshake.alerts

/** Asks for an escalation check now. The app's own goes through WorkManager ([AlertEscalationScheduler.checkNow]). */
fun interface AlertChecks {
    fun checkNow()
}
