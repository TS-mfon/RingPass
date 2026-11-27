package com.ringpass.app.service

import org.junit.Assert.*
import org.junit.Test

class CallAlertPolicyTest {
    @Test fun `CallStyle incoming uses type before fallback text and actions`() {
        assertEquals(WhatsAppEvent.RINGING, whatsAppEvent(callType = 1, text = "Appel vidéo"))
        assertEquals(WhatsAppEvent.OTHER_CALL, whatsAppEvent(callType = 2, hasAnswer = true))
        assertEquals(WhatsAppEvent.OTHER_CALL, whatsAppEvent(callType = 3, hasAnswer = true))
    }

    @Test fun `legacy WhatsApp voice and video calls are identified by call metadata`() {
        assertEquals(WhatsAppEvent.RINGING, whatsAppEvent(callChannel = true, actionCount = 2))
        assertEquals(WhatsAppEvent.RINGING, whatsAppEvent(callCategory = true, text = "Incoming video call"))
        assertEquals(WhatsAppEvent.RINGING, whatsAppEvent(hasAnswer = true))
    }

    @Test fun `missed calls and chats mentioning calls do not ring`() {
        assertEquals(WhatsAppEvent.OTHER_CALL, whatsAppEvent(callCategory = true, text = "Missed voice call", actionCount = 2))
        assertEquals(WhatsAppEvent.OTHER_CALL, whatsAppEvent(callChannel = true, hasHangUp = true, actionCount = 2))
        assertEquals(WhatsAppEvent.MESSAGE, whatsAppEvent(text = "Can you join an incoming voice call?"))
        assertEquals(WhatsAppEvent.OTHER_CALL, whatsAppEvent(callChannel = true, actionCount = 1))
    }

    @Test fun `priority mode requires alarm permission while total silence always blocks audio`() {
        assertTrue(allowsAlarmAudio(DndMode.OFF, alarmsInPriority = false))
        assertTrue(allowsAlarmAudio(DndMode.ALARMS_ONLY, alarmsInPriority = false))
        assertTrue(allowsAlarmAudio(DndMode.PRIORITY, alarmsInPriority = true))
        assertFalse(allowsAlarmAudio(DndMode.PRIORITY, alarmsInPriority = false))
        assertFalse(allowsAlarmAudio(DndMode.TOTAL_SILENCE, alarmsInPriority = true))
        assertFalse(allowsAlarmAudio(DndMode.UNKNOWN, alarmsInPriority = true))
    }

    @Test fun `contact ringtone takes precedence with global then system fallback`() {
        assertEquals(listOf("contact", "global", "system", "alarm"), ringtoneCandidates("contact", "global", listOf("system", "alarm")))
        assertEquals(listOf("global", "system"), ringtoneCandidates(null, "global", listOf("global", "system")))
        assertEquals(listOf("system"), ringtoneCandidates("", null, listOf("system")))
    }
}
