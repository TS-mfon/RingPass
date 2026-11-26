package com.ringpass.app.service

import android.net.Uri
import android.telecom.Call
import android.telecom.CallScreeningService
import com.ringpass.app.RingPassApplication
import com.ringpass.app.util.RingtonePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class SelectiveCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        var silenceSystemRinger = false
        try {
            if (callDetails.callDirection == Call.Details.DIRECTION_INCOMING) {
                val rawNumber = Uri.decode(callDetails.handle?.schemeSpecificPart.orEmpty()).substringBefore(';')
                if (rawNumber.isNotBlank()) {
                    val app = applicationContext as RingPassApplication
                    val matchedRule = runBlocking(Dispatchers.IO) {
                        withTimeoutOrNull(1_500L) { app.repository.findByNumber(rawNumber) }
                    }
                    if (matchedRule != null && matchedRule.allowCalls) {
                        val customUri = matchedRule.customRingtoneUri ?: RingtonePrefs.getGlobalUri(applicationContext)
                        silenceSystemRinger = customUri != null
                        app.dndController.temporarilyUnmuteForCall(customRingtoneUri = customUri)
                        runBlocking(Dispatchers.IO) {
                            withTimeoutOrNull(500L) { app.repository.markTriggered(matchedRule.id) }
                        }
                        val note = matchedRule.note?.trim()
                        if (!note.isNullOrEmpty()) {
                            NoteOverlayService.show(context = applicationContext, number = rawNumber, note = note)
                        }
                    }
                }
            }
        } finally {
            val response = CallResponse.Builder()
                .apply { if (silenceSystemRinger) setSilenceCall(true) }
                .build()
            respondToCall(callDetails, response)
        }
    }
}
