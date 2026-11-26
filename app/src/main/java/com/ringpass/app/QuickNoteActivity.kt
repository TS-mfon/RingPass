package com.ringpass.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.ringpass.app.ui.components.AddOrEditNoteDialog
import com.ringpass.app.ui.theme.RingPassTheme
import com.ringpass.app.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickNoteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val number = intent.getStringExtra(NotificationHelper.EXTRA_NUMBER).orEmpty()
        if (number.isBlank()) {
            finish()
            return
        }

        val app = application as RingPassApplication

        setContent {
            RingPassTheme {
                AddOrEditNoteDialog(
                    title = "Quick Note for Caller",
                    initialNumber = number,
                    initialNote = "",
                    allowEditingNumber = false,
                    onDismiss = { finish() },
                    onConfirm = { rawNumber, note ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            app.repository.upsertNotedNumber(rawNumber = rawNumber, note = note)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@QuickNoteActivity,
                                    "Saved note & enabled DND ring override",
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            }
                        }
                    }
                )
            }
        }
    }
}
