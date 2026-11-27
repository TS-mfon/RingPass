package com.ringpass.app.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.ringpass.app.util.PhoneNormalizer
import java.text.Collator

data class PickedContactResult(
    val displayName: String,
    val phoneNumbers: List<String>
)

data class RecentUnknownCaller(
    val number: String,
    val matchKey: String,
    val timestamp: Long,
    val callType: Int
)

data class DeviceContactEntry(
    val displayName: String,
    val rawNumber: String,
    val matchKey: String
)

object DeviceContactsAndCallLogHelper {

    fun extractContactFromPickerUri(context: Context, contactUri: Uri): PickedContactResult? {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return null

        var contactId: String? = null
        var displayName = "Contact"

        context.contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                contactId = cursor.getString(0)
                displayName = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "Contact"
            }
        }

        val id = contactId ?: return null
        val numbers = linkedSetOf<String>()

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(id),
            null
        )?.use { phoneCursor ->
            while (phoneCursor.moveToNext()) {
                val number = phoneCursor.getString(0)?.trim()
                if (!number.isNullOrBlank() && PhoneNormalizer.toMatchKey(number).length >= 7) {
                    numbers.add(number)
                }
            }
        }

        return PickedContactResult(displayName = displayName, phoneNumbers = numbers.distinctBy(PhoneNormalizer::toMatchKey))
    }

    fun isNumberInSystemContacts(context: Context, rawNumber: String): Boolean {
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) return false
        if (rawNumber.isBlank()) return false

        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(rawNumber)
        )
        return try {
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } ?: false
        } catch (_: SecurityException) {
            false
        }
    }

    fun getLatestIncomingCallNumber(
        context: Context,
        maxAgeMillis: Long = 180_000L
    ): String? {
        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) return null
        val minTime = System.currentTimeMillis() - maxAgeMillis

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} IN (?, ?, ?)",
                arrayOf(
                    minTime.toString(),
                    CallLog.Calls.INCOMING_TYPE.toString(),
                    CallLog.Calls.MISSED_TYPE.toString(),
                    CallLog.Calls.REJECTED_TYPE.toString()
                ),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf {
                        it.isNotBlank() && PhoneNormalizer.toMatchKey(it).length >= 7
                    }
                } else {
                    null
                }
            }
        } catch (_: SecurityException) {
            null
        }
    }

    fun queryRecentUnknownCallers(
        context: Context,
        excludedMatchKeys: Set<String>,
        limit: Int = 15
    ): List<RecentUnknownCaller> {
        if (!hasPermission(context, Manifest.permission.READ_CALL_LOG)) return emptyList()

        val seenKeys = HashSet<String>(excludedMatchKeys)
        val results = mutableListOf<RecentUnknownCaller>()

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.CACHED_NAME
                ),
                "${CallLog.Calls.TYPE} IN (?, ?, ?)",
                arrayOf(
                    CallLog.Calls.INCOMING_TYPE.toString(),
                    CallLog.Calls.MISSED_TYPE.toString(),
                    CallLog.Calls.REJECTED_TYPE.toString()
                ),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && results.size < limit) {
                    val number = cursor.getString(0)?.trim().orEmpty()
                    val date = cursor.getLong(1)
                    val type = cursor.getInt(2)
                    val cachedName = cursor.getString(3)

                    if (number.isBlank()) continue
                    val matchKey = PhoneNormalizer.toMatchKey(number)
                    if (matchKey.length < 7) continue
                    if (seenKeys.contains(matchKey)) continue

                    val savedInContacts = !cachedName.isNullOrBlank() ||
                        isNumberInSystemContacts(context, number)
                    if (savedInContacts) {
                        seenKeys.add(matchKey)
                        continue
                    }

                    seenKeys.add(matchKey)
                    results.add(
                        RecentUnknownCaller(
                            number = number,
                            matchKey = matchKey,
                            timestamp = date,
                            callType = type
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }

        return results
    }

    fun loadAllDeviceContacts(context: Context): List<DeviceContactEntry> {
        val results = mutableListOf<DeviceContactEntry>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = PhoneNormalizer.cleanText(cursor.getString(0).orEmpty()).ifBlank { "Unnamed contact" }
                val number = cursor.getString(1)?.trim().orEmpty()
                val matchKey = PhoneNormalizer.toMatchKey(number)
                if (matchKey.length < 7) continue
                results.add(DeviceContactEntry(displayName = name, rawNumber = number, matchKey = matchKey))
            }
        }
        val names = Collator.getInstance()
        return results.sortedWith { left, right -> names.compare(left.displayName, right.displayName) }
            .distinctBy(DeviceContactEntry::matchKey)
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
