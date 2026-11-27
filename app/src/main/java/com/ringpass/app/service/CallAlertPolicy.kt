package com.ringpass.app.service

import android.app.Notification.CallStyle
import java.util.Locale

internal enum class WhatsAppEvent { RINGING, OTHER_CALL, MESSAGE }

internal fun whatsAppEvent(
    callType: Int = CallStyle.CALL_TYPE_UNKNOWN,
    callCategory: Boolean = false,
    callChannel: Boolean = false,
    hasAnswer: Boolean = false,
    hasHangUp: Boolean = false,
    actionCount: Int = 0,
    text: String = ""
): WhatsAppEvent {
    if (callType == CallStyle.CALL_TYPE_INCOMING) return WhatsAppEvent.RINGING
    if (callType != CallStyle.CALL_TYPE_UNKNOWN || hasHangUp) return WhatsAppEvent.OTHER_CALL
    if (hasAnswer) return WhatsAppEvent.RINGING
    if (!callCategory && !callChannel) return WhatsAppEvent.MESSAGE

    val detail = text.lowercase(Locale.ROOT)
    if (listOf("missed", "ongoing", "outgoing", "calling", "ended", "declined", "in call")
            .any(detail::contains)
    ) return WhatsAppEvent.OTHER_CALL
    if (actionCount >= 2 || detail.startsWith("incoming") ||
        detail == "voice call" || detail == "video call"
    ) return WhatsAppEvent.RINGING
    return WhatsAppEvent.OTHER_CALL
}

internal enum class DndMode { OFF, PRIORITY, ALARMS_ONLY, TOTAL_SILENCE, UNKNOWN }

internal fun allowsAlarmAudio(mode: DndMode, alarmsInPriority: Boolean): Boolean =
    mode == DndMode.OFF || mode == DndMode.ALARMS_ONLY ||
        (mode == DndMode.PRIORITY && alarmsInPriority)

internal fun ringtoneCandidates(
    contactUri: String?,
    globalUri: String?,
    systemUris: List<String>
): List<String> = (listOfNotNull(contactUri, globalUri) + systemUris)
    .filter(String::isNotBlank)
    .distinct()
