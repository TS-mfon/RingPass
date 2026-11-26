package com.ringpass.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ringpass.app.ui.RingPassApp
import com.ringpass.app.ui.theme.RingPassTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RingPassTheme {
                RingPassApp()
            }
        }
    }
}
