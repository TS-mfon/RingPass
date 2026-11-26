package com.ringpass.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrustedRule::class],
    version = 2,
    exportSchema = true
)
abstract class RingPassDatabase : RoomDatabase() {
    abstract fun trustedRuleDao(): TrustedRuleDao

    companion object {
        @Volatile
        private var INSTANCE: RingPassDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trusted_rules ADD COLUMN customRingtoneUri TEXT")
                db.execSQL("ALTER TABLE trusted_rules ADD COLUMN customRingtoneName TEXT")
            }
        }

        fun getInstance(context: Context): RingPassDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RingPassDatabase::class.java,
                    "ringpass.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
