package com.ringpass.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

object RingtonePrefs {
    private const val PREFS_NAME = "ringpass_audio_prefs"
    private const val KEY_GLOBAL_URI = "global_ringtone_uri"
    private const val KEY_GLOBAL_NAME = "global_ringtone_name"

    fun getGlobalUri(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GLOBAL_URI, null)?.takeIf { it.isNotBlank() }

    fun getGlobalName(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GLOBAL_NAME, null)?.takeIf { it.isNotBlank() }

    fun setGlobalRingtone(context: Context, uriString: String?, displayName: String?) {
        val ed = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (uriString.isNullOrBlank()) ed.remove(KEY_GLOBAL_URI).remove(KEY_GLOBAL_NAME)
        else ed.putString(KEY_GLOBAL_URI, uriString).putString(KEY_GLOBAL_NAME, displayName ?: "Custom audio")
        ed.apply()
    }

    fun persistUriPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun resolveDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Custom audio"
    }
}
