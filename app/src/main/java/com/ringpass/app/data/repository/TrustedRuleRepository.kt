package com.ringpass.app.data.repository

import com.ringpass.app.data.local.TrustedRule
import com.ringpass.app.data.contacts.DeviceContactEntry
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

    suspend fun findForMessageSender(candidates: List<String>): TrustedRule? =
        findForRuleCandidates(candidates)

    suspend fun findForRuleCandidates(candidates: List<String>): TrustedRule? {
        val expanded = candidates.flatMap { raw ->
            val cleaned = PhoneNormalizer.cleanText(raw)
            if (cleaned.isBlank()) emptyList()
            else buildList {
                add(cleaned)
                val strippedCount = cleanSenderTitle(cleaned)
                if (strippedCount != cleaned && strippedCount.isNotBlank()) add(strippedCount)
                if (cleaned.contains(": ")) {
                    val afterColon = cleaned.substringAfterLast(": ").trim()
                    if (afterColon.isNotBlank()) add(afterColon)
                }
            }
        }.distinct()

        for (c in expanded) {
            if (PhoneNormalizer.looksLikePhoneNumber(c)) {
                val key = PhoneNormalizer.toMatchKey(c)
                if (key.isNotBlank()) dao.findByMatchKey(key)?.let { return it }
            }
        }
        for (c in expanded) {
            val cleanedName = cleanSenderTitle(c)
            if (cleanedName.isNotBlank()) dao.findByExactContactName(cleanedName)?.let { return it }
        }
        val named = dao.getAllNamedContacts()
        for (c in expanded) {
            val norm = cleanSenderTitle(c)
            val match = named.firstOrNull { rule ->
                val contact = PhoneNormalizer.cleanText(rule.contactName.orEmpty())
                contact.isNotEmpty() && (
                    norm.equals(contact, ignoreCase = true) ||
                        norm.startsWith("$contact (", ignoreCase = true) ||
                        norm.endsWith(": $contact", ignoreCase = true)
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
        dao.insertOrReplace(
            TrustedRule(
                id = existing?.id ?: 0L,
                rawNumber = rawNumber.trim(),
                matchKey = matchKey,
                contactName = PhoneNormalizer.cleanText(contactName),
                note = existing?.note,
                isSavedContact = true,
                allowCalls = existing?.allowCalls ?: true,
                allowMessages = existing?.allowMessages ?: true,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastTriggeredAt = existing?.lastTriggeredAt,
                customRingtoneUri = existing?.customRingtoneUri,
                customRingtoneName = existing?.customRingtoneName
            )
        )
        return true
    }

    suspend fun bulkWhitelistContacts(entries: List<DeviceContactEntry>): Int {
        if (entries.isEmpty()) return 0
        val rules = mutableListOf<TrustedRule>()
        for (entry in entries) {
            val key = entry.matchKey.ifBlank { PhoneNormalizer.toMatchKey(entry.rawNumber) }
            if (key.isBlank()) continue
            val existing = dao.findByMatchKey(key)
            rules.add(
                TrustedRule(
                    id = existing?.id ?: 0L,
                    rawNumber = entry.rawNumber.trim(),
                    matchKey = key,
                    contactName = PhoneNormalizer.cleanText(entry.displayName),
                    note = existing?.note,
                    isSavedContact = true,
                    allowCalls = existing?.allowCalls ?: true,
                    allowMessages = existing?.allowMessages ?: true,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    lastTriggeredAt = existing?.lastTriggeredAt,
                    customRingtoneUri = existing?.customRingtoneUri,
                    customRingtoneName = existing?.customRingtoneName
                )
            )
        }
        if (rules.isNotEmpty()) dao.insertAll(rules)
        return rules.size
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
        dao.insertOrReplace(
            TrustedRule(
                id = existing?.id ?: 0L,
                rawNumber = PhoneNormalizer.toE164(rawNumber).ifBlank { rawNumber.trim() },
                matchKey = matchKey,
                contactName = existing?.contactName,
                note = note.trim(),
                isSavedContact = existing?.isSavedContact ?: false,
                allowCalls = allowCalls,
                allowMessages = allowMessages,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastTriggeredAt = existing?.lastTriggeredAt,
                customRingtoneUri = existing?.customRingtoneUri,
                customRingtoneName = existing?.customRingtoneName
            )
        )
        return true
    }

    suspend fun setAllowCalls(id: Long, allow: Boolean) = dao.updateAllowCalls(id, allow)

    suspend fun setAllowMessages(id: Long, allow: Boolean) = dao.updateAllowMessages(id, allow)

    suspend fun updateNote(id: Long, note: String) = dao.updateNote(id, note.trim())
    suspend fun updateCustomRingtone(id: Long, uri: String?, name: String?) = dao.updateCustomRingtone(id, uri, name)

    suspend fun deleteRule(rule: TrustedRule) = dao.delete(rule)

    suspend fun markTriggered(id: Long) = dao.updateLastTriggeredAt(id, System.currentTimeMillis())

    private fun cleanSenderTitle(raw: String): String {
        val cleaned = PhoneNormalizer.cleanText(raw)
        val countSuffix = Regex("""\s*\(\d+\s+messages?\)$""", RegexOption.IGNORE_CASE)
        return cleaned.replace(countSuffix, "").trim()
    }
}
