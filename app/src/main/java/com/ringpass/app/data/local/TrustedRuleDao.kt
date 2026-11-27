package com.ringpass.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrustedRuleDao {

    @Query("SELECT * FROM trusted_rules WHERE isSavedContact = 1 ORDER BY contactName COLLATE NOCASE ASC, createdAt DESC")
    fun observeWhitelistedContacts(): Flow<List<TrustedRule>>

    @Query("SELECT * FROM trusted_rules WHERE isSavedContact = 0 ORDER BY COALESCE(lastTriggeredAt, createdAt) DESC")
    fun observeNotedNumbers(): Flow<List<TrustedRule>>

    @Query("SELECT matchKey FROM trusted_rules")
    fun observeAllMatchKeys(): Flow<List<String>>

    @Query("SELECT * FROM trusted_rules WHERE matchKey = :matchKey LIMIT 1")
    suspend fun findByMatchKey(matchKey: String): TrustedRule?

    @Query("SELECT * FROM trusted_rules WHERE contactName IS NOT NULL AND LOWER(TRIM(contactName)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun findByExactContactName(name: String): TrustedRule?

    @Query("SELECT * FROM trusted_rules WHERE contactName IS NOT NULL")
    suspend fun getAllNamedContacts(): List<TrustedRule>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(rule: TrustedRule): Long

    @Query("""
        UPDATE trusted_rules
        SET rawNumber = :rawNumber, contactName = :contactName, isSavedContact = 1
        WHERE matchKey = :matchKey AND (isSavedContact = 0 OR :refreshExisting)
    """)
    suspend fun whitelistExistingContact(
        matchKey: String,
        rawNumber: String,
        contactName: String?,
        refreshExisting: Boolean
    ): Int

    @Transaction
    suspend fun importWhitelistedContacts(rules: List<TrustedRule>, refreshExisting: Boolean): Int {
        return rules.count { rule ->
            insertIfNew(rule) != -1L || whitelistExistingContact(
                rule.matchKey, rule.rawNumber, rule.contactName, refreshExisting
            ) > 0
        }
    }

    @Query("""
        UPDATE trusted_rules
        SET rawNumber = :rawNumber, note = :note, allowCalls = :allowCalls, allowMessages = :allowMessages
        WHERE matchKey = :matchKey
    """)
    suspend fun updateNumberNote(
        matchKey: String,
        rawNumber: String,
        note: String?,
        allowCalls: Boolean,
        allowMessages: Boolean
    )

    @Transaction
    suspend fun saveNotedNumber(rule: TrustedRule) {
        if (insertIfNew(rule) == -1L) {
            updateNumberNote(rule.matchKey, rule.rawNumber, rule.note, rule.allowCalls, rule.allowMessages)
        }
    }

    @Delete
    suspend fun delete(rule: TrustedRule)

    @Query("UPDATE trusted_rules SET allowCalls = :allowCalls WHERE id = :id")
    suspend fun updateAllowCalls(id: Long, allowCalls: Boolean)

    @Query("UPDATE trusted_rules SET allowMessages = :allowMessages WHERE id = :id")
    suspend fun updateAllowMessages(id: Long, allowMessages: Boolean)

    @Query("UPDATE trusted_rules SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)

    @Query("UPDATE trusted_rules SET customRingtoneUri = :uri, customRingtoneName = :name WHERE id = :id")
    suspend fun updateCustomRingtone(id: Long, uri: String?, name: String?): Int

    @Query("UPDATE trusted_rules SET lastTriggeredAt = :timestamp WHERE id = :id")
    suspend fun updateLastTriggeredAt(id: Long, timestamp: Long)
}
