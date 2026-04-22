package com.example.relapse_watch.services

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringForegroundServiceReminderTransitionTest {

    @Test
    fun `decideReminderTransition returns BASELINE when no prior state and currently inside`() {
        val decision = decideReminderTransition(
            previousInside = null,
            previousZoneSignature = null,
            currentInside = true,
            currentZoneSignature = "1.0|2.0|100"
        )

        assertEquals(ReminderTransitionDecision.BASELINE, decision)
    }

    @Test
    fun `decideReminderTransition returns BASELINE when no prior state and currently outside`() {
        val decision = decideReminderTransition(
            previousInside = null,
            previousZoneSignature = null,
            currentInside = false,
            currentZoneSignature = "1.0|2.0|100"
        )

        assertEquals(ReminderTransitionDecision.BASELINE, decision)
    }

    @Test
    fun `decideReminderTransition returns BASELINE when zone signature changes`() {
        val decision = decideReminderTransition(
            previousInside = false,
            previousZoneSignature = "1.0|2.0|100",
            currentInside = true,
            currentZoneSignature = "1.5|2.5|80"
        )

        assertEquals(ReminderTransitionDecision.BASELINE, decision)
    }

    @Test
    fun `decideReminderTransition returns TRIGGER for outside to inside with same zone`() {
        val decision = decideReminderTransition(
            previousInside = false,
            previousZoneSignature = "1.0|2.0|100",
            currentInside = true,
            currentZoneSignature = "1.0|2.0|100"
        )

        assertEquals(ReminderTransitionDecision.TRIGGER, decision)
    }

    @Test
    fun `decideReminderTransition returns NO_TRIGGER when still inside`() {
        val decision = decideReminderTransition(
            previousInside = true,
            previousZoneSignature = "1.0|2.0|100",
            currentInside = true,
            currentZoneSignature = "1.0|2.0|100"
        )

        assertEquals(ReminderTransitionDecision.NO_TRIGGER, decision)
    }

    @Test
    fun `decideReminderTransition returns NO_TRIGGER when leaving zone`() {
        val decision = decideReminderTransition(
            previousInside = true,
            previousZoneSignature = "1.0|2.0|100",
            currentInside = false,
            currentZoneSignature = "1.0|2.0|100"
        )

        assertEquals(ReminderTransitionDecision.NO_TRIGGER, decision)
    }
}
