package com.ringpass.app

import android.app.NotificationManager
import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ringpass.app.data.contacts.DeviceContactEntry
import com.ringpass.app.service.ListenerDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CellularCallSmokeTest {
    @Test
    fun trustedCellularCallRingsAndStopsWithoutChangingDnd() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as RingPassApplication
        val manager = checkNotNull(app.getSystemService(NotificationManager::class.java))
        val audio = checkNotNull(app.getSystemService(AudioManager::class.java))
        val rule = DeviceContactEntry("Emulator caller", "6505550199", "6505550199")
        val imported = app.repository.bulkWhitelistContacts(listOf(rule, rule))
        assertEquals(1, imported)
        assertEquals(0, app.repository.bulkWhitelistContacts(listOf(rule)))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, manager.currentInterruptionFilter)
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0)

        Log.i("RingPassSmoke", "READY_FOR_CALL")
        awaitDiagnostic("Cellular trusted ringtone started.")
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, manager.currentInterruptionFilter)

        Log.i("RingPassSmoke", "READY_TO_END_CALL")
        awaitDiagnostic("Cellular trusted ringtone stopped.")
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALARMS, manager.currentInterruptionFilter)
    }

    private suspend fun awaitDiagnostic(message: String) {
        withTimeout(20_000L) {
            while (ListenerDiagnostics.logs.value.none { it.endsWith(message) }) delay(100)
        }
    }
}
