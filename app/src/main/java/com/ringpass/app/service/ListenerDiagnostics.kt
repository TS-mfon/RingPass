package com.ringpass.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ListenerDiagnostics {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _probeReceived = MutableStateFlow(0L)
    val probeReceived: StateFlow<Long> = _probeReceived.asStateFlow()

    fun onProbeReceived(token: Long) {
        _probeReceived.value = token
        log("Android delivered the notification listener test.")
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
        log(if (connected) "Listener connected to Android" else "Listener disconnected")
    }

    @Synchronized
    fun log(message: String) {
        val entry = "${timeFormat.format(Date())}  $message"
        _logs.value = (listOf(entry) + _logs.value).take(10)
    }
}
