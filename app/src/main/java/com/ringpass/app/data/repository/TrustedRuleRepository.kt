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

    suspend fun findForRuleCandidates(candidates: List<String>): TrustedRule? {
        val titles = candidates.map(::cleanSenderTitle).filter(String::isNotBlank).distinct()
        val numbers = titles.filter(PhoneNormalizer::looksLikePhoneNumber)
        if (numbers.isNotEmpty()) return numbers.firstNotNullOfOrNull { findByNumber(it) }
        if (titles.isEmpty()) return null

        val named = dao.getAllNamedContacts().filter(TrustedRule::isSavedContact)
        for (title in titles) {
            val matching = named.filter {
                PhoneNormalizer.cleanText(it.contactName.orEmpty()).equals(title, ignoreCase = true)
            }
            val first = matching.firstOrNull() ?: continue
            if (matching.all {
                it.allowCalls == first.allowCalls && it.allowMessages == first.allowMessages &&
                    it.customRingtoneUri == first.customRingtoneUri
            }) return first
            return null
        }
        return null
    }

    suspend fun upsertWhitelistedContact(rawNumber: String, contactName: String): Boolean {
        return whitelistContactNumbers(contactName, listOf(rawNumber)) == 1
    }

    suspend fun whitelistContactNumbers(contactName: String, rawNumbers: List<String>): Int {
        val rules = rawNumbers.mapNotNull { contactRule(it, contactName) }.distinctBy(TrustedRule::matchKey)
        return dao.importWhitelistedContacts(rules, refreshExisting = true)
    }

    suspend fun bulkWhitelistContacts(entries: List<DeviceContactEntry>): Int {
        val rules = entries.mapNotNull { contactRule(it.rawNumber, it.displayName) }
            .distinctBy(TrustedRule::matchKey)
        return dao.importWhitelistedContacts(rules, refreshExisting = false)
    }

    private fun contactRule(rawNumber: String, contactName: String): TrustedRule? {
        val matchKey = PhoneNormalizer.toMatchKey(rawNumber)
        if (matchKey.length < 7) return null
        return TrustedRule(
            rawNumber = rawNumber.trim(),
            matchKey = matchKey,
            contactName = PhoneNormalizer.cleanText(contactName).ifBlank { null },
            isSavedContact = true
        )
    }

    suspend fun upsertNotedNumber(
        rawNumber: String,
        note: String,
        allowCalls: Boolean = true,
        allowMessages: Boolean = true
    ): Boolean {
        val matchKey = PhoneNormalizer.toMatchKey(rawNumber)
        if (matchKey.length < 7 || note.isBlank()) return false
        dao.saveNotedNumber(
            TrustedRule(
                rawNumber = PhoneNormalizer.toE164(rawNumber).ifBlank { rawNumber.trim() },
                matchKey = matchKey,
                note = note.trim(),
                isSavedContact = false,
                allowCalls = allowCalls,
                allowMessages = allowMessages
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
