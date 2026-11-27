package com.ringpass.app

import android.app.NotificationManager
import android.content.ComponentName
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ringpass.app.service.ListenerDiagnostics
import com.ringpass.app.service.SelectiveNotificationListener
import com.ringpass.app.util.NotificationHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhatsAppCallSmokeTest {
    @Test
    fun listenerRingsForTrustedWhatsAppCallUntilItIsAnswered() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RingPassApplication
        val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, manager.currentInterruptionFilter)
        assertTrue(app.repository.upsertWhitelistedContact("6505550199", "Emulator caller"))
        val component = ComponentName(app, SelectiveNotificationListener::class.java)
        assertTrue(manager.isNotificationListenerAccessGranted(component))
        NotificationListenerService.requestRebind(component)
        withTimeout(20_000L) { ListenerDiagnostics.isConnected.first { it } }
        val probe = SystemClock.elapsedRealtimeNanos()
        assertTrue(NotificationHelper.postListenerProbe(app, probe))
        withTimeout(5_000L) { ListenerDiagnostics.probeReceived.first { it == probe } }
        manager.cancel(NotificationHelper.LISTENER_PROBE_ID)

        Log.i("RingPassSmoke", "READY_FOR_WHATSAPP")
        awaitDiagnostic("WhatsApp trusted ringtone started.")
        Log.i("RingPassSmoke", "READY_TO_ANSWER_WHATSAPP")
        awaitDiagnostic("WhatsApp ringtone stopped.")
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, manager.currentInterruptionFilter)
    }

    private suspend fun awaitDiagnostic(message: String) {
        withTimeout(15_000L) {
            while (ListenerDiagnostics.logs.value.none { it.endsWith(message) }) delay(100)
        }
    }
}
