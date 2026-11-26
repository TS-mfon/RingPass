package com.ringpass.app.service

import android.telecom.Call
import android.telecom.CallScreeningService
import com.ringpass.app.RingPassApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class SelectiveCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        try {
            if (callDetails.callDirection == Call.Details.DIRECTION_INCOMING) {
                val rawNumber = callDetails.handle?.schemeSpecificPart.orEmpty()
                if (rawNumber.isNotBlank()) {
                    val app = applicationContext as RingPassApplication
                    val matchedRule = runBlocking(Dispatchers.IO) {
                        withTimeoutOrNull(1_500L) {
                            app.repository.findByNumber(rawNumber)
                        }
                    }

                    if (matchedRule != null && matchedRule.allowCalls) {
                        app.dndController.temporarilyUnmuteForCall()

                        runBlocking(Dispatchers.IO) {
                            withTimeoutOrNull(500L) {
                                app.repository.markTriggered(matchedRule.id)
                            }
                        }

                        val note = matchedRule.note?.trim()
                        if (!note.isNullOrEmpty()) {
                            NoteOverlayService.show(
                                context = applicationContext,
                                number = rawNumber,
                                note = note
                            )
                        }
                    }
                }
            }
        } finally {
            respondToCall(callDetails, CallResponse.Builder().build())
        }
    }
}
