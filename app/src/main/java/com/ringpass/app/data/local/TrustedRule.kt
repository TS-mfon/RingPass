package com.ringpass.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trusted_rules",
    indices = [Index(value = ["matchKey"], unique = true)]
)
data class TrustedRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val rawNumber: String,
    val matchKey: String,
    val contactName: String? = null,
    val note: String? = null,
    val isSavedContact: Boolean,
    val allowCalls: Boolean = true,
    val allowMessages: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggeredAt: Long? = null,
    val customRingtoneUri: String? = null,
    val customRingtoneName: String? = null
)
