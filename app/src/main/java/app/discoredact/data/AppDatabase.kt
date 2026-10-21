package app.discoredact.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        ChannelEntity::class,
        MessageEntity::class,
        RunEntity::class,
        EventEntity::class,
        RunMessageEntity::class,
        SelectionOverrideEntity::class,
        ChannelInclusionEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun messages(): MessageDao
    abstract fun runs(): RunDao

    companion object {
        const val NAME = "discoredact.db"
        const val VERSION = 1

        /**
         * Every schema change adds a migration here and a test (CLAUDE.md rule 19). There is deliberately no
         * destructive fallback: an upgrade without a migration fails instead of wiping the user's selections.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
