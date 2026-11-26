package com.ringpass.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrustedRule::class],
    version = 1,
    exportSchema = true
)
abstract class RingPassDatabase : RoomDatabase() {
    abstract fun trustedRuleDao(): TrustedRuleDao

    companion object {
        @Volatile
        private var INSTANCE: RingPassDatabase? = null

        fun getInstance(context: Context): RingPassDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RingPassDatabase::class.java,
                    "ringpass.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
