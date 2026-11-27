package com.ringpass.app.data.repository

import com.ringpass.app.data.contacts.DeviceContactEntry
import com.ringpass.app.data.local.TrustedRule
import com.ringpass.app.data.local.TrustedRuleDao
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BulkWhitelistContactsTest {
    @Test
    fun `bulk import promotes a noted number and counts only newly whitelisted numbers`() = runBlocking {
        val noted = TrustedRule(
            id = 1L,
            rawNumber = "08031234567",
            matchKey = "8031234567",
            note = "Courier",
            isSavedContact = false,
            allowCalls = false,
            allowMessages = false,
            createdAt = 100L,
            lastTriggeredAt = 200L,
            customRingtoneUri = "content://audio/courier",
            customRingtoneName = "Courier.mp3"
        )
        val alreadyWhitelisted = TrustedRule(
            id = 2L,
            rawNumber = "08051234567",
            matchKey = "8051234567",
            contactName = "Original name",
            isSavedContact = true
        )
        val repository = TrustedRuleRepository(InMemoryRules(listOf(noted, alreadyWhitelisted)))
        val selected = listOf(
            DeviceContactEntry("Courier", "+234 803 123 4567", "8031234567"),
            DeviceContactEntry("Duplicate", "08031234567", "8031234567"),
            DeviceContactEntry("Potential rename", "08051234567", "8051234567"),
            DeviceContactEntry("New contact", "08061234567", "unused-input-key"),
            DeviceContactEntry("Missing number", "", "8071234567")
        )

        assertEquals(2, repository.bulkWhitelistContacts(selected))
        assertEquals(
            noted.copy(rawNumber = "+234 803 123 4567", contactName = "Courier", isSavedContact = true),
            repository.findByNumber("08031234567")
        )
        assertEquals(alreadyWhitelisted, repository.findByNumber("08051234567"))
        assertEquals("New contact", repository.findByNumber("08061234567")?.contactName)
        assertNull(repository.findByNumber("08071234567"))
        assertEquals(0, repository.bulkWhitelistContacts(selected))
    }

    @Test
    fun `explicit unknown number cannot fall back to a trusted contact name`() = runBlocking {
        val rule = TrustedRule(
            id = 1L,
            rawNumber = "08031234567",
            matchKey = "8031234567",
            contactName = "Ada",
            isSavedContact = true
        )
        val repository = TrustedRuleRepository(InMemoryRules(listOf(rule)))
        assertEquals(rule, repository.findForRuleCandidates(listOf("Ada")))
        assertEquals(rule, repository.findForRuleCandidates(listOf("+234 803 123 4567")))
        assertNull(repository.findForRuleCandidates(listOf("+234 805 123 4567", "Ada")))
    }

    @Test
    fun `same display name with conflicting call rules does not guess`() = runBlocking {
        val rule = TrustedRule(
            id = 1L, rawNumber = "08031234567", matchKey = "8031234567",
            contactName = "Ada", isSavedContact = true
        )
        val muted = rule.copy(
            id = 2L, rawNumber = "08051234567", matchKey = "8051234567", allowCalls = false
        )
        val repository = TrustedRuleRepository(InMemoryRules(listOf(rule, muted)))
        assertNull(repository.findForRuleCandidates(listOf("Ada")))
        assertEquals(muted, repository.findForRuleCandidates(listOf("08051234567", "Ada")))
    }

    private class InMemoryRules(initial: List<TrustedRule>) : TrustedRuleDao {
        private var rules = initial.associateBy(TrustedRule::matchKey)

        override suspend fun findByMatchKey(matchKey: String) = rules[matchKey]

        override suspend fun insertIfNew(rule: TrustedRule): Long {
            if (rule.matchKey in rules) return -1L
            val id = (rules.values.maxOfOrNull { it.id } ?: 0L) + 1L
            rules = rules + (rule.matchKey to rule.copy(id = id))
            return id
        }

        override suspend fun whitelistExistingContact(
            matchKey: String,
            rawNumber: String,
            contactName: String?,
            refreshExisting: Boolean
        ): Int {
            val current = rules[matchKey] ?: return 0
            if (current.isSavedContact && !refreshExisting) return 0
            val promoted = current.copy(rawNumber = rawNumber, contactName = contactName, isSavedContact = true)
            rules = rules + (matchKey to promoted)
            return 1
        }

        override fun observeWhitelistedContacts() = flowOf(rules.values.filter { it.isSavedContact })
        override fun observeNotedNumbers() = flowOf(rules.values.filterNot { it.isSavedContact })
        override fun observeAllMatchKeys() = flowOf(rules.keys.toList())
        override suspend fun findByExactContactName(name: String) = unexpectedCall()
        override suspend fun getAllNamedContacts() = rules.values.filter { it.contactName != null }
        override suspend fun updateNumberNote(
            matchKey: String,
            rawNumber: String,
            note: String?,
            allowCalls: Boolean,
            allowMessages: Boolean
        ) = unexpectedCall()
        override suspend fun delete(rule: TrustedRule) = unexpectedCall()
        override suspend fun updateAllowCalls(id: Long, allowCalls: Boolean) = unexpectedCall()
        override suspend fun updateAllowMessages(id: Long, allowMessages: Boolean) = unexpectedCall()
        override suspend fun updateNote(id: Long, note: String?) = unexpectedCall()
        override suspend fun updateCustomRingtone(id: Long, uri: String?, name: String?) = unexpectedCall()
        override suspend fun updateLastTriggeredAt(id: Long, timestamp: Long) = unexpectedCall()
        private fun unexpectedCall(): Nothing = error("Bulk import called an unrelated DAO method")
    }
}
