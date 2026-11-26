package com.ringpass.app.data.repository

import com.ringpass.app.data.local.TrustedRule
import com.ringpass.app.data.local.TrustedRuleDao
import com.ringpass.app.util.PhoneNormalizer
import kotlinx.coroutines.flow.Flow

class TrustedRuleRepository(
    private val dao: TrustedRuleDao
) {
    val whitelistedContacts: Flow<List<TrustedRule>> = dao.observeWhitelistedContacts()
    val notedNumbers: Flow<List<TrustedRule>> = dao.observeNotedNumbers()
    val allMatchKeys: Flow<List<String>> = dao.observeAllMatchKeys()

    suspend fun findByNumber(rawNumber: String): TrustedRule? {
        val key = PhoneNormalizer.toMatchKey(rawNumber)
        if (key.isBlank()) return null
        return dao.findByMatchKey(key)
    }

    suspend fun findForMessageSender(candidates: List<String>): TrustedRule? {
        for (candidate in candidates) {
            if (PhoneNormalizer.looksLikePhoneNumber(candidate)) {
                val key = PhoneNormalizer.toMatchKey(candidate)
                if (key.isNotBlank()) {
                    val matched = dao.findByMatchKey(key)
                    if (matched != null) return matched
                }
            }
        }

        for (candidate in candidates) {
            val cleanedName = cleanSenderTitle(candidate)
            if (cleanedName.isNotBlank()) {
                val exact = dao.findByExactContactName(cleanedName)
                if (exact != null) return exact
            }
        }

        val messageContacts = dao.getMessageEnabledContacts()
        for (candidate in candidates) {
            val normalizedCandidate = candidate.trim()
            val match = messageContacts.firstOrNull { rule ->
                val contact = rule.contactName?.trim().orEmpty()
                contact.isNotEmpty() && (
                    normalizedCandidate.equals(contact, ignoreCase = true) ||
                        normalizedCandidate.startsWith("$contact (", ignoreCase = true) ||
                        normalizedCandidate.endsWith(": $contact", ignoreCase = true)
                    )
            }
            if (match != null) return match
        }
        return null
    }

    suspend fun upsertWhitelistedContact(rawNumber: String, contactName: String): Boolean {
        val matchKey = PhoneNormalizer.toMatchKey(rawNumber)
        if (matchKey.isBlank()) return false

        val existing = dao.findByMatchKey(matchKey)
        val rule = TrustedRule(
            id = existing?.id ?: 0L,
            rawNumber = rawNumber.trim(),
            matchKey = matchKey,
            contactName = contactName.trim(),
            note = existing?.note,
            isSavedContact = true,
            allowCalls = existing?.allowCalls ?: true,
            allowMessages = existing?.allowMessages ?: true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastTriggeredAt = existing?.lastTriggeredAt
        )
        dao.insertOrReplace(rule)
        return true
    }

    suspend fun upsertNotedNumber(
        rawNumber: String,
        note: String,
        allowCalls: Boolean = true,
        allowMessages: Boolean = true
    ): Boolean {
        val matchKey = PhoneNormalizer.toMatchKey(rawNumber)
        if (matchKey.isBlank()) return false

        val existing = dao.findByMatchKey(matchKey)
        val rule = TrustedRule(
            id = existing?.id ?: 0L,
            rawNumber = PhoneNormalizer.toE164(rawNumber).ifBlank { rawNumber.trim() },
            matchKey = matchKey,
            contactName = existing?.contactName,
            note = note.trim(),
            isSavedContact = existing?.isSavedContact ?: false,
            allowCalls = allowCalls,
            allowMessages = allowMessages,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastTriggeredAt = existing?.lastTriggeredAt
        )
        dao.insertOrReplace(rule)
        return true
    }

    suspend fun setAllowCalls(id: Long, allow: Boolean) = dao.updateAllowCalls(id, allow)

    suspend fun setAllowMessages(id: Long, allow: Boolean) = dao.updateAllowMessages(id, allow)

    suspend fun updateNote(id: Long, note: String) = dao.updateNote(id, note.trim())

    suspend fun deleteRule(rule: TrustedRule) = dao.delete(rule)

    suspend fun markTriggered(id: Long) = dao.updateLastTriggeredAt(id, System.currentTimeMillis())

    private fun cleanSenderTitle(raw: String): String {
        val trimmed = raw.trim()
        val countSuffix = Regex("""\s*\(\d+\s+messages?\)$""", RegexOption.IGNORE_CASE)
        return trimmed.replace(countSuffix, "").trim()
    }
}
